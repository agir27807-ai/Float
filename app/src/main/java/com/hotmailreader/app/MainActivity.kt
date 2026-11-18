package com.hotmailreader.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hotmailreader.app.data.MailPollUpdate
import com.hotmailreader.app.data.MailboxRepository
import com.hotmailreader.app.model.MailConnectionModes
import com.hotmailreader.app.model.MailServiceTypes
import com.hotmailreader.app.model.MailboxAccount
import com.hotmailreader.app.network.DongvanApiClient
import com.hotmailreader.app.parser.MailboxParser
import com.hotmailreader.app.parser.SingleParseResult
import com.hotmailreader.app.service.FloatingOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private lateinit var repository: MailboxRepository
    private var hasOverlayPermission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = MailboxRepository.getInstance(this)
        hasOverlayPermission = Settings.canDrawOverlays(this)

        setContent {
            MaterialTheme {
                HotmailReaderScreen(
                    repository = repository,
                    hasOverlayPermission = hasOverlayPermission,
                    onRequestOverlayPermission = { openOverlayPermissionSettings() },
                    onStartOverlay = { startFloatingOverlay() },
                    onStopOverlay = { stopFloatingOverlay() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasOverlayPermission = Settings.canDrawOverlays(this)
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun startFloatingOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlayPermissionSettings()
            return
        }
        val intent = Intent(this, FloatingOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun stopFloatingOverlay() {
        val intent = Intent(this, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_STOP
        }
        startService(intent)
    }
}

private fun countNonEmptyLines(text: String): Int {
    if (text.isEmpty()) return 0
    var count = 0
    var hasNonWhitespace = false
    for (i in 0 until text.length) {
        val c = text[i]
        if (c == '\n') {
            if (hasNonWhitespace) count++
            hasNonWhitespace = false
        } else if (!c.isWhitespace()) {
            hasNonWhitespace = true
        }
    }
    if (hasNonWhitespace) count++
    return count
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotmailReaderScreen(
    repository: MailboxRepository,
    hasOverlayPermission: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit
) {
    val context = LocalContext.current
    val mailboxes by repository.mailboxes.collectAsState()
    val activeId by repository.activeId.collectAsState()
    val autoReadEnabled by repository.autoReadEnabled.collectAsState()
    val overlayRunning by repository.overlayRunning.collectAsState()

    var statusBanner by remember { mutableStateOf<String?>(null) }
    var copiedEmail by remember { mutableStateOf<String?>(null) }
    val activeToastRef = remember { mutableStateOf<Toast?>(null) }

    LaunchedEffect(copiedEmail) {
        if (copiedEmail != null) {
            delay(1_400)
            copiedEmail = null
        }
    }

    // Continuous fast auto-read every 3s (wakes up instantly on save/resume)
    LaunchedEffect(overlayRunning, mailboxes.isNotEmpty(), autoReadEnabled) {
        if (overlayRunning || !repository.isAutoReadActive()) return@LaunchedEffect
        var seq = System.currentTimeMillis()
        while (isActive && !overlayRunning && repository.isAutoReadActive()) {
            val snapshot = repository.mailboxes.value
            if (snapshot.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    snapshot.chunked(16).forEach { batch ->
                        if (!isActive || !repository.isAutoReadActive()) return@forEach
                        val responseMap = DongvanApiClient.fetchMessagesBatch(batch)
                        val updates = batch.mapNotNull { account ->
                            val res = responseMap[account.id] ?: return@mapNotNull null
                            if (!res.status) {
                                val err = res.error ?: res.content
                                return@mapNotNull if (!err.isNullOrBlank()) {
                                    MailPollUpdate(id = account.id, error = err)
                                } else {
                                    null
                                }
                            }
                            val code = MailboxParser.extractOtpCode(res.code, res.content)
                            val isNew = MailboxParser.hasNewMailCode(
                                prev = account,
                                nextCode = code,
                                nextContent = res.content,
                                nextDate = res.date,
                                nextUid = res.uid
                            )
                            MailPollUpdate(
                                id = account.id,
                                code = code,
                                content = res.content,
                                date = res.date,
                                uid = res.uid,
                                isNewCode = isNew,
                                receivedSeq = ++seq
                            )
                        }
                        if (updates.isNotEmpty()) {
                            repository.updateMailResultsBatch(updates)
                        }
                    }
                }
            }
            withTimeoutOrNull(MailboxParser.AUTO_POLL_INTERVAL_MS) {
                repository.pollTrigger.first()
            }
        }
    }

    val copyToClipboard = remember(context) {
        { label: String, value: String ->
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText(label, value))
            activeToastRef.value?.cancel()
            activeToastRef.value = Toast.makeText(context, "Copied $value", Toast.LENGTH_SHORT).also {
                it.show()
            }
        }
    }

    val onCopyCode = remember(copyToClipboard) {
        { code: String ->
            copyToClipboard("OTP Code", code)
        }
    }

    val onCopyEmail = remember(copyToClipboard) {
        { email: String ->
            val pureEmail = MailboxParser.extractEmailAddress(email)
            if (pureEmail.isNotEmpty()) {
                copyToClipboard("Email Address", pureEmail)
                copiedEmail = pureEmail
            }
        }
    }

    val onSelectMailbox = remember(repository) {
        { id: String -> repository.selectActive(id) }
    }

    val onDeleteMailbox = remember(repository) {
        { id: String -> repository.deleteMailbox(id) }
    }

    val onToggleAutoRead = remember(repository) {
        { repository.toggleAutoRead() }
    }

    val onDeleteAll = remember(repository) {
        {
            repository.clearAll()
            statusBanner = "Deleted all saved mailboxes"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Hotmail OTP Reader",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    Button(
                        onClick = { if (overlayRunning) onStopOverlay() else onStartOverlay() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (overlayRunning) Color(0xFFDC2626) else Color(0xFF0F172A)
                        )
                    ) {
                        Text(if (overlayRunning) "Stop Floating" else "Start Floating")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF4F6F8))
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!hasOverlayPermission) {
                item(key = "permission_card", contentType = "permission") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Overlay Permission Required",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF92400E)
                            )
                            Text(
                                text = "Allow Display over other apps so the movable OTP bar can float above Facebook and other apps.",
                                fontSize = 12.sp,
                                color = Color(0xFF78350F),
                                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                            )
                            Button(onClick = onRequestOverlayPermission) {
                                Text("Grant Overlay Permission")
                            }
                        }
                    }
                }
            }

            item(key = "mailbox_form", contentType = "form") {
                MailboxInputCard(
                    repository = repository,
                    hasSavedMailboxes = mailboxes.isNotEmpty(),
                    externalStatus = statusBanner,
                    onClearExternalStatus = { statusBanner = null }
                )
            }

            if (mailboxes.isNotEmpty()) {
                item(key = "autoread_status", contentType = "status") {
                    AutoReadStatusCard(
                        mailboxCount = mailboxes.size,
                        autoReadEnabled = autoReadEnabled,
                        onToggleAutoRead = onToggleAutoRead,
                        onDeleteAll = onDeleteAll
                    )
                }
            }

            items(
                items = mailboxes,
                key = { it.id },
                contentType = { "mailbox" }
            ) { mailbox ->
                MailboxRowCard(
                    mailbox = mailbox,
                    isActive = mailbox.id == activeId,
                    isEmailCopied = copiedEmail == mailbox.email,
                    onSelectId = onSelectMailbox,
                    onCopyEmail = onCopyEmail,
                    onCopyCode = onCopyCode,
                    onDeleteId = onDeleteMailbox
                )
            }

            item(key = "bottom_spacer", contentType = "spacer") {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun MailboxInputCard(
    repository: MailboxRepository,
    hasSavedMailboxes: Boolean,
    externalStatus: String?,
    onClearExternalStatus: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Single, 1 = Bulk
    var singleInput by remember { mutableStateOf("") }
    var bulkInput by remember { mutableStateOf("") }
    var connectionMode by remember { mutableStateOf(MailConnectionModes.AUTO) }
    var serviceType by remember { mutableStateOf("facebook") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var localStatus by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val displayStatus = localStatus ?: externalStatus

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Mail Box", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(8.dp))

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0; errorMessage = null },
                    text = { Text("Single Mail") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1; errorMessage = null },
                    text = { Text("Bulk Mode") }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (selectedTab == 0) {
                OutlinedTextField(
                    value = singleInput,
                    onValueChange = { singleInput = it; errorMessage = null },
                    label = { Text("email|password|refresh_token|client_id") },
                    textStyle = TextStyle(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp, max = 120.dp)
                )
            } else {
                val lineCount = remember(bulkInput) { countNonEmptyLines(bulkInput) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$lineCount ${if (lineCount == 1) "account" else "accounts"} pasted",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )
                    if (bulkInput.isNotEmpty()) {
                        Text(
                            text = "Clear box",
                            fontSize = 12.sp,
                            color = Color(0xFF64748B),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable {
                                bulkInput = ""
                                errorMessage = null
                            }
                        )
                    }
                }
                OutlinedTextField(
                    value = bulkInput,
                    onValueChange = { bulkInput = it; errorMessage = null },
                    label = { Text("Bulk Mail Box (one account per line)") },
                    textStyle = TextStyle(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 132.dp, max = 160.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MailConnectionModes.ALL.forEach { modeOption ->
                    val selected = connectionMode == modeOption
                    val labelText = when (modeOption) {
                        MailConnectionModes.AUTO -> "Auto"
                        MailConnectionModes.GRAPH -> "Graph"
                        else -> "OAuth2"
                    }
                    OutlinedButton(
                        onClick = { connectionMode = modeOption; errorMessage = null },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(
                            1.dp,
                            if (selected) Color(0xFF2563EB) else Color(0xFFCBD5E1)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) Color(0xFFEFF6FF) else Color.White,
                            contentColor = if (selected) Color(0xFF1D4ED8) else Color(0xFF475569)
                        )
                    ) {
                        Text(labelText, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { dropdownExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Mail Filter Type: ${serviceType.replaceFirstChar { it.uppercase() }}",
                        modifier = Modifier.weight(1f)
                    )
                }
                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false }
                ) {
                    MailServiceTypes.ALL.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type) },
                            onClick = {
                                serviceType = type
                                dropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (selectedTab == 0) {
                Button(
                    enabled = !isSaving,
                    onClick = {
                        errorMessage = null
                        localStatus = null
                        onClearExternalStatus()
                        val modeSnapshot = connectionMode
                        scope.launch {
                            isSaving = true
                            val res = withContext(Dispatchers.Default) {
                                MailboxParser.parseSingle(singleInput)
                            }
                            when (res) {
                                is SingleParseResult.Error -> errorMessage = res.message
                                is SingleParseResult.Success -> {
                                    withContext(Dispatchers.IO) {
                                        repository.saveMailboxes(
                                            listOf(res.mailbox),
                                            serviceType,
                                            modeSnapshot
                                        )
                                    }
                                    singleInput = ""
                                    localStatus = "Saved ${res.mailbox.email} ($modeSnapshot)"
                                }
                            }
                            isSaving = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
                ) {
                    Text(if (isSaving) "Saving..." else "Save")
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        enabled = !isSaving,
                        onClick = {
                            errorMessage = null
                            localStatus = null
                            onClearExternalStatus()
                            val rawText = bulkInput
                            val modeSnapshot = connectionMode
                            scope.launch {
                                isSaving = true
                                val bulk = withContext(Dispatchers.Default) {
                                    MailboxParser.parseBulk(rawText)
                                }
                                if (bulk.valid.isEmpty()) {
                                    val first = bulk.errors.firstOrNull()
                                    errorMessage = first?.let { "Line ${it.line}: ${it.error}" }
                                        ?: "Paste one or more valid Hotmail lines."
                                } else {
                                    withContext(Dispatchers.IO) {
                                        repository.saveMailboxes(
                                            bulk.valid,
                                            serviceType,
                                            modeSnapshot
                                        )
                                    }
                                    bulkInput = ""
                                    localStatus = "Saved ${bulk.valid.size} mailboxes ($modeSnapshot)"
                                }
                                isSaving = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
                    ) {
                        Text(if (isSaving) "Saving..." else "Save All")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    OutlinedButton(
                        enabled = !isSaving && (hasSavedMailboxes || bulkInput.isNotEmpty()),
                        onClick = {
                            errorMessage = null
                            onClearExternalStatus()
                            bulkInput = ""
                            if (hasSavedMailboxes) {
                                repository.clearAll()
                                localStatus = "Deleted all saved mailboxes"
                            } else {
                                localStatus = "Cleared bulk box"
                            }
                        },
                        border = BorderStroke(
                            1.dp,
                            if (hasSavedMailboxes || bulkInput.isNotEmpty()) Color(0xFFDC2626) else Color(0xFFE2E8F0)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFDC2626)
                        )
                    ) {
                        Text("Delete All", maxLines = 1)
                    }
                }
            }

            errorMessage?.let { msg ->
                Text(
                    text = msg,
                    color = Color(0xFFDC2626),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            displayStatus?.let { msg ->
                Text(
                    text = msg,
                    color = Color(0xFF15803D),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun AutoReadStatusCard(
    mailboxCount: Int,
    autoReadEnabled: Boolean,
    onToggleAutoRead: () -> Unit,
    onDeleteAll: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                Text(
                    text = if (autoReadEnabled) "Auto-read 3s · On" else "Auto-read paused",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$mailboxCount saved",
                    fontSize = 12.sp,
                    color = Color(0xFF64748B),
                    maxLines = 1
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = onToggleAutoRead,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text(if (autoReadEnabled) "Pause" else "Resume", fontSize = 12.sp, maxLines = 1)
                }
                TextButton(
                    onClick = onDeleteAll,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Text("Delete All", color = Color(0xFFDC2626), fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun MailboxRowCard(
    mailbox: MailboxAccount,
    isActive: Boolean,
    isEmailCopied: Boolean,
    onSelectId: (String) -> Unit,
    onCopyEmail: (String) -> Unit,
    onCopyCode: (String) -> Unit,
    onDeleteId: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) Color(0xFFEFF6FF) else Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            1.dp,
            if (isActive) Color(0xFFBFDBFE) else Color(0xFFE2E8F0)
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectId(mailbox.id) }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onSelectId(mailbox.id)
                                onCopyEmail(mailbox.email)
                            }
                            .padding(vertical = 2.dp, horizontal = 2.dp)
                    ) {
                        Text(
                            text = mailbox.email,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = if (isEmailCopied) Color(0xFF15803D) else Color(0xFF0F172A),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = if (isEmailCopied) Icons.Rounded.Check else Icons.Outlined.ContentCopy,
                            contentDescription = "Copy email ${mailbox.email}",
                            tint = if (isEmailCopied) Color(0xFF15803D) else Color(0xFF64748B),
                            modifier = Modifier.size(14.dp)
                        )
                        if (isEmailCopied) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Copied",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF15803D)
                            )
                        }
                    }
                    Text(
                        text = "${mailbox.connectionMode} · ${mailbox.serviceType} · ${mailbox.lastDate ?: "Waiting for mail"}",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val otpCode = mailbox.lastCode
                if (!otpCode.isNullOrBlank()) {
                    Button(
                        onClick = { onCopyCode(otpCode) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF16A34A)
                        )
                    ) {
                        Text(
                            text = otpCode,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            val mailContent = mailbox.lastContent
            val mailError = mailbox.lastError
            if (!mailContent.isNullOrBlank()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = mailContent,
                    fontSize = 12.sp,
                    color = Color(0xFF334155),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (!mailError.isNullOrBlank()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = mailError,
                    fontSize = 12.sp,
                    color = Color(0xFFDC2626),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { onDeleteId(mailbox.id) }) {
                    Text("Delete", color = Color(0xFFDC2626), fontSize = 12.sp)
                }
            }
        }
    }
}
