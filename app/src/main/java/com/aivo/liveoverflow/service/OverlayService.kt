package com.aivo.liveoverflow.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.Calendar

/**
 * 悬浮窗桌宠服务（v2）。
 *
 * 职责：
 * - 透明 WebView 渲染 assets/pet.html
 * - 状态机：清醒 / 睡觉 / 吃饭 / 饿了（饥饿度随真实时间增长，重启不丢）
 * - 设置同步：MainActivity 改 SharedPreferences，这里监听并即时推送
 * - JS 桥：拖拽、动态调整窗口大小、喂食、开设置
 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "pet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS = "pet_prefs"

        private const val TICK_MS = 15000L
        private const val EAT_MS = 7000L

        // 每涨 1 点饥饿需要的分钟数（慢/正常/快）
        private val MIN_PER_POINT = intArrayOf(12, 5, 2)
    }

    private var wm: WindowManager? = null
    private var web: WebView? = null
    private var params: WindowManager.LayoutParams? = null

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    private var eatingUntil = 0L
    private var lastNotifText = ""

    // ==== 生命周期 ====

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(notifText()))
        setupOverlay()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        startTick()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pushState()
        return START_STICKY
    }

    override fun onDestroy() {
        tick?.let { handler.removeCallbacks(it) }
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        web?.let {
            wm?.removeView(it)
            it.destroy()
        }
        web = null
        super.onDestroy()
    }

    // ==== 悬浮窗 ====

    private fun setupOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val sizeDp = prefs.getInt("size_dp", 160)
        params = WindowManager.LayoutParams(
            dpToPx(sizeDp), baseHeightPx(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        web = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pushState()
                }
            }
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            addJavascriptInterface(Bridge(), "PetBridge")
            loadUrl("file:///android_asset/pet.html")
        }

        wm?.addView(web, params)
    }

    // ==== JS 桥 ====

    private inner class Bridge {
        @JavascriptInterface
        fun getState(): String = buildStateJson().toString()

        @JavascriptInterface
        fun feed(food: String) = post { onFeed(food) }

        @JavascriptInterface
        fun dragStart() = post { onDragStart() }

        @JavascriptInterface
        fun dragTo(dx: Int, dy: Int) = post { onDragTo(dx, dy) }

        @JavascriptInterface
        fun resize(wDp: Int, hDp: Int) = post { onResize(wDp, hDp) }

        @JavascriptInterface
        fun event(kind: String) = post { onEvent(kind) }

        @JavascriptInterface
        fun openSettings() = post {
            val i = Intent(this@OverlayService, com.aivo.liveoverflow.MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
    }

    private fun post(r: () -> Unit) { handler.post(r) }

    // ==== 状态机 ====

    private fun minPerPoint(): Int =
        MIN_PER_POINT[prefs.getInt("hunger_speed", 2) - 1]

    private fun hungerNow(): Float {
        val base = prefs.getFloat("hunger_base", 30f)
        val t = prefs.getLong("hunger_time", System.currentTimeMillis())
        val mins = ((System.currentTimeMillis() - t) / 60000f).coerceAtLeast(0f)
        return (base + mins / minPerPoint()).coerceIn(0f, 100f)
    }

    private fun setHunger(h: Float) {
        prefs.edit()
            .putFloat("hunger_base", h.coerceIn(0f, 100f))
            .putLong("hunger_time", System.currentTimeMillis())
            .apply()
    }

    private fun sleepStartMin() = prefs.getInt("sleep_start", 23 * 60 + 30)
    private fun sleepEndMin() = prefs.getInt("sleep_end", 7 * 60)

    private fun minuteOfDay(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    private fun inSleepWindow(): Boolean {
        val s = sleepStartMin()
        val e = sleepEndMin()
        val m = minuteOfDay()
        return if (s <= e) m in s..e else m >= s || m < e
    }

    /** 手动叫醒：到当前睡眠窗口结束前都保持清醒 */
    private fun awakeOverrideUntil(): Long = prefs.getLong("awake_override", 0L)

    private fun currentState(): String {
        if (System.currentTimeMillis() < eatingUntil) return "eat"
        if (prefs.getBoolean("force_sleep", false)) return "sleep"
        if (inSleepWindow() && System.currentTimeMillis() >= awakeOverrideUntil()) return "sleep"
        return if (hungerNow() >= 72f) "hungry" else "idle"
    }

    private fun buildStateJson(): JSONObject = JSONObject().apply {
        put("state", currentState())
        put("hunger", hungerNow().toDouble())
        put("rate", prefs.getInt("chat_rate", 2))
        put("size", prefs.getInt("size_dp", 160))
    }

    private fun pushState() {
        val w = web ?: return
        w.evaluateJavascript(
            "window.PetUI && PetUI.applyState(${buildStateJson()})", null
        )
        val t = notifText()
        if (t != lastNotifText) updateNotification(t)
    }

    private fun notifText(): String = when (currentState()) {
        "sleep" -> "睡着呢… Zzz"
        "eat" -> "干饭中…"
        "hungry" -> "饿了，戳它喂点吃的"
        else -> "在守着你。"
    }

    private fun onFeed(food: String) {
        val (delta, name) = when (food) {
            "rice" -> 38f to "饭团"
            "fish" -> 26f to "小鱼干"
            "soup" -> 16f to "热汤"
            else -> 25f to "饭"
        }
        // 已经很饱了就不吃（hunger 越小越饱）
        if (hungerNow() <= 2f) {
            web?.evaluateJavascript(
                "window.PetUI && PetUI.say('吃不下了……已经很撑了。')", null
            )
            pushState()
            return
        }
        setHunger(hungerNow() - delta)
        eatingUntil = System.currentTimeMillis() + EAT_MS
        pushState()
        handler.postDelayed({
            if (System.currentTimeMillis() >= eatingUntil - 50) {
                web?.evaluateJavascript(
                    "window.PetUI && PetUI.say('吃饱了……谢谢。')", null
                )
            }
        }, EAT_MS + 200)
    }

    private fun onEvent(kind: String) {
        when (kind) {
            "sleep" -> prefs.edit().putBoolean("force_sleep", true).apply()
            "wake" -> {
                // 睡醒后撑到当前睡眠窗口结束为止
                val until = if (inSleepWindow()) {
                    val s = sleepStartMin(); val e = sleepEndMin(); val m = minuteOfDay()
                    val remain = if (s <= e) e - m
                    else if (m >= s) (1440 - m) + e else e - m
                    System.currentTimeMillis() + remain * 60000L
                } else 0L
                prefs.edit()
                    .putBoolean("force_sleep", false)
                    .putLong("awake_override", until)
                    .apply()
            }
        }
        pushState()
    }

    // ==== 定时器 ====

    private fun startTick() {
        val r = object : Runnable {
            override fun run() {
                pushState()
                handler.postDelayed(this, TICK_MS)
            }
        }
        tick = r
        handler.post(r)
    }

    // ==== 设置同步 ====

    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            handler.post {
                onResize(0, 0)  // 尺寸可能变了，回到基准大小
                pushState()
            }
        }

    private fun baseHeightPx(): Int =
        dpToPx((prefs.getInt("size_dp", 160) * 1.28f).toInt().coerceAtLeast(140))

    // ==== 拖拽 / 缩放 ====

    /** 拖动采用屏幕绝对坐标：JS 传 screenX/screenY 相对位移（CSS 像素），不随窗口移动反馈 */
    private var dragBaseX = 0
    private var dragBaseY = 0

    private fun onDragStart() {
        val p = params ?: return
        dragBaseX = p.x
        dragBaseY = p.y
    }

    private fun onDragTo(dx: Int, dy: Int) {
        val p = params ?: return
        val dm = resources.displayMetrics
        val d = dm.density
        p.x = (dragBaseX + (dx * d).toInt()).coerceIn(0, (dm.widthPixels - p.width).coerceAtLeast(0))
        p.y = (dragBaseY + (dy * d).toInt()).coerceIn(0, (dm.heightPixels - p.height).coerceAtLeast(0))
        wm?.updateViewLayout(web, p)
    }

    /** wDp/hDp 为 0 表示恢复基准大小；菜单展开时保持底边不动 */
    private fun onResize(wDp: Int, hDp: Int) {
        val p = params ?: return
        val dm = resources.displayMetrics
        val newW = if (wDp > 0) dpToPx(wDp) else dpToPx(prefs.getInt("size_dp", 160))
        val newH = if (hDp > 0) dpToPx(hDp) else baseHeightPx()
        val oldBottom = p.y + p.height
        p.width = newW
        p.height = newH
        p.y = (oldBottom - newH).coerceIn(0, (dm.heightPixels - newH).coerceAtLeast(0))
        p.x = p.x.coerceIn(0, (dm.widthPixels - newW).coerceAtLeast(0))
        wm?.updateViewLayout(web, p)
    }

    // ==== 通知 ====

    private fun buildNotification(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Live Overflow")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        lastNotifText = text
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "桌宠", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()
}
