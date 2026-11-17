package com.hotmailreader.app.parser

import com.hotmailreader.app.model.MailboxAccount
import org.json.JSONObject

data class ParsedMailbox(
    val email: String,
    val password: String?,
    val refreshToken: String,
    val clientId: String
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

object MailboxParser {
    const val AUTO_POLL_INTERVAL_MS = 3_000L
    const val TWO_HOURS_MS = 2L * 60L * 60L * 1_000L

    private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    private val FB_CODE_REGEX = Regex("\\bFB-(\\d{4,8})\\b", RegexOption.IGNORE_CASE)
    private val DIGIT_CODE_REGEX = Regex("\\b(\\d{4,8})\\b")

    fun isValidEmail(email: String): Boolean = EMAIL_REGEX.matches(email.trim())

    fun validateFields(
        email: String,
        refreshToken: String,
        clientId: String,
        password: String? = null
    ): SingleParseResult {
        val cleanEmail = email.trim()
        val cleanToken = refreshToken.trim()
        val cleanClientId = clientId.trim()
        val cleanPassword = password?.trim()?.takeIf { it.isNotEmpty() }

        if (cleanEmail.isEmpty()) {
            return SingleParseResult.Error("Please enter a Hotmail or Outlook email address.")
        }
        if (!isValidEmail(cleanEmail)) {
            return SingleParseResult.Error("Email address format looks invalid.")
        }
        if (cleanToken.isEmpty()) {
            return SingleParseResult.Error("Refresh token is required for OAuth2 mail reading.")
        }
        if (cleanClientId.isEmpty()) {
            return SingleParseResult.Error("Client ID is required for OAuth2 mail reading.")
        }

        return SingleParseResult.Success(
            ParsedMailbox(
                email = cleanEmail,
                password = cleanPassword,
                refreshToken = cleanToken,
                clientId = cleanClientId
            )
        )
    }

    fun parseSingle(raw: String): SingleParseResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return SingleParseResult.Error(
                "Paste credentials: email|password|refresh_token|client_id or email|refresh_token|client_id"
            )
        }

        if (trimmed.startsWith("{")) {
            return try {
                val obj = JSONObject(trimmed)
                validateFields(
                    email = obj.optString("email", ""),
                    refreshToken = obj.optString("refresh_token", ""),
                    clientId = obj.optString("client_id", ""),
                    password = obj.optString("password", "")
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

        return when {
            parts.size == 4 -> validateFields(parts[0], parts[2], parts[3], parts[1])
            parts.size == 3 -> validateFields(parts[0], parts[1], parts[2], null)
            parts.size > 4 -> {
                val email = parts.first()
                val clientId = parts.last()
                val refreshToken = parts[parts.size - 2]
                val password = parts.subList(1, parts.size - 2).joinToString("|")
                validateFields(email, refreshToken, clientId, password)
            }
            else -> SingleParseResult.Error(
                "Use format: email|password|refresh_token|client_id or email|refresh_token|client_id"
            )
        }
    }

    fun parseBulk(raw: String): BulkParseResult {
        val byEmail = LinkedHashMap<String, ParsedMailbox>()
        val errors = mutableListOf<BulkLineError>()

        raw.lines().forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            when (val res = parseSingle(trimmed)) {
                is SingleParseResult.Success -> byEmail[res.mailbox.email.lowercase()] = res.mailbox
                is SingleParseResult.Error -> errors.add(BulkLineError(index + 1, res.message))
            }
        }

        return BulkParseResult(
            valid = byEmail.values.toList(),
            errors = errors
        )
    }

    fun extractOtpCode(code: String?, content: String?): String {
        val cleanCode = code.orEmpty().trim()
        if (cleanCode.isNotEmpty() && cleanCode != "----") {
            return cleanCode
        }
        val body = content.orEmpty().trim()
        if (body.isEmpty()) return ""
        FB_CODE_REGEX.find(body)?.groupValues?.getOrNull(1)?.let { return it }
        return DIGIT_CODE_REGEX.find(body)?.groupValues?.getOrNull(1).orEmpty()
    }

    fun hasNewMailCode(
        prev: MailboxAccount,
        nextCode: String,
        nextContent: String?,
        nextDate: String?
    ): Boolean {
        if (nextCode.isBlank()) return false
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
