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
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var repository: MailboxRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = MailboxRepository.getInstance(this)

        setContent {
            MaterialTheme {
                HotmailReaderScreen(
                    repository = repository,
                    onRequestOverlayPermission = { openOverlayPermissionSettings() },
                    onStartOverlay = { startFloatingOverlay() },
                    onStopOverlay = { stopFloatingOverlay() }
                )
            }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotmailReaderScreen(
    repository: MailboxRepository,
    onRequestOverlayPermission: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit
) {
    val context = LocalContext.current
    val mailboxes by repository.mailboxes.collectAsState()
    val activeId by repository.activeId.collectAsState()
    val autoReadStartedAt by repository.autoReadStartedAt.collectAsState()
    val autoReadEnabled by repository.autoReadEnabled.collectAsState()
    val overlayRunning by repository.overlayRunning.collectAsState()

    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Single, 1 = Bulk
    var singleInput by remember { mutableStateOf("") }
    var bulkInput by remember { mutableStateOf("") }
    var serviceType by remember { mutableStateOf("facebook") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            hasOverlayPermission = Settings.canDrawOverlays(context)
            delay(1_000)
        }
    }

    val isExpired = mailboxes.isNotEmpty() &&
        autoReadStartedAt != null &&
        (nowMs - (autoReadStartedAt ?: nowMs)) >= MailboxParser.TWO_HOURS_MS
    val remainingLabel = remember(autoReadStartedAt, nowMs) {
        val start = autoReadStartedAt ?: return@remember "0m 00s"
        val rem = (MailboxParser.TWO_HOURS_MS - (nowMs - start).coerceAtLeast(0L)).coerceAtLeast(0L)
        val secs = rem / 1000L
        val h = secs / 3600L
        val m = (secs % 3600L) / 60L
        val s = secs % 60L
        if (h > 0) "${h}h ${m.toString().padStart(2, '0')}m" else "${m}m ${s.toString().padStart(2, '0')}s"
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
                item {
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

            item {
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
                            val lineCount = remember(bulkInput) {
                                bulkInput.lines().count { it.isNotBlank() }
                            }
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
                                        text = "Clear input",
                                        fontSize = 12.sp,
                                        color = Color(0xFFDC2626),
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

                        Button(
                            onClick = {
                                errorMessage = null
                                statusMessage = null
                                if (selectedTab == 0) {
                                    when (val res = MailboxParser.parseSingle(singleInput)) {
                                        is SingleParseResult.Error -> errorMessage = res.message
                                        is SingleParseResult.Success -> {
                                            repository.saveMailboxes(listOf(res.mailbox), serviceType)
                                            singleInput = ""
                                            statusMessage = "Saved ${res.mailbox.email}"
                                        }
                                    }
                                } else {
                                    val bulk = MailboxParser.parseBulk(bulkInput)
                                    if (bulk.valid.isEmpty()) {
                                        val first = bulk.errors.firstOrNull()
                                        errorMessage = first?.let { "Line ${it.line}: ${it.error}" }
                                            ?: "Paste one or more valid Hotmail lines."
                                    } else {
                                        repository.saveMailboxes(bulk.valid, serviceType)
                                        bulkInput = ""
                                        statusMessage = "Saved ${bulk.valid.size} mailboxes"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
                        ) {
                            Text(if (selectedTab == 1) "Save All" else "Save")
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
                item {
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
                            Column {
                                Text(
                                    text = when {
                                        isExpired -> "2h auto-read completed"
                                        autoReadEnabled -> "Auto-read every 3s · $remainingLabel left"
                                        else -> "Auto-read paused · $remainingLabel left"
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${mailboxes.size} saved accounts",
                                    fontSize = 12.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        if (isExpired) repository.restartAutoRead()
                                        else repository.toggleAutoRead()
                                    }
                                ) {
                                    Text(if (isExpired) "Restart 2h" else if (autoReadEnabled) "Pause" else "Resume")
                                }
                                TextButton(onClick = { repository.clearAll() }) {
                                    Text("Clear", color = Color(0xFFDC2626))
                                }
                            }
                        }
                    }
                }
            }

            items(mailboxes, key = { it.id }) { mailbox ->
                MailboxRowCard(
                    mailbox = mailbox,
                    isActive = mailbox.id == activeId,
                    onSelect = { repository.selectActive(mailbox.id) },
                    onCopyCode = { code ->
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("OTP Code", code))
                        Toast.makeText(context, "Copied $code", Toast.LENGTH_SHORT).show()
                    },
                    onDelete = { repository.deleteMailbox(mailbox.id) }
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mailbox.email,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "${mailbox.serviceType} · ${mailbox.lastDate ?: "Waiting for mail"}",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
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
                    color = Color(0xFF334155)
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
