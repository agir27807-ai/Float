package com.hotmailreader.app.network

import com.hotmailreader.app.model.MailConnectionModes
import com.hotmailreader.app.model.MailReadResponse
import com.hotmailreader.app.model.MailboxAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object DongvanApiClient {
    private const val OAUTH_ENDPOINT = "https://tools.dongvanfb.net/api/get_code_oauth2"
    private const val GRAPH_ENDPOINT = "https://tools.dongvanfb.net/api/graph_code"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val preferredEndpointByAccount = ConcurrentHashMap<String, String>()

    private val httpDispatcher = Dispatcher().apply {
        maxRequests = 48
        maxRequestsPerHost = 24
    }

    private val httpClient = OkHttpClient.Builder()
        .dispatcher(httpDispatcher)
        .connectionPool(ConnectionPool(16, 2, TimeUnit.MINUTES))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun fetchMessagesBatch(accounts: List<MailboxAccount>): Map<String, MailReadResponse> =
        withContext(Dispatchers.IO) {
            if (accounts.isEmpty()) return@withContext emptyMap()
            coroutineScope {
                accounts.map { account ->
                    async {
                        account.id to fetchCode(account)
                    }
                }.awaitAll().toMap()
            }
        }

    suspend fun fetchCode(account: MailboxAccount): MailReadResponse {
        return when (account.connectionMode) {
            MailConnectionModes.GRAPH -> {
                val res = fetchFromEndpoint(GRAPH_ENDPOINT, account)
                if (res.status) preferredEndpointByAccount[account.email.lowercase()] = GRAPH_ENDPOINT
                res
            }
            MailConnectionModes.OAUTH -> {
                val res = fetchFromEndpoint(OAUTH_ENDPOINT, account)
                if (res.status) preferredEndpointByAccount[account.email.lowercase()] = OAUTH_ENDPOINT
                res
            }
            else -> fetchAutoParallel(account)
        }
    }

    private suspend fun fetchAutoParallel(account: MailboxAccount): MailReadResponse = coroutineScope {
        val emailKey = account.email.lowercase()
        val cached = preferredEndpointByAccount[emailKey]
        if (cached != null) {
            val first = fetchFromEndpoint(cached, account)
            if (first.status) return@coroutineScope first
            val fallbackEndpoint = if (cached == OAUTH_ENDPOINT) GRAPH_ENDPOINT else OAUTH_ENDPOINT
            val second = fetchFromEndpoint(fallbackEndpoint, account)
            if (second.status) {
                preferredEndpointByAccount[emailKey] = fallbackEndpoint
                return@coroutineScope second
            }
            val oauthRes = if (cached == OAUTH_ENDPOINT) first else second
            val graphRes = if (cached == GRAPH_ENDPOINT) first else second
            return@coroutineScope combineFailureResponses(account, oauthRes, graphRes)
        }

        val oauthCallRef = AtomicReference<Call?>(null)
        val graphCallRef = AtomicReference<Call?>(null)
        val winner = CompletableDeferred<MailReadResponse>()

        val oauthDeferred = async(Dispatchers.IO) {
            val res = fetchFromEndpoint(OAUTH_ENDPOINT, account, oauthCallRef)
            if (res.status && winner.complete(res)) {
                preferredEndpointByAccount[emailKey] = OAUTH_ENDPOINT
                graphCallRef.get()?.cancel()
            }
            res
        }

        val graphDeferred = async(Dispatchers.IO) {
            val res = fetchFromEndpoint(GRAPH_ENDPOINT, account, graphCallRef)
            if (res.status && winner.complete(res)) {
                preferredEndpointByAccount[emailKey] = GRAPH_ENDPOINT
                oauthCallRef.get()?.cancel()
            }
            res
        }

        val watcher = launch {
            val oauthRes = oauthDeferred.await()
            val graphRes = graphDeferred.await()
            if (!winner.isCompleted) {
                winner.complete(combineFailureResponses(account, oauthRes, graphRes))
            }
        }

        try {
            winner.await()
        } finally {
            watcher.cancel()
            oauthCallRef.get()?.cancel()
            graphCallRef.get()?.cancel()
            if (oauthDeferred.isActive) oauthDeferred.cancel()
            if (graphDeferred.isActive) graphDeferred.cancel()
        }
    }

    private fun combineFailureResponses(
        account: MailboxAccount,
        oauthRes: MailReadResponse,
        graphRes: MailReadResponse
    ): MailReadResponse {
        val oauthErr = oauthRes.error.orEmpty()
        val graphErr = graphRes.error.orEmpty()
        val combined = when {
            oauthErr.isBlank() -> graphErr
            graphErr.isBlank() || oauthErr == graphErr -> oauthErr
            else -> "OAuth2: $oauthErr | Graph: $graphErr"
        }.ifBlank { "No matching ${account.serviceType} verification mail found yet." }
        return MailReadResponse(
            email = account.email,
            status = false,
            error = combined
        )
    }

    private fun fetchFromEndpoint(
        endpoint: String,
        account: MailboxAccount,
        callRef: AtomicReference<Call?>? = null
    ): MailReadResponse {
        val serviceType = account.serviceType.ifBlank { "facebook" }
        val payload = JSONObject().apply {
            put("email", account.email)
            put("refresh_token", account.refreshToken)
            put("client_id", account.clientId)
            put("type", serviceType)
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()

        val call = httpClient.newCall(request)
        callRef?.set(call)

        return try {
            call.execute().use { response ->
                val statusCode = response.code
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return MailReadResponse(
                        email = account.email,
                        status = false,
                        error = "Empty response (HTTP $statusCode)"
                    )
                }

                val json = JSONObject(body)
                val status = statusCode in 200..299 && json.optBoolean("status", false)
                val code = if (json.isNull("code")) null else json.optString("code", "").takeIf { it.isNotBlank() }
                val content = if (json.isNull("content")) null else json.optString("content", "").takeIf { it.isNotBlank() }
                val date = if (json.isNull("date")) null else json.optString("date", "").takeIf { it.isNotBlank() }
                val explicitError = if (json.isNull("error")) {
                    if (json.isNull("message")) null else json.optString("message", "").takeIf { it.isNotBlank() }
                } else {
                    json.optString("error", "").takeIf { it.isNotBlank() }
                }
                val resolvedError = if (status) {
                    null
                } else {
                    explicitError ?: content ?: "No matching $serviceType verification mail found yet."
                }

                MailReadResponse(
                    email = json.optString("email", account.email).ifBlank { account.email },
                    status = status,
                    code = code,
                    content = content,
                    date = date,
                    uid = null,
                    error = resolvedError
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            MailReadResponse(
                email = account.email,
                status = false,
                error = if (call.isCanceled()) {
                    "Cancelled"
                } else {
                    e.message ?: "Network error reaching dongvanfb API"
                }
            )
        }
    }
}
