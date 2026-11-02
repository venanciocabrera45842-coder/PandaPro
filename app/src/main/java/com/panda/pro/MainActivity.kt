package com.panda.pro

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("panda", MODE_PRIVATE) }
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }
    private val fields = mutableMapOf<String, EditText>()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(0xFF0F0F1A.toInt())
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply {
            text = "🐼 Panda Pro"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFB0BEC5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(status)

        root.addView(card("1 · Permisos", 0xFF7C4DFF.toInt()).apply {
            addView(btn("🪟 Superposición (dibujar encima)", 0xFF5E35B1.toInt()) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            })
            addView(btn("♿ Accesibilidad (tocar pantalla)", 0xFF5E35B1.toInt()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        })

        root.addView(card("2 · Control", 0xFF00BFA5.toInt()).apply {
            addView(btn("▶ Iniciar panel flotante", 0xFF00897B.toInt()) {
                startForegroundService(Intent(this@MainActivity, OverlayService::class.java))
            })
            addView(btn("⏹ Cerrar panel", 0xFFE53935.toInt()) {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
            })
        })

        root.addView(card("3 · Ajustes", 0xFFFF4081.toInt()).apply {
            addField(this, "Intervalo autoclick (ms)", "interval", 300)
            addField(this, "Repeticiones (0 = infinito)", "repeats", 0)
            addField(this, "Pausa entre puntos de macro (ms)", "mdelay", 500)
            addView(btn("💾 Guardar ajustes", 0xFFFF4081.toInt()) { saveSettings() })
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val accOk = enabled.contains(ComponentName(this, ClickService::class.java).flattenToString())
        status.text = "Superposición: ${if (overlayOk) "✅" else "❌"}    " +
            "Accesibilidad: ${if (accOk) "✅" else "❌"}"
    }

    private fun saveSettings() {
        val edit = prefs.edit()
        for ((key, et) in fields) {
            var v = et.text.toString().toIntOrNull() ?: 0
            if (key == "interval") v = v.coerceAtLeast(50)
            if (key == "mdelay") v = v.coerceAtLeast(100)
            edit.putInt(key, v)
        }
        edit.apply()
        beep()
        Toast.makeText(this, "Ajustes guardados", Toast.LENGTH_SHORT).show()
    }

    // ---------- Construcción de la UI ----------

    private fun card(title: String, accent: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(0xFF1C1B2E.toInt(), 18)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(16)) }
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 18f
                setTextColor(accent)
                setPadding(0, 0, 0, dp(6))
            })
        }

    private fun btn(label: String, color: Int, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            isAllCaps = false
            background = roundBg(color, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply { setMargins(0, dp(6), 0, dp(6)) }
            setOnClickListener {
                beep()
                action()
            }
        }

    private fun addField(parent: LinearLayout, label: String, key: String, def: Int) {
        parent.addView(TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, dp(2))
        })
        val et = EditText(this).apply {
            setText(prefs.getInt(key, def).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            background = roundBg(0x33FFFFFF, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        fields[key] = et
        parent.addView(et)
    }

    private fun roundBg(color: Int, radiusDp: Int) =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
        }

    private fun beep() {
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
