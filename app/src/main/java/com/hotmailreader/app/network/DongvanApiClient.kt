package com.hotmailreader.app.network

import com.hotmailreader.app.model.MailConnectionModes
import com.hotmailreader.app.model.MailReadResponse
import com.hotmailreader.app.model.MailboxAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object DongvanApiClient {
    private const val OAUTH_ENDPOINT = "https://tools.dongvanfb.net/api/get_code_oauth2"
    private const val GRAPH_ENDPOINT = "https://tools.dongvanfb.net/api/graph_code"

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

    fun fetchCode(account: MailboxAccount): MailReadResponse {
        return when (account.connectionMode) {
            MailConnectionModes.GRAPH -> fetchFromEndpoint(GRAPH_ENDPOINT, account)
            MailConnectionModes.OAUTH -> fetchFromEndpoint(OAUTH_ENDPOINT, account)
            else -> {
                val oauthRes = fetchFromEndpoint(OAUTH_ENDPOINT, account)
                if (oauthRes.status) return oauthRes
                val graphRes = fetchFromEndpoint(GRAPH_ENDPOINT, account)
                if (graphRes.status) return graphRes
                val oauthErr = oauthRes.error.orEmpty()
                val graphErr = graphRes.error.orEmpty()
                val combined = when {
                    oauthErr.isBlank() -> graphErr
                    graphErr.isBlank() || oauthErr == graphErr -> oauthErr
                    else -> "OAuth2: $oauthErr | Graph: $graphErr"
                }.ifBlank { "No matching ${account.serviceType} verification mail found yet." }
                MailReadResponse(
                    email = account.email,
                    status = false,
                    error = combined
                )
            }
        }
    }

    private fun fetchFromEndpoint(endpoint: String, account: MailboxAccount): MailReadResponse {
        val serviceType = account.serviceType.ifBlank { "facebook" }
        val payload = JSONObject().apply {
            put("email", account.email)
            put("refresh_token", account.refreshToken)
            put("client_id", account.clientId)
            put("type", serviceType)
        }

        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val statusCode = conn.responseCode
            val stream = if (statusCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
            }.orEmpty()

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
        } catch (e: Exception) {
            MailReadResponse(
                email = account.email,
                status = false,
                error = e.message ?: "Network error reaching dongvanfb API"
            )
        } finally {
            conn.disconnect()
        }
    }
}
