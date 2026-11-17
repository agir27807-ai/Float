package com.hotmailreader.app.data

import android.content.Context
import com.hotmailreader.app.model.MailboxAccount
import com.hotmailreader.app.parser.MailboxParser
import com.hotmailreader.app.parser.ParsedMailbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class MailboxRepository private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _mailboxes = MutableStateFlow<List<MailboxAccount>>(emptyList())
    val mailboxes: StateFlow<List<MailboxAccount>> = _mailboxes.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _autoReadStartedAt = MutableStateFlow<Long?>(null)
    val autoReadStartedAt: StateFlow<Long?> = _autoReadStartedAt.asStateFlow()

    private val _autoReadEnabled = MutableStateFlow(true)
    val autoReadEnabled: StateFlow<Boolean> = _autoReadEnabled.asStateFlow()

    private val _overlayRunning = MutableStateFlow(false)
    val overlayRunning: StateFlow<Boolean> = _overlayRunning.asStateFlow()

    init {
        val loaded = loadMailboxesFromPrefs()
        _mailboxes.value = loaded
        _activeId.value = prefs.getString(KEY_ACTIVE_ID, null) ?: loaded.firstOrNull()?.id
        val savedStart = prefs.getLong(KEY_AUTOREAD_START, -1L)
        _autoReadStartedAt.value = if (savedStart > 0L) savedStart else if (loaded.isNotEmpty()) System.currentTimeMillis() else null
        _autoReadEnabled.value = prefs.getBoolean(KEY_AUTOREAD_ENABLED, true)
    }

    @Synchronized
    fun saveMailboxes(items: List<ParsedMailbox>, serviceType: String) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val current = _mailboxes.value.toMutableList()
        var newestId: String? = null

        for (item in items.reversed()) {
            val existingIdx = current.indexOfFirst { it.email.equals(item.email, ignoreCase = true) }
            if (existingIdx >= 0) {
                val updated = current[existingIdx].copy(
                    email = item.email,
                    password = item.password,
                    refreshToken = item.refreshToken,
                    clientId = item.clientId,
                    serviceType = serviceType
                )
                current[existingIdx] = updated
                newestId = updated.id
            } else {
                val created = MailboxAccount(
                    id = UUID.randomUUID().toString(),
                    email = item.email,
                    password = item.password,
                    refreshToken = item.refreshToken,
                    clientId = item.clientId,
                    serviceType = serviceType,
                    createdAt = now
                )
                current.add(0, created)
                newestId = created.id
            }
        }

        _mailboxes.value = current
        if (newestId != null) {
            _activeId.value = newestId
        }
        _autoReadStartedAt.value = now
        _autoReadEnabled.value = true
        persistAll()
    }

    @Synchronized
    fun updateMailResult(
        id: String,
        code: String,
        content: String?,
        date: String?,
        isNewCode: Boolean,
        receivedSeq: Long
    ) {
        val current = _mailboxes.value.map { account ->
            if (account.id == id) {
                account.copy(
                    lastCode = code.ifEmpty { account.lastCode },
                    lastContent = content?.takeIf { it.isNotBlank() }
                        ?: if (code.isNotEmpty()) "$code confirmation code" else account.lastContent,
                    lastDate = date?.takeIf { it.isNotBlank() } ?: account.lastDate,
                    lastFetchedAt = System.currentTimeMillis(),
                    lastCodeReceivedAt = if (isNewCode) receivedSeq else account.lastCodeReceivedAt
                )
            } else {
                account
            }
        }
        _mailboxes.value = current
        if (isNewCode && code.isNotEmpty()) {
            _activeId.value = id
        }
        persistAll()
    }

    @Synchronized
    fun selectActive(id: String) {
        if (_mailboxes.value.any { it.id == id }) {
            _activeId.value = id
            persistAll()
        }
    }

    @Synchronized
    fun deleteMailbox(id: String) {
        val next = _mailboxes.value.filterNot { it.id == id }
        _mailboxes.value = next
        if (_activeId.value == id) {
            _activeId.value = next.firstOrNull()?.id
        }
        persistAll()
    }

    @Synchronized
    fun clearAll() {
        _mailboxes.value = emptyList()
        _activeId.value = null
        _autoReadStartedAt.value = null
        persistAll()
    }

    @Synchronized
    fun toggleAutoRead() {
        _autoReadEnabled.value = !_autoReadEnabled.value
        persistAll()
    }

    @Synchronized
    fun restartAutoRead() {
        _autoReadStartedAt.value = System.currentTimeMillis()
        _autoReadEnabled.value = true
        persistAll()
    }

    fun setOverlayRunning(running: Boolean) {
        _overlayRunning.value = running
    }

    fun isAutoReadActive(now: Long = System.currentTimeMillis()): Boolean {
        val start = _autoReadStartedAt.value ?: return false
        return _autoReadEnabled.value &&
            _mailboxes.value.isNotEmpty() &&
            (now - start) < MailboxParser.TWO_HOURS_MS
    }

    fun getOverlayPosition(): Pair<Int, Int> {
        return prefs.getInt(KEY_OVERLAY_X, 24) to prefs.getInt(KEY_OVERLAY_Y, 160)
    }

    fun saveOverlayPosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_OVERLAY_X, x).putInt(KEY_OVERLAY_Y, y).apply()
    }

    private fun persistAll() {
        val arr = JSONArray()
        for (m in _mailboxes.value) {
            val obj = JSONObject().apply {
                put("id", m.id)
                put("email", m.email)
                put("password", m.password ?: "")
                put("refreshToken", m.refreshToken)
                put("clientId", m.clientId)
                put("serviceType", m.serviceType)
                put("createdAt", m.createdAt)
                put("lastCode", m.lastCode ?: "")
                put("lastContent", m.lastContent ?: "")
                put("lastDate", m.lastDate ?: "")
                put("lastFetchedAt", m.lastFetchedAt ?: -1L)
                put("lastCodeReceivedAt", m.lastCodeReceivedAt ?: -1L)
            }
            arr.put(obj)
        }
        prefs.edit()
            .putString(KEY_MAILBOXES, arr.toString())
            .putString(KEY_ACTIVE_ID, _activeId.value)
            .putLong(KEY_AUTOREAD_START, _autoReadStartedAt.value ?: -1L)
            .putBoolean(KEY_AUTOREAD_ENABLED, _autoReadEnabled.value)
            .apply()
    }

    private fun loadMailboxesFromPrefs(): List<MailboxAccount> {
        val raw = prefs.getString(KEY_MAILBOXES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val fetched = obj.optLong("lastFetchedAt", -1L)
                    val received = obj.optLong("lastCodeReceivedAt", -1L)
                    add(
                        MailboxAccount(
                            id = obj.getString("id"),
                            email = obj.getString("email"),
                            password = obj.optString("password", "").takeIf { it.isNotEmpty() },
                            refreshToken = obj.getString("refreshToken"),
                            clientId = obj.getString("clientId"),
                            serviceType = obj.optString("serviceType", "facebook"),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                            lastCode = obj.optString("lastCode", "").takeIf { it.isNotEmpty() },
                            lastContent = obj.optString("lastContent", "").takeIf { it.isNotEmpty() },
                            lastDate = obj.optString("lastDate", "").takeIf { it.isNotEmpty() },
                            lastFetchedAt = if (fetched > 0) fetched else null,
                            lastCodeReceivedAt = if (received > 0) received else null
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val PREFS_NAME = "hotmail_floating_reader_prefs"
        private const val KEY_MAILBOXES = "mailboxes_json"
        private const val KEY_ACTIVE_ID = "active_id"
        private const val KEY_AUTOREAD_START = "autoread_start"
        private const val KEY_AUTOREAD_ENABLED = "autoread_enabled"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"

        @Volatile
        private var instance: MailboxRepository? = null

        fun getInstance(context: Context): MailboxRepository {
            return instance ?: synchronized(this) {
                instance ?: MailboxRepository(context).also { instance = it }
            }
        }
    }
}
