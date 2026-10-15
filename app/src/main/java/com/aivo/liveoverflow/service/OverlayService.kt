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
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            overlayView = object : View(this) {
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    try {
                        // 画粉色圆球
                        val bodyPaint = Paint().apply {
                            color = Color.parseColor("#FF6B9D")
                            isAntiAlias = true
                            style = Paint.Style.FILL
                        }
                        // 防止宽高为0
                        val cx = (width / 2f).coerceAtLeast(40f)
                        val cy = (height / 2f).coerceAtLeast(40f)
                        canvas.drawCircle(cx, cy, 40f, bodyPaint)

                        // 画白色眼睛
                        val eyePaint = Paint().apply {
                            color = Color.WHITE
                            isAntiAlias = true
                            style = Paint.Style.FILL
                        }
                        canvas.drawCircle(cx - 15f, cy - 10f, 8f, eyePaint)
                        canvas.drawCircle(cx + 15f, cy - 10f, 8f, eyePaint)

                        // 画黑色瞳孔
                        val pupilPaint = Paint().apply {
                            color = Color.BLACK
                            isAntiAlias = true
                            style = Paint.Style.FILL
                        }
                        canvas.drawCircle(cx - 15f, cy - 10f, 4f, pupilPaint)
                        canvas.drawCircle(cx + 15f, cy - 10f, 4f, pupilPaint)
                        
                        // 画高光
                        val highlightPaint = Paint().apply {
                            color = Color.WHITE
                            isAntiAlias = true
                            style = Paint.Style.FILL
                        }
                        canvas.drawCircle(cx - 17f, cy - 12f, 2f, highlightPaint)
                        canvas.drawCircle(cx + 13f, cy - 12f, 2f, highlightPaint)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            overlayView.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isDragging = true
                        touchX = event.x
                        touchY = event.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isDragging) {
                            try {
                                val params = overlayView.layoutParams as WindowManager.LayoutParams
                                params.x += (event.rawX - touchX).toInt()
                                params.y += (event.rawY - touchY).toInt()
                                windowManager.updateViewLayout(overlayView, params)
                                touchX = event.x
                                touchY = event.y
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        isDragging = false
                    }
                }
                true
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            params.gravity = android.view.Gravity.START or android.view.Gravity.TOP
            params.x = 100
            params.y = 100
            
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (::overlayView.isInitialized) {
                windowManager.removeView(overlayView)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
