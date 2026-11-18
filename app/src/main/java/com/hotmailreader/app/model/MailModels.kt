package com.hotmailreader.app.model

import androidx.compose.runtime.Immutable

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
    const val AUTO = "auto"
    const val OAUTH = "oauth"
    const val GRAPH = "graph"

    val ALL = listOf(AUTO, OAUTH, GRAPH)
}

@Immutable
data class MailboxAccount(
    val id: String,
    val email: String,
    val password: String? = null,
    val refreshToken: String,
    val clientId: String,
    val clientSecret: String? = null,
    val connectionMode: String = MailConnectionModes.AUTO,
    val serviceType: String = "facebook",
    val createdAt: Long = System.currentTimeMillis(),
    val lastCode: String? = null,
    val lastContent: String? = null,
    val lastDate: String? = null,
    val lastUid: String? = null,
    val lastError: String? = null,
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
