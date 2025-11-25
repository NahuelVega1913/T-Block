package com.example.t_block

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.view.accessibility.AccessibilityEvent
import android.content.Intent
import android.content.Context
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.Log
import android.view.WindowManager
import android.view.Gravity
import android.view.View
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Button
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo

class AppMonitorService : AccessibilityService() {

    private val TAG = "BlockHero-Monitor"
    private var lastBlockedPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null

    private val KNOWN_LAUNCHERS = setOf(
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.oneplus.launcher"
    )

    private val myPackageName by lazy { applicationContext.packageName }
    private var servicioInicializado = false
    private val TIEMPO_ESPERA_INICIO = 2000L

    private val myAppName: String by lazy {
        try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(myPackageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            "BlockHero"
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "🚀 ========== ACCESSIBILITY SERVICE CONECTADO ==========")
        Log.d(TAG, "📱 Package: $myPackageName")
        Log.d(TAG, "🏷️  App Name: $myAppName")

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }

        handler.postDelayed({
            servicioInicializado = true
            Log.d(TAG, "✅ Servicio listo - PROTECCIÓN ACTIVA")
        }, TIEMPO_ESPERA_INICIO)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !servicioInicializado) return

        try {
            val packageName = event.packageName?.toString() ?: return

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    if (packageName in KNOWN_LAUNCHERS) {
                        Log.d(TAG, "📱 Launcher detectado: $packageName")
                        verificarProteccionEnLauncher()
                    }
                    if (packageName.contains("settings", ignoreCase = true)) {
                        detectarIntentDesinstalacion()
                    }
                }

                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                    Log.d(TAG, "🔴 Long click detectado en: $packageName")
                    detectarLongClickEnMiApp()
                }

                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    detectarClickDesinstalar(event)
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (packageName in KNOWN_LAUNCHERS) {
                        detectarMenuDesinstalacion()
                    }
                }
            }

            verificarBloqueApp(packageName)

        } catch (e: Exception) {
            Log.e(TAG, "Error en onAccessibilityEvent: ${e.message}")
        }
    }

    private fun verificarProteccionEnLauncher() {
        try {
            val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
            val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)
            val ahora = System.currentTimeMillis()

            if (fechaFin > ahora) {
                Log.d(TAG, "🔒 Protección activa en launcher")
                mostrarProteccionFullScreen()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error verificando protección: ${e.message}")
        }
    }

    private fun verificarBloqueApp(pkg: String) {
        try {
            if (pkg == myPackageName) return

            val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
            val blocked = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()

            if (!blocked.contains(pkg)) {
                if (lastBlockedPackage == pkg) lastBlockedPackage = null
                if (overlayView != null) removeOverlay()
                return
            }

            if (pkg == lastBlockedPackage) return
            lastBlockedPackage = pkg

            Log.d(TAG, "🚫 APP BLOQUEADA DETECTADA: $pkg")

            val pm = packageManager
            val appName = try {
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                pkg
            }

            if (Settings.canDrawOverlays(applicationContext)) {
                showBlockOverlay(pkg, appName)
            } else {
                try {
                    val intent = Intent(applicationContext, BlockOverlayActivity::class.java).apply {
                        putExtra("blocked_package", appName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (ex: Exception) {
                    Log.e(TAG, "Error lanzando overlay: ${ex.message}")
                }
            }

            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ lastBlockedPackage = null }, 1000L)

        } catch (e: Exception) {
            Log.e(TAG, "Error en verificarBloqueApp: ${e.message}")
        }
    }

    private fun showBlockOverlay(blockedPkg: String, appName: String) {
        if (overlayView != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val layout = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(240, 20, 20, 20))
            isClickable = true
            isFocusable = true
        }

        layout.setOnTouchListener { _, _ -> true }

        val icono = TextView(this).apply {
            text = "🚫"
            textSize = 80f
            gravity = Gravity.CENTER
        }

        val mensaje = TextView(this).apply {
            text = "Aplicación bloqueada\n\n$appName"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
            typeface = Typeface.DEFAULT_BOLD
        }

        val btnHome = Button(this).apply {
            text = "Volver al Inicio"
            setBackgroundColor(Color.argb(255, 200, 50, 50))
            setTextColor(Color.WHITE)
            setPadding(60, 30, 60, 30)
            textSize = 16f

            setOnClickListener {
                try {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    removeOverlay()
                } catch (e: Exception) {
                    Log.e(TAG, "Error: ${e.message}")
                }
            }
        }

        layout.addView(icono, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            topMargin = -150
        })

        layout.addView(mensaje, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        })

        layout.addView(btnHome, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 150
        })

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

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
            Log.d(TAG, "✅ Overlay de bloqueo mostrado")
        } catch (e: Exception) {
            Log.e(TAG, "Error agregando overlay: ${e.message}")
        }
    }

    private fun mostrarProteccionFullScreen() {
        try {
            val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
            val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)
            if (fechaFin <= System.currentTimeMillis()) return

            val intent = Intent(this, ProteccionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            Log.d(TAG, "🔒 ProteccionActivity mostrada")
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
        }
    }

    private fun detectarIntentDesinstalacion() {
        try {
            val rootNode = rootInActiveWindow ?: return

            val keywords = listOf("Desinstalar", "Uninstall", "Remove", "Eliminar")
            for (keyword in keywords) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
                if (nodes.isNotEmpty()) {
                    Log.w(TAG, "⚠️ Intento de desinstalación detectado")
                    val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
                    val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)
                    if (fechaFin > System.currentTimeMillis()) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        mostrarProteccionFullScreen()
                    }
                    nodes.forEach { it.recycle() }
                    rootNode.recycle()
                    return
                }
            }

            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error detectando desinstalación: ${e.message}")
        }
    }

    private fun detectarLongClickEnMiApp() {
        try {
            val rootNode = rootInActiveWindow ?: return
            val appNodes = rootNode.findAccessibilityNodeInfosByText(myAppName)

            if (appNodes.isNotEmpty()) {
                Log.w(TAG, "🔴 Long click en mi app - bloqueando movimiento")
                performGlobalAction(GLOBAL_ACTION_BACK)
                mostrarProteccionFullScreen()
                appNodes.forEach { it.recycle() }
            }

            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
        }
    }

    private fun detectarMenuDesinstalacion() {
        try {
            val rootNode = rootInActiveWindow ?: return

            val keywords = listOf("Desinstalar", "Uninstall", "Remove", "Delete")
            for (keyword in keywords) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
                if (nodes.isNotEmpty()) {
                    Log.w(TAG, "🔴 Menú desinstalación detectado")
                    val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
                    val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)
                    if (fechaFin > System.currentTimeMillis()) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        mostrarProteccionFullScreen()
                    }
                    nodes.forEach { it.recycle() }
                    rootNode.recycle()
                    return
                }
            }

            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
        }
    }

    private fun detectarClickDesinstalar(event: AccessibilityEvent) {
        val text = event.text.joinToString(" ")
        if (text.contains("Desinstalar", ignoreCase = true) ||
            text.contains("Uninstall", ignoreCase = true)) {

            val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
            val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)
            if (fechaFin > System.currentTimeMillis()) {
                Log.w(TAG, "❌ Click en Desinstalar - BLOQUEADO")
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
    }

    private fun removeOverlay() {
        if (overlayView == null) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(overlayView)
            overlayView = null
        } catch (e: Exception) {
            Log.e(TAG, "Error removiendo overlay: ${e.message}")
        }
    }

    override fun onInterrupt() {
        removeOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "🛑 Servicio destruido")
    }
}
class ProteccionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )

        setFinishOnTouchOutside(false)
        setContentView(R.layout.activity_proteccion)

        // Elementos del XML
        val btnCerrar = findViewById<Button>(R.id.btn_cerrar)
        val diasRestantes = findViewById<TextView>(R.id.dias_restantes)

        val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
        val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)

        val diasRemaining = if (fechaFin > System.currentTimeMillis()) {
            ((fechaFin - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt() + 1
        } else {
            0
        }

        diasRestantes.text = "$diasRemaining días"

        btnCerrar.setOnClickListener {
            if (fechaFin <= System.currentTimeMillis()) {
                finish()
            }
        }
    }



    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    )
        }
    }
}
