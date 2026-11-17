package com.hotmailreader.app.parser

import com.hotmailreader.app.model.MailConnectionModes
import com.hotmailreader.app.model.MailboxAccount
import org.json.JSONObject

data class ParsedMailbox(
    val email: String,
    val password: String?,
    val refreshToken: String,
    val clientId: String,
    val clientSecret: String? = null
)

sealed class SingleParseResult {
    data class Success(val mailbox: ParsedMailbox) : SingleParseResult()
    data class Error(val message: String) : SingleParseResult()
}

data class BulkLineError(
    val line: Int,
    val error: String
)

data class BulkParseResult(
    val valid: List<ParsedMailbox>,
    val errors: List<BulkLineError>
)

data class Smail1sRawMessage(
    val uid: String,
    val from: String,
    val subject: String,
    val date: String,
    val message: String,
    val code: String
)

object MailboxParser {
    const val AUTO_POLL_INTERVAL_MS = 3_000L
    const val TWO_HOURS_MS = 2L * 60L * 60L * 1_000L

    private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    private val FB_CODE_REGEX = Regex("\\bFB-(\\d{4,8})\\b", RegexOption.IGNORE_CASE)
    private val DIGIT_CODE_REGEX = Regex("\\b(\\d{4,8})\\b")
    private val STYLE_BLOCK_REGEX = Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE)
    private val SCRIPT_BLOCK_REGEX = Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE)
    private val HTML_TAG_REGEX = Regex("<[^>]+>")
    private val WHITESPACE_REGEX = Regex("\\s+")

    fun isValidEmail(email: String): Boolean = EMAIL_REGEX.matches(email.trim())

    private fun looksLikeRefreshToken(value: String): Boolean {
        val v = value.trim()
        return v.length >= 40 ||
            v.startsWith("M.", ignoreCase = true) ||
            v.startsWith("0.A", ignoreCase = true) ||
            v.startsWith("1//") ||
            v.startsWith("DEMO_REFRESH_TOKEN")
    }

    fun validateFields(
        email: String,
        refreshToken: String,
        clientId: String,
        password: String? = null,
        clientSecret: String? = null,
        connectionMode: String = MailConnectionModes.OAUTH
    ): SingleParseResult {
        val cleanEmail = email.trim()
        val cleanToken = refreshToken.trim()
        val cleanClientId = clientId.trim()
        val cleanPassword = password?.trim()?.takeIf { it.isNotEmpty() }
        val cleanSecret = clientSecret?.trim()?.takeIf { it.isNotEmpty() }

        if (cleanEmail.isEmpty()) {
            return SingleParseResult.Error("Please enter a Hotmail or Outlook email address.")
        }
        if (!isValidEmail(cleanEmail)) {
            return SingleParseResult.Error("Email address format looks invalid.")
        }

        if (connectionMode == MailConnectionModes.ROUNDCUBE) {
            if (cleanPassword == null) {
                return SingleParseResult.Error("Password is required for Roundcube mode (email|password).")
            }
            return SingleParseResult.Success(
                ParsedMailbox(
                    email = cleanEmail,
                    password = cleanPassword,
                    refreshToken = cleanToken,
                    clientId = cleanClientId,
                    clientSecret = cleanSecret
                )
            )
        }

        if (cleanToken.isEmpty()) {
            return SingleParseResult.Error("Refresh token is required for OAuth2 / Graph mail reading.")
        }
        if (cleanClientId.isEmpty()) {
            return SingleParseResult.Error("Client ID is required for OAuth2 / Graph mail reading.")
        }

        return SingleParseResult.Success(
            ParsedMailbox(
                email = cleanEmail,
                password = cleanPassword,
                refreshToken = cleanToken,
                clientId = cleanClientId,
                clientSecret = cleanSecret
            )
        )
    }

    fun parseSingle(
        raw: String,
        connectionMode: String = MailConnectionModes.OAUTH
    ): SingleParseResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return SingleParseResult.Error(
                if (connectionMode == MailConnectionModes.ROUNDCUBE) {
                    "Paste credentials: email|password"
                } else {
                    "Paste credentials: email|password|refresh_token|client_id or email|refresh_token|client_id|client_secret"
                }
            )
        }

        if (trimmed.startsWith("{")) {
            return try {
                val obj = JSONObject(trimmed)
                validateFields(
                    email = obj.optString("email", ""),
                    refreshToken = obj.optString("refresh_token", ""),
                    clientId = obj.optString("client_id", ""),
                    password = obj.optString("password", ""),
                    clientSecret = obj.optString("client_secret", ""),
                    connectionMode = connectionMode
                )
            } catch (_: Exception) {
                SingleParseResult.Error("Invalid JSON format in Mail Box.")
            }
        }

        val firstLine = trimmed.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val parts = when {
            firstLine.contains("|") -> firstLine.split("|").map { it.trim() }
            firstLine.contains("\t") -> firstLine.split("\t").map { it.trim() }
            firstLine.contains(";") -> firstLine.split(";").map { it.trim() }
            firstLine.contains(":") -> firstLine.split(":").map { it.trim() }
            else -> emptyList()
        }

        if (connectionMode == MailConnectionModes.ROUNDCUBE && parts.size == 2) {
            return validateFields(
                email = parts[0],
                refreshToken = "",
                clientId = "",
                password = parts[1],
                connectionMode = connectionMode
            )
        }

        return when {
            parts.size == 4 -> {
                // Distinguish Graph format (email|refresh_token|client_id|client_secret)
                // from Combo format (email|password|refresh_token|client_id)
                if (looksLikeRefreshToken(parts[1]) && !looksLikeRefreshToken(parts[2])) {
                    validateFields(
                        email = parts[0],
                        refreshToken = parts[1],
                        clientId = parts[2],
                        password = null,
                        clientSecret = parts[3],
                        connectionMode = connectionMode
                    )
                } else {
                    validateFields(
                        email = parts[0],
                        refreshToken = parts[2],
                        clientId = parts[3],
                        password = parts[1],
                        clientSecret = null,
                        connectionMode = connectionMode
                    )
                }
            }
            parts.size == 3 -> validateFields(parts[0], parts[1], parts[2], null, null, connectionMode)
            parts.size > 4 -> {
                val email = parts.first()
                val clientId = parts.last()
                val refreshToken = parts[parts.size - 2]
                val password = parts.subList(1, parts.size - 2).joinToString("|")
                validateFields(email, refreshToken, clientId, password, null, connectionMode)
            }
            else -> SingleParseResult.Error(
                "Use format: email|password|refresh_token|client_id or email|refresh_token|client_id"
            )
        }
    }

    fun parseBulk(
        raw: String,
        connectionMode: String = MailConnectionModes.OAUTH
    ): BulkParseResult {
        val byEmail = LinkedHashMap<String, ParsedMailbox>()
        val errors = mutableListOf<BulkLineError>()

        raw.lines().forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            when (val res = parseSingle(trimmed, connectionMode)) {
                is SingleParseResult.Success -> byEmail[res.mailbox.email.lowercase()] = res.mailbox
                is SingleParseResult.Error -> errors.add(BulkLineError(index + 1, res.message))
            }
        }

        return BulkParseResult(
            valid = byEmail.values.toList(),
            errors = errors
        )
    }

    fun stripHtml(raw: String?): String {
        val text = raw.orEmpty().trim()
        if (text.isEmpty()) return ""
        if (!text.contains('<') && !text.contains('&')) {
            return WHITESPACE_REGEX.replace(text, " ").trim()
        }
        val withoutBlocks = SCRIPT_BLOCK_REGEX.replace(STYLE_BLOCK_REGEX.replace(text, " "), " ")
        val withoutTags = HTML_TAG_REGEX.replace(withoutBlocks, " ")
        val unescaped = withoutTags
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&#x27;", "'", ignoreCase = true)
        return WHITESPACE_REGEX.replace(unescaped, " ").trim()
    }

    fun matchesServiceType(
        from: String,
        subject: String,
        plainBody: String,
        serviceType: String
    ): Boolean {
        val normalized = serviceType.trim().lowercase()
        if (normalized.isEmpty() || normalized == "all") return true
        val haystack = "${from.lowercase()} ${subject.lowercase()} ${plainBody.lowercase()}"
        val keywords = when (normalized) {
            "facebook" -> listOf("facebook", "facebookmail", "fb-", "meta")
            "twitter" -> listOf("twitter", "x.com")
            "google" -> listOf("google", "gmail")
            else -> listOf(normalized)
        }
        return keywords.any { haystack.contains(it) }
    }

    fun pickBestSmail1sMessage(
        messages: List<Smail1sRawMessage>,
        serviceType: String
    ): Smail1sRawMessage? {
        if (messages.isEmpty()) return null
        val sorted = messages.sortedByDescending { it.date.trim() }
        val matching = sorted.filter { msg ->
            val plain = stripHtml(msg.message)
            matchesServiceType(msg.from, msg.subject, plain, serviceType)
        }
        if (matching.isEmpty()) return null
        return matching.firstOrNull { msg ->
            val plain = stripHtml(msg.message)
            extractOtpCode(msg.code, "${msg.subject} $plain").isNotEmpty()
        } ?: matching.first()
    }

    fun extractOtpCode(code: String?, content: String?): String {
        val cleanCode = code.orEmpty().trim()
        if (cleanCode.isNotEmpty() && cleanCode != "----" && !cleanCode.equals("null", ignoreCase = true)) {
            return cleanCode
        }
        val body = stripHtml(content)
        if (body.isEmpty()) return ""
        FB_CODE_REGEX.find(body)?.groupValues?.getOrNull(1)?.let { return it }
        return DIGIT_CODE_REGEX.find(body)?.groupValues?.getOrNull(1).orEmpty()
    }

    fun hasNewMailCode(
        prev: MailboxAccount,
        nextCode: String,
        nextContent: String?,
        nextDate: String?,
        nextUid: String? = null
    ): Boolean {
        if (nextCode.isBlank()) return false
        val cleanNextUid = nextUid.orEmpty().trim()
        val cleanPrevUid = prev.lastUid.orEmpty().trim()
        if (cleanNextUid.isNotEmpty() && cleanPrevUid.isNotEmpty() && cleanNextUid != cleanPrevUid) {
            return true
        }

        val prevCode = prev.lastCode.orEmpty().trim()
        if (prevCode.isEmpty()) return true
        if (nextCode != prevCode) return true

        val cleanNextDate = nextDate.orEmpty().trim()
        val cleanPrevDate = prev.lastDate.orEmpty().trim()
        if (cleanNextDate.isNotEmpty() && cleanPrevDate.isNotEmpty() && cleanNextDate != cleanPrevDate) {
            return true
        }

        val cleanNextContent = nextContent.orEmpty().trim()
        val cleanPrevContent = prev.lastContent.orEmpty().trim()
        if (cleanNextContent.isNotEmpty() && cleanPrevContent.isNotEmpty() && cleanNextContent != cleanPrevContent) {
            return true
        }
        return false
    }
}
