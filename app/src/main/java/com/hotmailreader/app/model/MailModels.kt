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

object MailConnectionModes {
    const val OAUTH = "oauth"
    const val GRAPH = "graph"
    const val ROUNDCUBE = "roundcube"

    val ALL = listOf(OAUTH, GRAPH, ROUNDCUBE)
}

data class MailboxAccount(
    val id: String,
    val email: String,
    val password: String? = null,
    val refreshToken: String,
    val clientId: String,
    val clientSecret: String? = null,
    val connectionMode: String = MailConnectionModes.OAUTH,
    val serviceType: String = "facebook",
    val createdAt: Long = System.currentTimeMillis(),
    val lastCode: String? = null,
    val lastContent: String? = null,
    val lastDate: String? = null,
    val lastUid: String? = null,
    val lastFetchedAt: Long? = null,
    val lastCodeReceivedAt: Long? = null
)

data class MailReadResponse(
    val email: String,
    val status: Boolean,
    val code: String? = null,
    val content: String? = null,
    val date: String? = null,
    val uid: String? = null,
    val error: String? = null
)
