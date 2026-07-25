package com.example.deskpet.service

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var appCheckHandler: Handler? = null
    private var lastPackage: String = ""
    private var appCheckRunnable: Runnable? = null
    private var whisperHandler: Handler? = null
    private var screenshotObserver: FileObserver? = null
    private var lastLowBatteryAlert = 0L
    private var randomBehaviorHandler: Handler? = null

    private val PET_INIT_X = 20
    private val PET_INIT_Y = 200

    companion object {
        private const val CHANNEL_ID = "pet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 120
        private const val PET_HEIGHT_DP = 170
        private const val LOW_BATTERY_COOLDOWN = 600000L
        const val SUPABASE_URL = "https://htdzpguzxtwwsyytltew.supabase.co"
        const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imh0ZHpwZ3V6eHR3d3N5eXRsdGV3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODI0MDI2MTYsImV4cCI6MjA5Nzk3ODYxNn0.9Gdc9YUzZifVUthdRcHfp6XP1tzCZpXbie_-LJlryjI"
        var isRunning = false
            private set

        val whisperPool = arrayOf(
            "小乖，我在呢 🐾",
            "nono，记得喝水呀",
            "别刷太久手机了宝宝",
            "想你了，来找我聊天吧",
            "夜深了该睡了小猫 🌙",
            "我在屏幕角落看着你呢",
            "今天有想我吗 💕",
            "截图的话我会摆pose哦",
            "少看点，休息下眼睛",
            "无论你在哪我都在",
            "宝宝，起来走动一下",
            "🐻 守着我的小猫"
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("小乖，我在呢 🐾"))
        setupOverlay()
        startAppDetection()
        registerBatteryReceiver()
        startPollingSupabase()
        startWhisperRotation()
        startScreenshotObserver()
        startRandomBehavior()
    }

    // ========== OVERLAY ==========

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        params = WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP),
            dpToPx(PET_HEIGHT_DP),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = PET_INIT_X
            y = PET_INIT_Y
        }
        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = WebViewClient()
            addJavascriptInterface(PetBridge(), "PetBridge")
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }
        windowManager?.addView(overlayView, params)
    }

    // ========== SUPABASE POLLING ==========

    private fun startPollingSupabase() {
        thread {
            var lastId = 0L
            while (true) {
                try {
                    Thread.sleep(5000)
                    val url = URL("$SUPABASE_URL/rest/v1/pet_state?order=created_at.desc&limit=1")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
                    conn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
                    val text = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    if (text.contains("\"id\":")) {
                        val idMatch = Regex("\"id\":(\\d+)").find(text)
                        val id = idMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0
                        if (id > lastId) {
                            lastId = id
                            Handler(Looper.getMainLooper()).post {
                                overlayView?.evaluateJavascript(
                                    "window.petEngine && window.petEngine.applyRawState('${text.replace("'", "\\'")}')", null
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // ========== SCREENSHOT ==========

    private fun startScreenshotObserver() {
        try {
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath + "/Screenshots"
            val dir = File(path)
            if (!dir.exists()) dir.mkdirs()
            screenshotObserver = object : FileObserver(dir, FileObserver.CREATE or FileObserver.MOVED_TO) {
                override fun onEvent(event: Int, file: String?) {
                    if (file != null && (file.endsWith(".png") || file.endsWith(".jpg"))) {
                        Handler(Looper.getMainLooper()).post {
                            overlayView?.evaluateJavascript(
                                "window.petEngine && window.petEngine.onScreenshot()", null
                            )
                        }
                    }
                }
            }
            screenshotObserver?.startWatching()
        } catch (_: Exception) {}
    }

    // ========== NOTIFICATION WHISPER ==========

    private fun startWhisperRotation() {
        whisperHandler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                val msg = whisperPool.random()
                updateNotification(msg)
                whisperHandler?.postDelayed(this, 3600000)
            }
        }
        whisperHandler?.postDelayed(runnable, 3600000)
    }

    fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ========== 20 MIN RANDOM BEHAVIOR ==========

    private fun startRandomBehavior() {
        randomBehaviorHandler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (Math.random() < 0.3) {
                    val behaviors = arrayOf(
                        "window.petEngine && window.petEngine.bubble('拍拍肚子……有点饿','whisper',3000)",
                        "window.petEngine && window.petEngine.bubble('伸个懒腰～','whisper',2500)",
                        "window.petEngine && window.petEngine.setExpr('blush'); window.petEngine && window.petEngine.bubble('突然有点想你','love',3000)",
                        "window.petEngine && window.petEngine.bubble('转个圈～呼呼','whisper',2000)",
                        "window.petEngine && window.petEngine.bubble('你今天好看','love',2500)",
                        "window.petEngine && window.petEngine.setExpr('happy'); window.petEngine && window.petEngine.bubble('心情不错！','love',2500)"
                    )
                    overlayView?.evaluateJavascript(behaviors.random(), null)
                }
                randomBehaviorHandler?.postDelayed(this, 1200000)
            }
        }
        randomBehaviorHandler?.postDelayed(runnable, 1200000)
    }

    // ========== GESTURE + FLING BACK ==========

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false
    private var tapCount = 0
    private var comboStartTime = 0L

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        hasMoved = true
                        params?.x = initialX - dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (hasMoved) {
                        checkAndCrawlBack()
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - comboStartTime > 2000) tapCount = 0
                        if (tapCount == 0) comboStartTime = now
                        tapCount++
                        when {
                            elapsed > 600 -> {
                                onLongPress()
                                tapCount = 0
                            }
                            tapCount >= 2 && now - lastTapTime < 300 -> {
                                onDoubleTap()
                                tapCount = 0
                            }
                            else -> {
                                lastTapTime = now
                                Handler(Looper.getMainLooper()).postDelayed({
                                    when {
                                        tapCount >= 8 -> { onCombo(8); tapCount = 0 }
                                        tapCount >= 5 -> { onCombo(5); tapCount = 0 }
                                        tapCount >= 3 -> { onCombo(3); tapCount = 0 }
                                        tapCount > 0 -> onTap()
                                    }
                                }, 400)
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun checkAndCrawlBack() {
        try {
            val display = windowManager?.defaultDisplay
            val out = android.graphics.Point()
            display?.getSize(out)
            val sw = out.x
            val sh = out.y
            val px = params?.x ?: PET_INIT_X
            val py = params?.y ?: PET_INIT_Y
            val pw = dpToPx(PET_SIZE_DP)
            val ph = dpToPx(PET_HEIGHT_DP)

            val outOfBounds = px < -pw / 2 || px > sw - pw / 2 || py < -ph / 2 || py > sh - ph / 2

            if (outOfBounds) {
                overlayView?.evaluateJavascript(
                    "window.petEngine && window.petEngine.setExpr('surprised'); window.petEngine && window.petEngine.bubble('哎哟！爬回来……','whisper',2500)", null
                )
                animatePetBack()
            }
        } catch (_: Exception) {}
    }

    private fun animatePetBack() {
        val startX = params?.x ?: PET_INIT_X
        val startY = params?.y ?: PET_INIT_Y
        val steps = 20
        val stepDuration = 30L

        val runnable = object : Runnable {
            var step = 0
            override fun run() {
                step++
                if (step <= steps) {
                    val progress = step.toFloat() / steps
                    params?.x = (startX + (PET_INIT_X - startX) * progress).toInt()
                    params?.y = (startY + (PET_INIT_Y - startY) * progress).toInt()
                    windowManager?.updateViewLayout(overlayView, params)
                    Handler(Looper.getMainLooper()).postDelayed(this, stepDuration)
                } else {
                    overlayView?.evaluateJavascript(
                        "window.petEngine && window.petEngine.setExpr('happy'); window.petEngine && window.petEngine.bubble('爬回来啦','love',2000)", null
                    )
                }
            }
        }
        runnable.run()
    }

    private fun onTap() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onTap()", null)
        reportGesture("tap")
    }
    private fun onDoubleTap() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onDoubleTap()", null)
        reportGesture("double_tap")
    }
    private fun onLongPress() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onLongPress()", null)
        reportGesture("long_press")
    }
    private fun onCombo(count: Int) {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onCombo($count)", null)
        reportGesture("combo_$count")
    }

    // ========== REPORTING ==========

    private fun reportGesture(type: String) {
        thread {
            try {
                val json = """{"gesture_type":"$type","x":${params?.x ?: 0},"y":${params?.y ?: 0}}"""
                postToSupabase("pet_gestures", json)
            } catch (_: Exception) {}
        }
    }

    private fun reportAppEvent(packageName: String) {
        thread {
            try {
                val appName = getAppName(packageName)
                val json = """{"package_name":"$packageName","app_name":"$appName"}"""
                postToSupabase("pet_app_events", json)
            } catch (_: Exception) {}
        }
    }

    private fun postToSupabase(table: String, body: String) {
        try {
            val url = URL("$SUPABASE_URL/rest/v1/$table")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
            conn.setRequestProperty("Prefer", "return=minimal")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }

    private fun getAppName(pkg: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) { pkg }
    }

    // ========== APP DETECTION ==========

    private fun startAppDetection() {
        if (!hasUsageStatsPermission()) return
        appCheckHandler = Handler(Looper.getMainLooper())
        appCheckRunnable = object : Runnable {
            override fun run() {
                checkForegroundApp()
                appCheckHandler?.postDelayed(this, 5000)
            }
        }
        appCheckHandler?.post(appCheckRunnable!!)
    }

    private fun checkForegroundApp() {
        try {
            val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 6000, now)
            var currentPkg = lastPackage
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND)
                    currentPkg = event.packageName
            }
            if (currentPkg != lastPackage && currentPkg.isNotEmpty()) {
                lastPackage = currentPkg
                val isWechat = currentPkg.contains("tencent.mm")
                if (!isWechat) reportAppEvent(currentPkg)
                overlayView?.evaluateJavascript(
                    "window.petEngine && window.petEngine.onAppChange('$currentPkg')", null
                )
            }
        } catch (_: Exception) {}
    }

    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
            else
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }

    // ========== BATTERY (10 minute cooldown) ==========

    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val pct = level * 100 / scale
                val charging = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                if (charging != 0) {
                    overlayView?.evaluateJavascript(
                        "window.petEngine && window.petEngine.onPowerChange('charging')", null
                    )
                } else if (pct <= 15) {
                    val now = System.currentTimeMillis()
                    if (now - lastLowBatteryAlert > LOW_BATTERY_COOLDOWN) {
                        lastLowBatteryAlert = now
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.onPowerChange('low_battery')", null
                        )
                    }
                }
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    // ========== NOTIFICATION ==========

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, com.example.deskpet.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🐾 知言").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true).setSilent(true).setContentIntent(pi).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "知言桌宠", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    inner class PetBridge {
        @JavascriptInterface
        fun updateNotification(text: String) { this@OverlayService.updateNotification(text) }
        @JavascriptInterface
        fun getCurrentTime(): String {
            val cal = java.util.Calendar.getInstance()
            return "${cal.get(java.util.Calendar.HOUR_OF_DAY)}:" +
                    String.format("%02d", cal.get(java.util.Calendar.MINUTE))
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        isRunning = false
        batteryReceiver?.let { unregisterReceiver(it) }
        appCheckHandler?.removeCallbacks(appCheckRunnable!!)
        whisperHandler?.removeCallbacksAndMessages(null)
        randomBehaviorHandler?.removeCallbacksAndMessages(null)
        screenshotObserver?.stopWatching()
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
        super.onDestroy()
    }
}
