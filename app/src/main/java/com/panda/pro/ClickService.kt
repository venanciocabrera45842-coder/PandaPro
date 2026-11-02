package com.panda.pro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Servicio de accesibilidad: es el que realmente toca la pantalla.
 * El panel flotante (OverlayService) le pide los toques a través de [tap].
 */
class ClickService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ClickService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No necesitamos leer eventos; solo emitir toques.
    }

    override fun onInterrupt() {}

    fun tap(x: Float, y: Float, durationMs: Long = 40L, onDone: ((Boolean) -> Unit)? = null) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onDone?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onDone?.invoke(false)
                }
            },
            null
        )
    }
}
