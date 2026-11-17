package com.hotmailreader.app.data

import android.content.Context
import com.hotmailreader.app.model.MailConnectionModes
import com.hotmailreader.app.model.MailboxAccount
import com.hotmailreader.app.parser.MailboxParser
import com.hotmailreader.app.parser.ParsedMailbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class MailPollUpdate(
    val id: String,
    val code: String = "",
    val content: String? = null,
    val date: String? = null,
    val uid: String? = null,
    val error: String? = null,
    val isNewCode: Boolean = false,
    val receivedSeq: Long = 0L
)

class MailboxRepository private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var persistJob: Job? = null

    private val _mailboxes = MutableStateFlow<List<MailboxAccount>>(emptyList())
    val mailboxes: StateFlow<List<MailboxAccount>> = _mailboxes.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _autoReadEnabled = MutableStateFlow(true)
    val autoReadEnabled: StateFlow<Boolean> = _autoReadEnabled.asStateFlow()

    private val _overlayRunning = MutableStateFlow(false)
    val overlayRunning: StateFlow<Boolean> = _overlayRunning.asStateFlow()

    init {
        val loaded = loadMailboxesFromPrefs()
        _mailboxes.value = loaded
        _activeId.value = prefs.getString(KEY_ACTIVE_ID, null) ?: loaded.firstOrNull()?.id
        _autoReadEnabled.value = prefs.getBoolean(KEY_AUTOREAD_ENABLED, true)
    }

    fun saveMailboxes(
        items: List<ParsedMailbox>,
        serviceType: String,
        connectionMode: String = MailConnectionModes.AUTO
    ) {
        if (items.isEmpty()) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val current = _mailboxes.value.toMutableList()
            val indexByEmail = HashMap<String, Int>(current.size * 2)
            current.forEachIndexed { idx, acc ->
                indexByEmail[acc.email.lowercase()] = idx
            }
            var newestId: String? = null

            for (item in items.reversed()) {
                val existingIdx = indexByEmail[item.email.lowercase()]
                if (existingIdx != null) {
                    val updated = current[existingIdx].copy(
                        email = item.email,
                        password = item.password,
                        refreshToken = item.refreshToken,
                        clientId = item.clientId,
                        clientSecret = item.clientSecret,
                        connectionMode = connectionMode,
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
                        clientSecret = item.clientSecret,
                        connectionMode = connectionMode,
                        serviceType = serviceType,
                        createdAt = now
                    )
                    current.add(0, created)
                    indexByEmail.clear()
                    current.forEachIndexed { idx, acc ->
                        indexByEmail[acc.email.lowercase()] = idx
                    }
                    newestId = created.id
                }
            }

            _mailboxes.value = current
            if (newestId != null) {
                _activeId.value = newestId
            }
            _autoReadEnabled.value = true
            schedulePersist(immediate = true)
        }
    }

    fun updateMailResult(
        id: String,
        code: String,
        content: String?,
        date: String?,
        uid: String? = null,
        isNewCode: Boolean,
        receivedSeq: Long
    ) {
        updateMailResultsBatch(
            listOf(
                MailPollUpdate(
                    id = id,
                    code = code,
                    content = content,
                    date = date,
                    uid = uid,
                    isNewCode = isNewCode,
                    receivedSeq = receivedSeq
                )
            )
        )
    }

    fun updateMailResultsBatch(updates: List<MailPollUpdate>) {
        if (updates.isEmpty()) return
        synchronized(lock) {
            val updateMap = updates.associateBy { it.id }
            var listChanged = false
            var newestActiveId: String? = null
            var newestSeq = Long.MIN_VALUE
            val now = System.currentTimeMillis()

            val nextList = _mailboxes.value.map { account ->
                val upd = updateMap[account.id] ?: return@map account
                if (upd.error != null) {
                    if (upd.error != account.lastError) {
                        listChanged = true
                        account.copy(
                            lastError = upd.error,
                            lastFetchedAt = now
                        )
                    } else {
                        account
                    }
                } else {
                    val nextCode = upd.code.ifEmpty { account.lastCode }
                    val nextContent = upd.content?.takeIf { it.isNotBlank() }
                        ?: if (upd.code.isNotEmpty()) "${upd.code} confirmation code" else account.lastContent
                    val nextDate = upd.date?.takeIf { it.isNotBlank() } ?: account.lastDate
                    val nextUid = upd.uid?.takeIf { it.isNotBlank() } ?: account.lastUid
                    val nextSeq = if (upd.isNewCode) upd.receivedSeq else account.lastCodeReceivedAt

                    if (upd.isNewCode && upd.code.isNotEmpty() && upd.receivedSeq >= newestSeq) {
                        newestSeq = upd.receivedSeq
                        newestActiveId = account.id
                    }

                    if (nextCode != account.lastCode ||
                        nextContent != account.lastContent ||
                        nextDate != account.lastDate ||
                        nextUid != account.lastUid ||
                        nextSeq != account.lastCodeReceivedAt ||
                        account.lastError != null
                    ) {
                        listChanged = true
                        account.copy(
                            lastCode = nextCode,
                            lastContent = nextContent,
                            lastDate = nextDate,
                            lastUid = nextUid,
                            lastError = null,
                            lastFetchedAt = now,
                            lastCodeReceivedAt = nextSeq
                        )
                    } else {
                        account
                    }
                }
            }

            var activeChanged = false
            if (newestActiveId != null && _activeId.value != newestActiveId) {
                _activeId.value = newestActiveId
                activeChanged = true
            }

            if (listChanged) {
                _mailboxes.value = nextList
            }

            if (listChanged || activeChanged) {
                schedulePersist(immediate = false)
            }
        }
    }

    fun selectActive(id: String) {
        synchronized(lock) {
            if (_mailboxes.value.any { it.id == id } && _activeId.value != id) {
                _activeId.value = id
                schedulePersist(immediate = false)
            }
        }
    }

    fun deleteMailbox(id: String) {
        synchronized(lock) {
            val next = _mailboxes.value.filterNot { it.id == id }
            _mailboxes.value = next
            if (_activeId.value == id) {
                _activeId.value = next.firstOrNull()?.id
            }
            schedulePersist(immediate = true)
        }
    }

    fun clearAll() {
        synchronized(lock) {
            _mailboxes.value = emptyList()
            _activeId.value = null
            schedulePersist(immediate = true)
        }
    }

    fun toggleAutoRead() {
        synchronized(lock) {
            _autoReadEnabled.value = !_autoReadEnabled.value
            schedulePersist(immediate = true)
        }
    }

    fun setOverlayRunning(running: Boolean) {
        _overlayRunning.value = running
    }

    fun isAutoReadActive(): Boolean {
        return _autoReadEnabled.value && _mailboxes.value.isNotEmpty()
    }

    fun getOverlayPosition(): Pair<Int, Int> {
        return prefs.getInt(KEY_OVERLAY_X, 24) to prefs.getInt(KEY_OVERLAY_Y, 160)
    }

    fun saveOverlayPosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_OVERLAY_X, x).putInt(KEY_OVERLAY_Y, y).apply()
    }

    private fun schedulePersist(immediate: Boolean) {
        val snapshotMailboxes = _mailboxes.value
        val snapshotActiveId = _activeId.value
        val snapshotEnabled = _autoReadEnabled.value

        persistJob?.cancel()
        persistJob = ioScope.launch {
            if (!immediate) {
                delay(350)
            }
            writeSnapshotToPrefs(
                mailboxes = snapshotMailboxes,
                activeId = snapshotActiveId,
                autoReadEnabled = snapshotEnabled
            )
        }
    }

    private fun writeSnapshotToPrefs(
        mailboxes: List<MailboxAccount>,
        activeId: String?,
        autoReadEnabled: Boolean
    ) {
        val arr = JSONArray()
        for (m in mailboxes) {
            val obj = JSONObject().apply {
                put("id", m.id)
                put("email", m.email)
                put("password", m.password ?: "")
                put("refreshToken", m.refreshToken)
                put("clientId", m.clientId)
                put("clientSecret", m.clientSecret ?: "")
                put("connectionMode", m.connectionMode)
                put("serviceType", m.serviceType)
                put("createdAt", m.createdAt)
                put("lastCode", m.lastCode ?: "")
                put("lastContent", m.lastContent ?: "")
                put("lastDate", m.lastDate ?: "")
                put("lastUid", m.lastUid ?: "")
                put("lastFetchedAt", m.lastFetchedAt ?: -1L)
                put("lastCodeReceivedAt", m.lastCodeReceivedAt ?: -1L)
            }
            arr.put(obj)
        }
        prefs.edit()
            .putString(KEY_MAILBOXES, arr.toString())
            .putString(KEY_ACTIVE_ID, activeId)
            .putBoolean(KEY_AUTOREAD_ENABLED, autoReadEnabled)
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
                            clientSecret = obj.optString("clientSecret", "").takeIf { it.isNotEmpty() },
                            connectionMode = obj.optString("connectionMode", MailConnectionModes.AUTO)
                                .ifBlank { MailConnectionModes.AUTO },
                            serviceType = obj.optString("serviceType", "facebook"),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                            lastCode = obj.optString("lastCode", "").takeIf { it.isNotEmpty() },
                            lastContent = obj.optString("lastContent", "").takeIf { it.isNotEmpty() },
                            lastDate = obj.optString("lastDate", "").takeIf { it.isNotEmpty() },
                            lastUid = obj.optString("lastUid", "").takeIf { it.isNotEmpty() },
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
