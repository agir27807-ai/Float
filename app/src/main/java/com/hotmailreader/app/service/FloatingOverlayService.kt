package com.hotmailreader.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.hotmailreader.app.MainActivity
import com.hotmailreader.app.R
import com.hotmailreader.app.data.MailPollUpdate
import com.hotmailreader.app.data.MailboxRepository
import com.hotmailreader.app.network.DongvanApiClient
import com.hotmailreader.app.parser.MailboxParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var repository: MailboxRepository
    private var overlayView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null
    private val arrivalSeq = AtomicLong(System.currentTimeMillis())

    private var txtEmail: TextView? = null
    private var txtOtp: TextView? = null
    private var previewDrawer: LinearLayout? = null
    private var txtPreviewService: TextView? = null
    private var txtPreviewDate: TextView? = null
    private var txtPreviewContent: TextView? = null
    private var currentCode: String = ""
    private var currentEmail: String = ""

    override fun onCreate() {
        super.onCreate()
        repository = MailboxRepository.getInstance(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), fgType)

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        attachOverlayView()
        observeRepositoryState()
        startAutoPollLoop()
        repository.setOverlayRunning(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        repository.setOverlayRunning(false)
        pollJob?.cancel()
        serviceScope.cancel()
        overlayView?.let { view ->
            if (view.isAttachedToWindow) {
                windowManager.removeView(view)
            }
        }
        overlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun attachOverlayView() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.floating_otp_bar, null)
        overlayView = view

        val (savedX, savedY) = repository.getOverlayPosition()
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        txtEmail = view.findViewById(R.id.txtFloatingEmail)
        txtOtp = view.findViewById(R.id.txtFloatingOtp)
        previewDrawer = view.findViewById(R.id.previewDrawer)
        txtPreviewService = view.findViewById(R.id.txtPreviewService)
        txtPreviewDate = view.findViewById(R.id.txtPreviewDate)
        txtPreviewContent = view.findViewById(R.id.txtPreviewContent)

        val dragHandle = view.findViewById<ImageView>(R.id.btnDragHandle)
        val otpChip = view.findViewById<LinearLayout>(R.id.btnCopyOtp)
        val toggleBtn = view.findViewById<ImageButton>(R.id.btnTogglePreview)

        setupDragTouchListener(dragHandle)
        setupDragTouchListener(view.findViewById(R.id.floatingBarContainer))

        txtEmail?.setOnClickListener {
            if (currentEmail.isNotEmpty()) {
                copyToClipboard("Hotmail", currentEmail)
            }
        }

        otpChip.setOnClickListener {
            if (currentCode.isNotEmpty()) {
                copyToClipboard("OTP Code", currentCode)
                txtOtp?.text = "OTP: Copied!"
                serviceScope.launch {
                    delay(1_400)
                    txtOtp?.text = if (currentCode.isNotEmpty()) "OTP: $currentCode" else "OTP: ----"
                }
            } else {
                Toast.makeText(this, "Waiting for OTP code...", Toast.LENGTH_SHORT).show()
            }
        }

        toggleBtn.setOnClickListener {
            val drawer = previewDrawer ?: return@setOnClickListener
            val isVisible = drawer.visibility == View.VISIBLE
            drawer.visibility = if (isVisible) View.GONE else View.VISIBLE
            toggleBtn.rotation = if (isVisible) 0f else 180f
        }

        windowManager.addView(view, layoutParams)
    }

    private fun setupDragTouchListener(target: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        target.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 4 || kotlin.math.abs(dy) > 4) {
                        moved = true
                    }
                    val metrics = resources.displayMetrics
                    val maxX = (metrics.widthPixels - (overlayView?.width ?: 300)).coerceAtLeast(0)
                    val maxY = (metrics.heightPixels - (overlayView?.height ?: 100)).coerceAtLeast(0)
                    val nextX = (initialX + dx).coerceIn(0, maxX)
                    val nextY = (initialY + dy).coerceIn(0, maxY)
                    if (nextX != layoutParams.x || nextY != layoutParams.y) {
                        layoutParams.x = nextX
                        layoutParams.y = nextY
                        overlayView?.let {
                            if (it.isAttachedToWindow) {
                                windowManager.updateViewLayout(it, layoutParams)
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        repository.saveOverlayPosition(layoutParams.x, layoutParams.y)
                        true
                    } else {
                        v.performClick()
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun observeRepositoryState() {
        var lastRenderedKey = ""
        serviceScope.launch {
            combine(repository.mailboxes, repository.activeId) { list, activeId ->
                list.firstOrNull { it.id == activeId } ?: list.firstOrNull()
            }.collectLatest { active ->
                val nextEmail = active?.email.orEmpty()
                val nextCode = active?.lastCode.orEmpty()
                val nextService = active?.serviceType.orEmpty()
                val nextDate = active?.lastDate.orEmpty()
                val nextContent = active?.lastContent.orEmpty()
                val key = "$nextEmail|$nextCode|$nextService|$nextDate|$nextContent"
                if (key == lastRenderedKey && active != null) return@collectLatest
                lastRenderedKey = key

                if (active == null) {
                    currentEmail = ""
                    currentCode = ""
                    txtEmail?.text = "No mail saved"
                    txtOtp?.text = "OTP: ----"
                    txtPreviewService?.text = "No mailbox"
                    txtPreviewDate?.text = ""
                    txtPreviewContent?.text = "Save Hotmail OAuth2 accounts in the app."
                } else {
                    currentEmail = nextEmail
                    currentCode = nextCode
                    txtEmail?.text = nextEmail
                    txtOtp?.text = if (nextCode.isNotEmpty()) "OTP: $nextCode" else "OTP: ----"
                    txtPreviewService?.text = "${nextService.replaceFirstChar { it.uppercase() }} latest mail"
                    txtPreviewDate?.text = nextDate
                    txtPreviewContent?.text = nextContent.ifEmpty {
                        "Auto-reading saved mailboxes every 3s."
                    }
                }
            }
        }
    }

    private fun startAutoPollLoop() {
        pollJob?.cancel()
        pollJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (repository.isAutoReadActive()) {
                    val snapshot = repository.mailboxes.value
                    snapshot.chunked(10).forEach { batch ->
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
                                receivedSeq = arrivalSeq.incrementAndGet()
                            )
                        }
                        if (updates.isNotEmpty()) {
                            repository.updateMailResultsBatch(updates)
                        }
                    }
                }
                delay(MailboxParser.AUTO_POLL_INTERVAL_MS)
            }
        }
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "Copied $value", Toast.LENGTH_SHORT).show()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hotmail Floating OTP Reader",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Floating OTP Reader Active")
            .setContentText("Auto-reading Hotmail accounts every 3s")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "hotmail_otp_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.hotmailreader.app.STOP_OVERLAY"
    }
}
