package com.aivo.liveoverflow

import android.app.TimePickerDialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aivo.liveoverflow.service.OverlayService
import java.util.Calendar
import java.util.Locale

/**
 * 控制面板：打开 app 先看到这里，而不是直接冒出桌宠。
 * 所有设置写 SharedPreferences，OverlayService 监听后即时生效。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    private lateinit var swPet: Switch
    private lateinit var tvStatus: TextView
    private lateinit var tvHunger: TextView
    private lateinit var hungerBar: ProgressBar
    private lateinit var btnSleepStart: Button
    private lateinit var btnSleepEnd: Button
    private lateinit var tvSize: TextView
    private lateinit var tvPerm: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("pet_prefs", MODE_PRIVATE)

        swPet = findViewById(R.id.swPet)
        tvStatus = findViewById(R.id.tvStatus)
        tvHunger = findViewById(R.id.tvHunger)
        hungerBar = findViewById(R.id.hungerBar)
        btnSleepStart = findViewById(R.id.btnSleepStart)
        btnSleepEnd = findViewById(R.id.btnSleepEnd)
        tvSize = findViewById(R.id.tvSize)
        tvPerm = findViewById(R.id.tvPerm)

        // ---- 桌宠开关 ----
        swPet.isChecked = Settings.canDrawOverlays(this)
        swPet.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (Settings.canDrawOverlays(this)) startPet()
                else {
                    swPet.isChecked = false
                    goOverlaySettings()
                }
            } else {
                prefs.edit().putBoolean("pet_running", false).apply()
                stopService(Intent(this, OverlayService::class.java))
            }
        }

        // ---- 喂食 ----
        findViewById<Button>(R.id.btnFeed).setOnClickListener {
            feed(38f)
        }

        // ---- 闲聊频率 ----
        val chatId = when (prefs.getInt("chat_rate", 2)) {
            0 -> R.id.rbChat0; 1 -> R.id.rbChat1; 3 -> R.id.rbChat3
            else -> R.id.rbChat2
        }
        (findViewById<RadioGroup>(R.id.rgChat)).check(chatId)
        findViewById<RadioGroup>(R.id.rgChat).setOnCheckedChangeListener { _, id ->
            prefs.edit().putInt(
                "chat_rate",
                when (id) {
                    R.id.rbChat0 -> 0; R.id.rbChat1 -> 1; R.id.rbChat3 -> 3
                    else -> 2
                }
            ).apply()
        }

        // ---- 饥饿速度 ----
        val speedId = when (prefs.getInt("hunger_speed", 2)) {
            1 -> R.id.rbSpeed1; 3 -> R.id.rbSpeed3; else -> R.id.rbSpeed2
        }
        findViewById<RadioGroup>(R.id.rgSpeed).check(speedId)
        findViewById<RadioGroup>(R.id.rgSpeed).setOnCheckedChangeListener { _, id ->
            prefs.edit().putInt(
                "hunger_speed",
                when (id) {
                    R.id.rbSpeed1 -> 1; R.id.rbSpeed3 -> 3; else -> 2
                }
            ).apply()
        }

        // ---- 作息 ----
        btnSleepStart.setOnClickListener { pickTime("sleep_start") }
        btnSleepEnd.setOnClickListener { pickTime("sleep_end") }

        // ---- 大小 ----
        val sb = findViewById<SeekBar>(R.id.sbSize)
        val sizeNow = prefs.getInt("size_dp", 160)
        sb.progress = sizeNow - 120
        tvSize.text = getString(R.string.size_fmt, sizeNow)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvSize.text = getString(R.string.size_fmt, p + 120)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                s?.let { prefs.edit().putInt("size_dp", it.progress + 120).apply() }
            }
        })

        // ---- 权限 ----
        findViewById<Button>(R.id.btnPerm).setOnClickListener { goOverlaySettings() }

        // ---- 关闭 ----
        findViewById<Button>(R.id.btnQuit).setOnClickListener {
            prefs.edit().putBoolean("pet_running", false).apply()
            stopService(Intent(this, OverlayService::class.java))
            swPet.isChecked = false
            Toast.makeText(this, "桌宠下线了", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startPet() {
        prefs.edit().putBoolean("pet_running", true).apply()
        val i = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
        Toast.makeText(this, "它来了", Toast.LENGTH_SHORT).show()
    }

    private fun goOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun pickTime(key: String) {
        val c = Calendar.getInstance()
        val dlg = TimePickerDialog(this, { _, h, m ->
            prefs.edit().putInt(key, h * 60 + m).apply()
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true)
        dlg.show()
    }

    private fun feed(delta: Float) {
        val h = hungerNow()
        if (h <= 2f) {
            Toast.makeText(this, "它已经吃得很饱了，等饿一点再喂", Toast.LENGTH_SHORT).show()
            return
        }
        val after = (h - delta).coerceIn(0f, 100f)
        prefs.edit()
            .putFloat("hunger_base", after)
            .putLong("hunger_time", System.currentTimeMillis())
            .apply()
        Toast.makeText(this, "它吃上了（饱食度 +${delta.toInt()}）", Toast.LENGTH_SHORT).show()
        refresh()
    }

    // ==== 状态显示（和服务端同一套算法） ====

    private fun minPerPoint(): Int =
        intArrayOf(12, 5, 2)[prefs.getInt("hunger_speed", 2) - 1]

    private fun hungerNow(): Float {
        val base = prefs.getFloat("hunger_base", 30f)
        val t = prefs.getLong("hunger_time", System.currentTimeMillis())
        val mins = ((System.currentTimeMillis() - t) / 60000f).coerceAtLeast(0f)
        return (base + mins / minPerPoint()).coerceIn(0f, 100f)
    }

    private fun inSleepWindow(): Boolean {
        val s = prefs.getInt("sleep_start", 23 * 60 + 30)
        val e = prefs.getInt("sleep_end", 7 * 60)
        val c = Calendar.getInstance()
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (s <= e) m in s..e else m >= s || m < e
    }

    private fun stateText(): String = when {
        prefs.getBoolean("force_sleep", false) -> "睡着"
        inSleepWindow() -> "睡着"
        hungerNow() >= 72f -> "饿了"
        else -> "清醒"
    }

    private fun refresh() {
        val h = hungerNow()
        // 内部 hunger：0=饱 100=饿；对外展示成饱食度（100 - hunger）
        val full = (100f - h).toInt()
        hungerBar.progress = full
        tvHunger.text = getString(R.string.hunger_fmt, full)
        tvStatus.text = getString(R.string.status_fmt, stateText())

        val granted = Settings.canDrawOverlays(this)
        swPet.isChecked = granted && prefs.getBoolean("pet_running", false)
        if (granted) {
            tvPerm.text = "悬浮窗权限：已授予"
            tvPerm.setTextColor(0xFF7BC47F.toInt())
        } else {
            tvPerm.text = "悬浮窗权限：未授予"
            tvPerm.setTextColor(0xFFC0505A.toInt())
        }

        btnSleepStart.text = getString(
            R.string.sleep_fmt, fmtMin(prefs.getInt("sleep_start", 23 * 60 + 30))
        )
        btnSleepEnd.text = getString(
            R.string.sleep_fmt, fmtMin(prefs.getInt("sleep_end", 7 * 60))
        )
    }

    private fun fmtMin(m: Int): String =
        String.format(Locale.CHINA, "%02d:%02d", m / 60, m % 60)

    override fun onResume() {
        super.onResume()
        refresh()
    }
}
