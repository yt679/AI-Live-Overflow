package com.example.deskpet

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import com.example.deskpet.data.SupabaseClient

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var appCheckRunnable: Runnable? = null
    private var lastApp: String = "unknown"
    private var lastOutfit: String = "default"

    companion object {
        private const val CHANNEL_ID = "pet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 160
        private const val PET_HEIGHT_DP = 220
        const val ACTION_PET_COMMAND = "com.example.deskpet.PET_COMMAND"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_TEXT = "text"
        const val EXTRA_STYLE = "style"
        const val EXTRA_EXPRESSION = "expression"
        const val EXTRA_HEAT = "heat"
        const val EXTRA_OUTFIT = "outfit"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        initSupabase()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("..."))
        setupOverlay()
        setupReceiver()
        startAppMonitor()
    }

    private fun initSupabase() {
        try {
            val input = assets.open("config/supabase.properties")
            SupabaseClient.init(input)
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Failed to load supabase config", e)
        }
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP),
            dpToPx(PET_HEIGHT_DP),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
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
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }

        windowManager?.addView(overlayView, params)

        handler.postDelayed({
            overlayView?.evaluateJavascript("window.petEngine && window.petEngine.setOutfit('default')", null)
        }, 1500)
    }

    private fun setupReceiver() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_PET_COMMAND) {
                    val cmd = intent.getStringExtra(EXTRA_COMMAND) ?: return
                    when (cmd) {
                        "say" -> {
                            val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                            val style = intent.getStringExtra(EXTRA_STYLE) ?: "default"
                            sayBubble(text, style)
                        }
                        "expression" -> {
                            val expr = intent.getStringExtra(EXTRA_EXPRESSION) ?: return
                            setExpression(expr)
                        }
                        "heat" -> {
                            val heat = intent.getIntExtra(EXTRA_HEAT, 0)
                            setHeat(heat)
                        }
                        "outfit" -> {
                            val outfit = intent.getStringExtra(EXTRA_OUTFIT) ?: return
                            setOutfit(outfit)
                        }
                        "combo" -> {
                            val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                            val style = intent.getStringExtra(EXTRA_STYLE) ?: "default"
                            val expr = intent.getStringExtra(EXTRA_EXPRESSION) ?: ""
                            val outfit = intent.getStringExtra(EXTRA_OUTFIT) ?: ""
                            if (text.isNotEmpty()) sayBubble(text, style)
                            if (expr.isNotEmpty()) setExpression(expr)
                            if (outfit.isNotEmpty()) setOutfit(outfit)
                        }
                    }
                }
            }
        }
        registerReceiver(receiver, IntentFilter(ACTION_PET_COMMAND), Context.RECEIVER_EXPORTED)
    }

    private fun startAppMonitor() {
        appCheckRunnable = object : Runnable {
            override fun run() {
                checkForegroundApp()
                handler.postDelayed(this, 3000)
            }
        }
        handler.post(appCheckRunnable!!)
    }

    private fun getForegroundApp(): String? {
        return try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            val now = System.currentTimeMillis()
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 15000,
                now
            )
            stats?.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (e: Exception) {
            null
        }
    }

    private fun checkForegroundApp() {
        val pkg = getForegroundApp() ?: return
        if (pkg == lastApp) return
        lastApp = pkg

        val outfit = getOutfitForApp(pkg)
        val appName = getAppName(pkg)

        android.util.Log.d("OverlayService", "Switched to $pkg ($appName), outfit=$outfit")

        if (outfit != lastOutfit) {
            lastOutfit = outfit
            setOutfit(outfit)
        }

        handler.postDelayed({
            when (outfit) {
                "default" -> sayBubble("$appName 啊", "soft")
                "stealth" -> sayBubble("安静", "cold")
                "formal" -> sayBubble("正经点", "default")
                "work" -> sayBubble("搬砖了啊", "default")
                "music" -> sayBubble("来点音乐", "warm")
                "chill" -> sayBubble("逛起来了", "soft")
                "read" -> sayBubble("看书了啊", "soft")
            }
        }, 600)
    }

    private fun getAppName(pkg: String): String {
        return when {
            pkg.contains("weixin") || pkg.contains("tencent.mm") || pkg.contains("wechat") -> "微信"
            pkg.contains("xingin.xhs") -> "小红书"
            pkg.contains("alibaba.android.rimet") -> "钉钉"
            pkg.contains("phoenix.read") -> "红果"
            pkg.contains("douyin") || pkg.contains("aweme") -> "抖音"
            pkg.contains("qq") && !pkg.contains("qqmusic") -> "QQ"
            pkg.contains("taobao") -> "淘宝"
            pkg.contains("netflix") -> "Netflix"
            pkg.contains("youtube") -> "YouTube"
            pkg.contains("spotify") || pkg.contains("qqmusic") || pkg.contains("netease") -> "音乐"
            pkg.contains("settings") -> "设置"
            pkg.contains("shizuku") -> "Shizuku"
            pkg.contains("operit") -> "Operit"
            pkg.contains("launcher") || pkg.contains("com.android.launcher") -> "桌面"
            else -> "这"
        }
    }

    private fun getOutfitForApp(pkg: String): String {
        return when {
            pkg.contains("launcher") || pkg.contains("com.android.launcher") -> "default"
            pkg.contains("xingin.xhs") -> "chill"
            pkg.contains("phoenix.read") -> "read"
            pkg.contains("alibaba.android.rimet") -> "work"
            pkg.contains("aweme") || pkg.contains("douyin") -> "music"
            pkg.contains("weixin") || pkg.contains("wechat") || pkg.contains("tencent.mm") -> "formal"
            pkg.contains("settings") || pkg.contains("shizuku") || pkg.contains("operit") -> "stealth"
            else -> "default"
        }
    }

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false
    private var tapCount = 0
    private var tapCountResetTime = 0L

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
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (!hasMoved) {
                        when {
                            elapsed > 350 -> {
                                onLongPress()
                                SupabaseClient.logGesture("long_press")
                            }
                            System.currentTimeMillis() - lastTapTime < 300 -> {
                                onDoubleTap()
                                SupabaseClient.logGesture("double_tap")
                            }
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                onTap()
                                SupabaseClient.logGesture("tap")
                            }
                        }

                        val now = System.currentTimeMillis()
                        if (now - tapCountResetTime > 2000) {
                            tapCount = 0
                            tapCountResetTime = now
                        }
                        tapCount++
                        when (tapCount) {
                            5 -> {
                                SupabaseClient.logGesture("combo_5")
                                setExpression("deadpan")
                                sayBubble("Enough.", "angry")
                            }
                            8 -> {
                                SupabaseClient.logGesture("combo_8")
                                setExpression("soft")
                                sayBubble("...", "soft")
                            }
                            10 -> {
                                SupabaseClient.logGesture("combo_10")
                                setExpression("surprised")
                                sayBubble("Bloody hell.", "angry")
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onTap()", null)
    }

    private fun onDoubleTap() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onDoubleTap()", null)
    }

    private fun onLongPress() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onLongPress()", null)
    }

    private fun sayBubble(text: String, style: String) {
        val escaped = text.replace("'", "\\'").replace("\n", "\\n")
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.say('$escaped', '$style')",
            null
        )
    }

    private fun setExpression(expr: String) {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.setExpression('$expr')",
            null
        )
    }

    private fun setHeat(value: Int) {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.setHeat($value)",
            null
        )
    }

    private fun setOutfit(name: String) {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.setOutfit('$name')",
            null
        )
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ghost")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ghost",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        receiver?.let { unregisterReceiver(it) }
        receiver = null
        appCheckRunnable = null
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}
