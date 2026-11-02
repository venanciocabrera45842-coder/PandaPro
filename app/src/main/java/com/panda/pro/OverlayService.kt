package com.panda.pro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var panel: LinearLayout
    private lateinit var marker: View
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var markerParams: WindowManager.LayoutParams

    private val handler = Handler(Looper.getMainLooper())
    private val points = mutableListOf<Pair<Float, Float>>()
    private var running = false
    private var movable = false
    private var repeats = 0
    private var clicksLeft = 0
    private var macroIndex = 0
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }

    private val autoTick = Runnable { tickAuto() }
    private val macroStep = Runnable { stepMacro() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("panda", MODE_PRIVATE)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()

        if (!Settings.canDrawOverlays(this)) {
            toast("Falta el permiso de superposición")
            stopSelf()
            return
        }
        buildMarker()
        buildPanel()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { if (::panel.isInitialized) wm.removeView(panel) }
        runCatching { if (::marker.isInitialized) wm.removeView(marker) }
        if (::prefs.isInitialized) tone.release()
        super.onDestroy()
    }

    // ---------- Notificación (requerida para servicios en primer plano) ----------

    private fun startAsForeground() {
        val channelId = "panda_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "Panda Pro", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Panda Pro activo")
            .setContentText("Panel flotante en uso")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    // ---------- Marca de objetivo (el punto donde se toca) ----------

    private fun buildMarker() {
        marker = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99FF4081.toInt())
                setStroke(dp(2), Color.WHITE)
            }
        }
        markerParams = WindowManager.LayoutParams(
            dp(40), dp(40),
            overlayType(),
            // Empieza NO tocable: los toques atraviesan la marca hacia la app.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(200)
            y = dp(400)
        }
        makeDraggable(marker, markerParams)
        wm.addView(marker, markerParams)
    }

    private fun toggleMove() {
        movable = !movable
        markerParams.flags = if (movable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        wm.updateViewLayout(marker, markerParams)
        toast(if (movable) "Arrastra la marca" else "Marca fija")
    }

    // ---------- Panel flotante ----------

    private fun buildPanel() {
        val header = TextView(this).apply {
            text = "🐼 PANDA PRO  ⠿"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(10))
        }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = roundBg(0xF01E1B2E.toInt(), 20, strokeColor = 0xFF7C4DFF.toInt())
            addView(header)
            addView(btn("▶ Autoclick", 0xFFFF4081.toInt()) { toggleAuto() })
            addView(btn("＋ Agregar punto", 0xFF00BFA5.toInt()) { addPoint() })
            addView(btn("▶ Ejecutar macro", 0xFF7C4DFF.toInt()) { playMacro() })
            addView(btn("◎ Mover marca", 0xFFFF9100.toInt()) { toggleMove() })
            addView(btn("⏹ Detener", 0xFFE53935.toInt()) { stopAll() })
            addView(btn("🗑 Borrar puntos", 0xFF546E7A.toInt()) {
                points.clear()
                toast("Puntos borrados")
            })
            addView(btn("✕ Cerrar panel", 0xFF263238.toInt()) { stopSelf() })
        }

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }

        // Arrastrar el panel desde el encabezado.
        makeDraggable(header, panelParams, target = panel)
        wm.addView(panel, panelParams)
    }

    private fun makeDraggable(
        handle: View,
        params: WindowManager.LayoutParams,
        target: View = handle
    ) {
        var dx = 0f
        var dy = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX - params.x
                    dy = e.rawY - params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (e.rawX - dx).toInt()
                    params.y = (e.rawY - dy).toInt()
                    wm.updateViewLayout(target, params)
                }
            }
            true
        }
    }

    // ---------- Acciones ----------

    private fun centerXY(): Pair<Float, Float> =
        Pair(markerParams.x + dp(20).toFloat(), markerParams.y + dp(20).toFloat())

    private fun toggleAuto() {
        if (running) {
            stopAll()
            return
        }
        val svc = ClickService.instance
        if (svc == null) {
            noAccessibility()
            return
        }
        running = true
        repeats = prefs.getInt("repeats", 0)
        clicksLeft = repeats
        toast("Autoclick iniciado")
        tickAuto()
    }

    private fun tickAuto() {
        if (!running) return
        val svc = ClickService.instance ?: run {
            noAccessibility()
            return
        }
        val (x, y) = centerXY()
        svc.tap(x, y)

        if (repeats > 0) {
            clicksLeft--
            if (clicksLeft <= 0) {
                running = false
                toast("Autoclick terminado")
                return
            }
        }
        handler.postDelayed(autoTick, prefs.getInt("interval", 300).toLong())
    }

    private fun addPoint() {
        val (x, y) = centerXY()
        points.add(Pair(x, y))
        toast("Punto ${points.size} guardado")
    }

    private fun playMacro() {
        if (running) return
        if (points.isEmpty()) {
            toast("Primero agrega puntos con ＋")
            return
        }
        if (ClickService.instance == null) {
            noAccessibility()
            return
        }
        running = true
        macroIndex = 0
        toast("Macro en marcha")
        stepMacro()
    }

    private fun stepMacro() {
        if (!running) return
        if (macroIndex >= points.size) {
            running = false
            toast("Macro terminada")
            return
        }
        val svc = ClickService.instance ?: run {
            noAccessibility()
            return
        }
        val (x, y) = points[macroIndex]
        macroIndex++
        svc.tap(x, y)
        handler.postDelayed(macroStep, prefs.getInt("mdelay", 500).toLong())
    }

    private fun stopAll() {
        running = false
        handler.removeCallbacksAndMessages(null)
        toast("Detenido")
    }

    private fun noAccessibility() {
        running = false
        toast("Activa Accesibilidad en la app Panda Pro")
    }

    // ---------- Utilidades ----------

    private fun btn(label: String, color: Int, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            isAllCaps = false
            background = roundBg(color, 14)
            layoutParams = LinearLayout.LayoutParams(dp(210), dp(46)).apply {
                setMargins(0, dp(4), 0, dp(4))
            }
            setOnClickListener {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
                action()
            }
        }

    private fun roundBg(color: Int, radiusDp: Int, strokeColor: Int? = null) =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
