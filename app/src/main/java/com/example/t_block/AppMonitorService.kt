package com.example.t_block

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.content.Intent
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.Log
import android.view.WindowManager
import android.view.Gravity
import android.view.View
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Button
import android.view.ViewGroup
import android.view.MotionEvent

class AppMonitorService : AccessibilityService() {

    private var lastBlockedPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())

    // overlay view y window manager
    private var overlayView: View? = null
    private val overlayTag = "tblock_overlay"

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AppMonitorService", "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val t = event.eventType
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val pkgObj = event.packageName ?: return
        val pkg = pkgObj.toString()

        if (pkg == applicationContext.packageName) return

        try {
            val prefs = applicationContext.getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
            val blocked = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()

            if (blocked.isEmpty()) return

            if (blocked.contains(pkg)) {
                if (pkg == lastBlockedPackage) return
                lastBlockedPackage = pkg

                // Si tenemos permiso de overlay, mostrar overlay desde el servicio
                if (Settings.canDrawOverlays(applicationContext)) {
                    showOverlay(pkg)
                    Log.d("AppMonitorService", "Overlay mostrado para $pkg")
                } else {
                    // fallback: intentar iniciar Activity (puede fallar en algunos dispositivos)
                    try {
                        val intent = Intent(applicationContext, BlockOverlayActivity::class.java).apply {
                            putExtra("blocked_package", pkg)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        applicationContext.startActivity(intent)
                        Log.d("AppMonitorService", "BlockOverlayActivity lanzada como fallback para $pkg")
                    } catch (tEx: Throwable) {
                        Log.w("AppMonitorService", "No se pudo abrir Activity de bloqueo: ${tEx.message}")
                        // informar al usuario que habilite permiso de overlays
                        handler.post {
                            Toast.makeText(applicationContext, "Permitir 'mostrar sobre otras apps' para bloquear correctamente", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                // permitir relanzar más tarde: limpiar lastBlockedPackage tras 1s
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ lastBlockedPackage = null }, 1000L)
            } else {
                if (lastBlockedPackage == pkg) lastBlockedPackage = null
                // si la app no está en lista y hay overlay visible, removerlo
                if (overlayView != null) {
                    removeOverlay()
                }
            }
        } catch (e: Exception) {
            Log.e("AppMonitorService", "error en onAccessibilityEvent: ${e.message}")
        }
    }

    override fun onInterrupt() {
        // limpiar overlay si servicio interrumpido
        removeOverlay()
    }

    private fun showOverlay(blockedPkg: String) {
        if (overlayView != null) return // ya mostrado

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // contenedor full‑screen
        val layout = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(220, 0, 0, 0)) // semitransparente/oscuro
            isClickable = true
            isFocusable = true
            tag = overlayTag
        }

        // Consumir todos los toques para bloquear interacción con la app debajo
        layout.setOnTouchListener { _, _ -> true }

        // mensaje central
        val tv = TextView(this).apply {
            text = "Acceso bloqueado\n$blockedPkg"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }

        // botón para ir al inicio (home) y quitar overlay
        val btn = Button(this).apply {
            text = "Ir al inicio"
            setOnClickListener {
                try {
                    val home = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(home)
                } catch (ignore: Exception) { }
                removeOverlay()
            }
        }

        // añadir views al layout
        val paramsTv = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER }
        layout.addView(tv, paramsTv)

        val paramsBtn = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = 120 }
        layout.addView(btn, paramsBtn)

        // LayoutParams para overlay
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Cambiado: usar flags que permitan recibir foco y bloquear interacción, y mantener pantalla encendida
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            wm.addView(layout, lp)
            overlayView = layout
            // solicitar foco para que capture input
            layout.isFocusableInTouchMode = true
            layout.requestFocus()
        } catch (e: Exception) {
            Log.w("AppMonitorService", "addView overlay fallo: ${e.message}")
            // si falla (permiso o restricción), intentar fallback a Activity
            try {
                val intent = Intent(applicationContext, BlockOverlayActivity::class.java).apply {
                    putExtra("blocked_package", blockedPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                applicationContext.startActivity(intent)
            } catch (_: Exception) { }
        }
    }

    private fun removeOverlay() {
        if (overlayView == null) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(overlayView)
        } catch (e: Exception) {
            Log.w("AppMonitorService", "removeView overlay fallo: ${e.message}")
        } finally {
            overlayView = null
        }
    }
}
