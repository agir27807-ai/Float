package com.hotmailreader.app.network

import com.hotmailreader.app.model.MailConnectionModes
import com.hotmailreader.app.model.MailReadResponse
import com.hotmailreader.app.model.MailboxAccount
import com.hotmailreader.app.parser.MailboxParser
import com.hotmailreader.app.parser.Smail1sRawMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object Smail1sApiClient {
    private const val ENDPOINT = "https://smail1s.com/get_messages"

    private fun formatAccountLine(account: MailboxAccount): String {
        return when (account.connectionMode) {
            MailConnectionModes.ROUNDCUBE -> "${account.email}|${account.password.orEmpty()}"
            MailConnectionModes.GRAPH -> {
                val secret = account.clientSecret.orEmpty().trim()
                if (secret.isNotEmpty()) {
                    "${account.email}|${account.refreshToken}|${account.clientId}|$secret"
                } else {
                    "${account.email}|${account.refreshToken}|${account.clientId}"
                }
            }
            else -> "${account.email}|${account.refreshToken}|${account.clientId}"
        }
    }

    suspend fun fetchMessagesBatch(accounts: List<MailboxAccount>): Map<String, MailReadResponse> =
        withContext(Dispatchers.IO) {
            if (accounts.isEmpty()) return@withContext emptyMap()

            val merged = HashMap<String, MailReadResponse>(accounts.size * 2)
            val groups = accounts.groupBy {
                it.connectionMode.ifBlank { MailConnectionModes.OAUTH }
            }

            for ((mode, groupAccounts) in groups) {
                merged.putAll(fetchGroupForMode(mode, groupAccounts))
            }
            merged
        }

    private fun fetchGroupForMode(
        mode: String,
        accounts: List<MailboxAccount>
    ): Map<String, MailReadResponse> {
        val byEmail = accounts.associateBy { it.email.lowercase() }
        val dataLines = accounts.joinToString("\n") { formatAccountLine(it) }

        val payload = JSONObject().apply {
            put("mode", mode)
            put("data", dataLines)
        }

        val url = URL(ENDPOINT)
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

            val root = JSONObject(body)
            val dataArr = root.optJSONArray("data") ?: JSONArray()
            val results = HashMap<String, MailReadResponse>(accounts.size * 2)

            for (i in 0 until dataArr.length()) {
                val item = dataArr.optJSONObject(i) ?: continue
                val rawEmail = if (item.isNull("email")) "" else item.optString("email", "")
                val errorText = if (item.isNull("error")) {
                    null
                } else {
                    item.optString("error", "").takeIf { it.isNotBlank() }
                }

                val matchedAccount = byEmail[rawEmail.lowercase()]
                    ?: if (accounts.size == 1) accounts.first() else null

                if (matchedAccount == null) continue

                if (errorText != null) {
                    results[matchedAccount.id] = MailReadResponse(
                        email = matchedAccount.email,
                        status = false,
                        error = errorText
                    )
                    continue
                }

                val msgArr = item.optJSONArray("messages") ?: JSONArray()
                val rawMessages = buildList(msgArr.length()) {
                    for (j in 0 until msgArr.length()) {
                        val mObj = msgArr.optJSONObject(j) ?: continue
                        add(
                            Smail1sRawMessage(
                                uid = if (mObj.isNull("uid")) "" else mObj.optString("uid", ""),
                                from = if (mObj.isNull("from")) "" else mObj.optString("from", ""),
                                subject = if (mObj.isNull("subject")) "" else mObj.optString("subject", ""),
                                date = if (mObj.isNull("date")) "" else mObj.optString("date", ""),
                                message = if (mObj.isNull("message")) "" else mObj.optString("message", ""),
                                code = if (mObj.isNull("code")) "" else mObj.optString("code", "")
                            )
                        )
                    }
                }

                val best = MailboxParser.pickBestSmail1sMessage(rawMessages, matchedAccount.serviceType)
                if (best == null) {
                    results[matchedAccount.id] = MailReadResponse(
                        email = matchedAccount.email,
                        status = false,
                        error = "No matching ${matchedAccount.serviceType} mail found yet."
                    )
                } else {
                    val plainBody = MailboxParser.stripHtml(best.message)
                    val displayContent = when {
                        plainBody.isNotEmpty() -> plainBody
                        best.subject.isNotEmpty() -> best.subject
                        else -> ""
                    }
                    val extractedCode = MailboxParser.extractOtpCode(
                        best.code,
                        "${best.subject} $plainBody"
                    )
                    results[matchedAccount.id] = MailReadResponse(
                        email = matchedAccount.email,
                        status = true,
                        code = extractedCode.takeIf { it.isNotEmpty() },
                        content = displayContent.takeIf { it.isNotEmpty() },
                        date = best.date.takeIf { it.isNotBlank() },
                        uid = best.uid.takeIf { it.isNotBlank() }
                    )
                }
            }

            results
        } catch (e: Exception) {
            val msg = e.message ?: "Network error reaching smail1s API"
            accounts.associate { account ->
                account.id to MailReadResponse(
                    email = account.email,
                    status = false,
                    error = msg
                )
            }
        } finally {
            conn.disconnect()
        }
    }
}
