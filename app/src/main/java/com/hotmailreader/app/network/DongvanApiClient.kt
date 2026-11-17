package com.hotmailreader.app.network

import com.hotmailreader.app.model.MailReadResponse
import com.hotmailreader.app.model.MailboxAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object DongvanApiClient {
    private const val ENDPOINT = "https://tools.dongvanfb.net/api/get_code_oauth2"

    suspend fun fetchCode(account: MailboxAccount): MailReadResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("email", account.email)
            put("refresh_token", account.refreshToken)
            put("client_id", account.clientId)
            put("type", account.serviceType)
        }

        val url = URL(ENDPOINT)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        try {
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
            MailReadResponse(
                email = json.optString("email", account.email),
                status = json.optBoolean("status", false),
                code = json.optString("code", "").takeIf { it.isNotEmpty() },
                content = json.optString("content", "").takeIf { it.isNotEmpty() },
                date = json.optString("date", "").takeIf { it.isNotEmpty() },
                error = json.optString("error", "").takeIf { it.isNotEmpty() }
                    ?: json.optString("message", "").takeIf { it.isNotEmpty() }
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
