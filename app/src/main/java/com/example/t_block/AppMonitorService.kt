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

    private val TAG = "T-Block-Monitor"
    private var lastBlockedPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())

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

    private val myAppName: String by lazy {
        try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(myPackageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            "T-Block"
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "🚀 ACCESSIBILITY SERVICE CONECTADO")
        Log.d(TAG, "📱 Package: $myPackageName")

        try {
            serviceInfo = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                notificationTimeout = 100
            }
            servicioInicializado = true
            Log.d(TAG, "✅ Servicio listo - PROTECCIÓN ACTIVA")
        } catch (e: Exception) {
            Log.e(TAG, "Error en onServiceConnected: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !servicioInicializado) return

        try {
            val packageName = event.packageName?.toString() ?: return

            // NO procesar eventos de nuestra propia app
            if (packageName == myPackageName) return

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    Log.d(TAG, "📱 Ventana cambió: $packageName")

                    // Detectar launcher
                    if (packageName in KNOWN_LAUNCHERS) {
                        verificarProteccionEnLauncher()
                    }

                    // Detectar Settings
                    if (packageName.contains("settings", ignoreCase = true)) {
                        detectarIntentDesinstalacion(event)
                    }
                }

                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                    Log.d(TAG, "🔴 Long click detectado en: $packageName")
                    // ✅ NO bloquear aquí - dejar que el sistema continúe
                    verificarProteccionEnLauncher()
                }

                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    detectarClickDesinstalar(event)
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (packageName in KNOWN_LAUNCHERS) {
                        detectarMenuDesinstalacion(event)
                    }
                }
            }

            // Verificar apps bloqueadas
            verificarBloqueApp(packageName)

        } catch (e: Exception) {
            Log.e(TAG, "Error en onAccessibilityEvent: ${e.message}")
            e.printStackTrace()
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
            Log.e(TAG, "Error en verificarProteccionEnLauncher: ${e.message}")
        }
    }

    private fun verificarBloqueApp(pkg: String) {
        try {
            if (pkg == myPackageName) return

            val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
            val blocked = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()

            if (!blocked.contains(pkg)) {
                lastBlockedPackage = null
                return
            }

            if (pkg == lastBlockedPackage) return
            lastBlockedPackage = pkg

            Log.d(TAG, "🚫 APP BLOQUEADA: $pkg")

            val pm = packageManager
            val appName = try {
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                pkg
            }

            // ✅ Mostrar overlay de bloqueo
            try {
                val intent = Intent(applicationContext, BlockOverlayActivity::class.java).apply {
                    putExtra("blocked_package", appName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
                Log.d(TAG, "✅ BlockOverlayActivity iniciada")
            } catch (ex: Exception) {
                Log.e(TAG, "Error lanzando BlockOverlay: ${ex.message}")
            }

            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ lastBlockedPackage = null }, 2000L)

        } catch (e: Exception) {
            Log.e(TAG, "Error en verificarBloqueApp: ${e.message}")
        }
    }

    private fun mostrarProteccionFullScreen() {
        try {
            val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
            val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)
            if (fechaFin <= System.currentTimeMillis()) return

            Log.d(TAG, "📺 Mostrando ProteccionActivity")
            val intent = Intent(this, ProteccionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error en mostrarProteccionFullScreen: ${e.message}")
        }
    }

    private fun detectarIntentDesinstalacion(event: AccessibilityEvent) {
        try {
            val text = event.text.joinToString(" ")

            if (text.contains("Desinstalar", ignoreCase = true) ||
                text.contains("Uninstall", ignoreCase = true)) {

                Log.w(TAG, "⚠️ Intento de desinstalación detectado")
                val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
                val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)

                if (fechaFin > System.currentTimeMillis()) {
                    Log.d(TAG, "❌ Bloqueando desinstalación")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    mostrarProteccionFullScreen()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en detectarIntentDesinstalacion: ${e.message}")
        }
    }

    private fun detectarMenuDesinstalacion(event: AccessibilityEvent) {
        try {
            val text = event.text.joinToString(" ")

            val keywords = listOf("Desinstalar", "Uninstall", "Remove", "Delete")
            for (keyword in keywords) {
                if (text.contains(keyword, ignoreCase = true)) {
                    Log.w(TAG, "🔴 Menú desinstalación detectado")
                    val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
                    val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)

                    if (fechaFin > System.currentTimeMillis()) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        mostrarProteccionFullScreen()
                    }
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en detectarMenuDesinstalacion: ${e.message}")
        }
    }

    private fun detectarClickDesinstalar(event: AccessibilityEvent) {
        try {
            val text = event.text.joinToString(" ")

            if (text.contains("Desinstalar", ignoreCase = true) ||
                text.contains("Uninstall", ignoreCase = true)) {

                Log.w(TAG, "❌ Click en Desinstalar")
                val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
                val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)

                if (fechaFin > System.currentTimeMillis()) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    mostrarProteccionFullScreen()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en detectarClickDesinstalar: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Servicio interrumpido")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "🛑 Servicio destruido")
    }
}
class ProteccionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("ProteccionActivity", "🔒 Activity creada")

        // Full screen configuration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        window.apply {
            addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
            decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    )
        }

        setFinishOnTouchOutside(false)

        // ✅ USAR XML DE LAYOUT
        try {
            setContentView(R.layout.activity_proteccion)
        } catch (e: Exception) {
            Log.e("ProteccionActivity", "Error cargando XML: ${e.message}")
            // Si falla, crear UI programáticamente
            createFallbackUI()
            return
        }

        // Referencias a los elementos del XML
        try {
            val btnCerrar = findViewById<Button>(R.id.btn_cerrar)
            val diasRestantes = findViewById<TextView>(R.id.dias_restantes)

            val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
            val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)

            val diasRemaining = if (fechaFin > System.currentTimeMillis()) {
                ((fechaFin - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt() + 1
            } else {
                0
            }

            diasRestantes?.text = "$diasRemaining días"

            btnCerrar?.setOnClickListener {
                if (fechaFin <= System.currentTimeMillis()) {
                    finish()
                } else {
                    Log.d("ProteccionActivity", "⏱️ Aún hay protección activa")
                }
            }
        } catch (e: Exception) {
            Log.e("ProteccionActivity", "Error vinculando elementos: ${e.message}")
        }
    }

    private fun createFallbackUI() {
        Log.w("ProteccionActivity", "⚠️ Usando UI fallback")
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isClickable = true
            isFocusable = true
        }
        setContentView(root)
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

    override fun onPause() {
        super.onPause()
        // Mantener la pantalla visible
        Log.d("ProteccionActivity", "📱 En pausa pero visible")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("ProteccionActivity", "🛑 Activity destruida")
    }
}
