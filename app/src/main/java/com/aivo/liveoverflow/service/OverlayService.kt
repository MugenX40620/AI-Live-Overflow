package com.aivo.liveoverflow.service

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.IBinder
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var touchX = 0f
    private var touchY = 0f
    private var isDragging = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 创建一个自定义的 View 来绘制桌宠
        overlayView = object : View(this) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)

                // 1. 画粉色圆球身体
                val bodyPaint = Paint().apply {
                    color = Color.parseColor("#FF6B9D") // 樱花粉
                    isAntiAlias = true
                    style = Paint.Style.FILL
                }
                // 圆心在视图中心，半径40dp
                canvas.drawCircle(width / 2f, height / 2f, 40f, bodyPaint)

                // 2. 画白色大眼睛
                val eyePaint = Paint().apply {
                    color = Color.WHITE
                    isAntiAlias = true
                    style = Paint.Style.FILL
                }
                // 左眼
                canvas.drawCircle(width / 2f - 15f, height / 2f - 10f, 8f, eyePaint)
                // 右眼
                canvas.drawCircle(width / 2f + 15f, height / 2f - 10f, 8f, eyePaint)

                // 3. 画黑色瞳孔（稍微偏一点，显得灵动）
                val pupilPaint = Paint().apply {
                    color = Color.BLACK
                    isAntiAlias = true
                    style = Paint.Style.FILL
                }
                // 左眼瞳孔
                canvas.drawCircle(width / 2f - 15f, height / 2f - 10f, 4f, pupilPaint)
                // 右眼瞳孔
                canvas.drawCircle(width / 2f + 15f, height / 2f - 10f, 4f, pupilPaint)
                
                // 4. 画高光（让眼睛更有神）
                val highlightPaint = Paint().apply {
                    color = Color.WHITE
                    isAntiAlias = true
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(width / 2f - 17f, height / 2f - 12f, 2f, highlightPaint)
                canvas.drawCircle(width / 2f + 13f, height / 2f - 12f, 2f, highlightPaint)
            }
        }

        // 设置触摸拖动逻辑
        overlayView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = true
                    touchX = event.x
                    touchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        val params = overlayView.layoutParams as WindowManager.LayoutParams
                        params.x += (event.rawX - touchX).toInt()
                        params.y += (event.rawY - touchY).toInt()
                        windowManager.updateViewLayout(overlayView, params)
                        touchX = event.x
                        touchY = event.y
                    }
                }
                MotionEvent.ACTION_UP -> {
                    isDragging = false
                }
            }
            true // 消费事件
        }

        // 配置悬浮窗参数
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = android.view.Gravity.START or android.view.Gravity.TOP
        params.x = 100 // 初始位置 X
        params.y = 100 // 初始位置 Y
        
        windowManager.addView(overlayView, params)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }
}
