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

    companion object {
        private const val CHANNEL_ID = "pet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 150
        private const val PET_HEIGHT_DP = 200
        const val SUPABASE_URL = "https://htdzpguzxtwwsyytltew.supabase.co"
        const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imh0ZHpwZ3V6eHR3d3N5eXRsdGV3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODI0MDI2MTYsImV4cCI6MjA5Nzk3ODYxNn0.9Gdc9YUzZifVUthdRcHfp6XP1tzCZpXbie_-LJlryjI"
        
        var isRunning = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("🐾 知言在这儿"))
        setupOverlay()
        startAppDetection()
        registerBatteryReceiver()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        params = WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP),
            dpToPx(PET_HEIGHT_DP),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 200
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
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        hasMoved = true
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (!hasMoved) {
                        val now = System.currentTimeMillis()
                        if (now - comboStartTime > 2000) tapCount = 0
                        if (tapCount == 0) comboStartTime = now
                        tapCount++
                        when {
                            elapsed > 600 -> { onLongPress(); tapCount = 0 }
                            now - lastTapTime < 300 -> { onDoubleTap(); tapCount = 0 }
                            else -> {
                                lastTapTime = now
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (tapCount >= 8) { onCombo(8); tapCount = 0 }
                                    else if (tapCount >= 5) { onCombo(5); tapCount = 0 }
                                    else if (tapCount >= 3) { onCombo(3); tapCount = 0 }
                                    else if (tapCount > 0) onTap()
                                }, 350)
                                true
                            }
                        }
                    } else true
                }
                else -> false
            }
        }
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
    }

    private fun getAppName(pkg: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) { pkg }
    }

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
                reportAppEvent(currentPkg)
                overlayView?.evaluateJavascript(
                    "window.petEngine && window.petEngine.onAppChange('$currentPkg')", null)
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

    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val pct = level * 100 / scale
                val charging = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                if (charging != 0)
                    overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onPowerChange('charging')", null)
                else if (pct <= 15)
                    overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onPowerChange('low_battery')", null)
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, com.example.deskpet.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🐾 知言").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true).setSilent(true).setContentIntent(pi).build()
    }

    fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
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
            return "${cal.get(java.util.Calendar.HOUR_OF_DAY)}:${String.format("%02d", cal.get(java.util.Calendar.MINUTE))}"
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        isRunning = false
        batteryReceiver?.let { unregisterReceiver(it) }
        appCheckHandler?.removeCallbacks(appCheckRunnable!!)
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
        super.onDestroy()
    }
}
