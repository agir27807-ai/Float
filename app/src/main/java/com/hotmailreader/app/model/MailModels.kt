package com.hotmailreader.app.model

object MailServiceTypes {
    val ALL = listOf(
        "facebook",
        "all",
        "instagram",
        "twitter",
        "apple",
        "tiktok",
        "amazon",
        "lazada",
        "kakaotalk",
        "google",
        "shopee",
        "telegram",
        "wechat"
    )
}

data class MailboxAccount(
    val id: String,
    val email: String,
    val password: String? = null,
    val refreshToken: String,
    val clientId: String,
    val serviceType: String = "facebook",
    val createdAt: Long = System.currentTimeMillis(),
    val lastCode: String? = null,
    val lastContent: String? = null,
    val lastDate: String? = null,
    val lastFetchedAt: Long? = null,
    val lastCodeReceivedAt: Long? = null
)

data class MailReadResponse(
    val email: String,
    val status: Boolean,
    val code: String? = null,
    val content: String? = null,
    val date: String? = null,
    val error: String? = null
)
