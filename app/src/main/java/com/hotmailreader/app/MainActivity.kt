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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hotmailreader.app.data.MailboxRepository
import com.hotmailreader.app.model.MailServiceTypes
import com.hotmailreader.app.model.MailboxAccount
import com.hotmailreader.app.parser.MailboxParser
import com.hotmailreader.app.parser.SingleParseResult
import com.hotmailreader.app.service.FloatingOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val scope = rememberCoroutineScope()
    val mailboxes by repository.mailboxes.collectAsState()
    val activeId by repository.activeId.collectAsState()
    val autoReadStartedAt by repository.autoReadStartedAt.collectAsState()
    val autoReadEnabled by repository.autoReadEnabled.collectAsState()
    val overlayRunning by repository.overlayRunning.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Single, 1 = Bulk
    var singleInput by remember { mutableStateOf("") }
    var bulkInput by remember { mutableStateOf("") }
    var serviceType by remember { mutableStateOf("facebook") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val copyCodeToClipboard = remember(context) {
        { code: String ->
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("OTP Code", code))
            Toast.makeText(context, "Copied $code", Toast.LENGTH_SHORT).show()
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
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasOverlayPermission) {
                item(key = "permission_card") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
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

            item(key = "mailbox_form") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
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
                                    statusMessage = null
                                    scope.launch {
                                        isSaving = true
                                        val res = withContext(Dispatchers.Default) {
                                            MailboxParser.parseSingle(singleInput)
                                        }
                                        when (res) {
                                            is SingleParseResult.Error -> errorMessage = res.message
                                            is SingleParseResult.Success -> {
                                                withContext(Dispatchers.IO) {
                                                    repository.saveMailboxes(listOf(res.mailbox), serviceType)
                                                }
                                                singleInput = ""
                                                statusMessage = "Saved ${res.mailbox.email}"
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
                                        statusMessage = null
                                        val rawText = bulkInput
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
                                                    repository.saveMailboxes(bulk.valid, serviceType)
                                                }
                                                bulkInput = ""
                                                statusMessage = "Saved ${bulk.valid.size} mailboxes"
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
                                    enabled = !isSaving && (mailboxes.isNotEmpty() || bulkInput.isNotEmpty()),
                                    onClick = {
                                        errorMessage = null
                                        val hadSaved = mailboxes.isNotEmpty()
                                        bulkInput = ""
                                        if (hadSaved) {
                                            repository.clearAll()
                                            statusMessage = "Deleted all saved mailboxes"
                                        } else {
                                            statusMessage = "Cleared bulk box"
                                        }
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (mailboxes.isNotEmpty() || bulkInput.isNotEmpty()) Color(0xFFDC2626) else Color(0xFFE2E8F0)
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
                        statusMessage?.let { msg ->
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

            if (mailboxes.isNotEmpty()) {
                item(key = "autoread_status") {
                    AutoReadStatusCard(
                        mailboxCount = mailboxes.size,
                        autoReadStartedAt = autoReadStartedAt,
                        autoReadEnabled = autoReadEnabled,
                        onToggleAutoRead = { repository.toggleAutoRead() },
                        onRestartAutoRead = { repository.restartAutoRead() },
                        onDeleteAll = {
                            repository.clearAll()
                            statusMessage = "Deleted all saved mailboxes"
                        }
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
                    onSelect = { repository.selectActive(mailbox.id) },
                    onCopyCode = copyCodeToClipboard,
                    onDelete = { repository.deleteMailbox(mailbox.id) }
                )
            }

            item(key = "bottom_spacer") { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AutoReadStatusCard(
    mailboxCount: Int,
    autoReadStartedAt: Long?,
    autoReadEnabled: Boolean,
    onToggleAutoRead: () -> Unit,
    onRestartAutoRead: () -> Unit,
    onDeleteAll: () -> Unit
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(autoReadStartedAt, autoReadEnabled) {
        if (autoReadStartedAt == null || !autoReadEnabled) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val isExpired = autoReadStartedAt != null &&
        (nowMs - autoReadStartedAt) >= MailboxParser.TWO_HOURS_MS
    val remainingLabel = remember(autoReadStartedAt, nowMs) {
        val start = autoReadStartedAt ?: return@remember "0m 00s"
        val rem = (MailboxParser.TWO_HOURS_MS - (nowMs - start).coerceAtLeast(0L)).coerceAtLeast(0L)
        val secs = rem / 1000L
        val h = secs / 3600L
        val m = (secs % 3600L) / 60L
        val s = secs % 60L
        if (h > 0) "${h}h ${m.toString().padStart(2, '0')}m" else "${m}m ${s.toString().padStart(2, '0')}s"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        isExpired -> "2h auto-read completed"
                        autoReadEnabled -> "Auto-read every 3s · $remainingLabel left"
                        else -> "Auto-read paused · $remainingLabel left"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$mailboxCount saved accounts",
                    fontSize = 12.sp,
                    color = Color(0xFF64748B)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        if (isExpired) onRestartAutoRead() else onToggleAutoRead()
                    }
                ) {
                    Text(if (isExpired) "Restart 2h" else if (autoReadEnabled) "Pause" else "Resume")
                }
                TextButton(onClick = onDeleteAll) {
                    Text("Delete All", color = Color(0xFFDC2626))
                }
            }
        }
    }
}

@Composable
private fun MailboxRowCard(
    mailbox: MailboxAccount,
    isActive: Boolean,
    onSelect: () -> Unit,
    onCopyCode: (String) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) Color(0xFFEFF6FF) else Color.White
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = mailbox.email,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${mailbox.serviceType} · ${mailbox.lastDate ?: "Waiting for mail"}",
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
            if (!mailContent.isNullOrBlank()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = mailContent,
                    fontSize = 12.sp,
                    color = Color(0xFF334155),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = Color(0xFFDC2626), fontSize = 12.sp)
                }
            }
        }
    }
}
