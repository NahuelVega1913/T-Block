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

    // ✅ RECEIVER DINÁMICO PARA NUEVAS APPS
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "🎯 PACKAGE RECEIVER LLAMADO")

            if (context == null || intent == null) {
                Log.e(TAG, "❌ Context o Intent nulo")
                return
            }

            Log.d(TAG, "📋 Action: ${intent.action}")

            if (intent.action != Intent.ACTION_PACKAGE_ADDED) {
                return
            }

            // Filtrar actualizaciones
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                Log.d(TAG, "⏭️ Es actualización, no instalación nueva")
                return
            }

            val pkg = intent.data?.schemeSpecificPart
            Log.d(TAG, "📦 Nueva app instalada: $pkg")

            if (pkg == null || pkg == myPackageName) {
                return
            }

            try {
                val prefs = context.getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
                val bloqueosHabilitado = prefs.getBoolean("bloquear_recien", false)

                Log.d(TAG, "🔍 bloquear_recien = $bloqueosHabilitado")

                if (!bloqueosHabilitado) {
                    Log.d(TAG, "❌ Función deshabilitada")
                    return
                }

                // Obtener lista actual
                val blocked = prefs.getStringSet("blocked_apps", emptySet())?.toMutableSet()
                    ?: mutableSetOf()

                Log.d(TAG, "📊 Apps bloqueadas actuales: ${blocked.size}")

                if (blocked.contains(pkg)) {
                    Log.d(TAG, "⚠️ $pkg ya estaba bloqueada")
                    return
                }

                // AGREGAR
                blocked.add(pkg)
                val success = prefs.edit()
                    .putStringSet("blocked_apps", HashSet(blocked))
                    .commit()

                if (success) {
                    Log.d(TAG, "✅✅✅ APP AGREGADA AUTOMÁTICAMENTE: $pkg")
                    Log.d(TAG, "📊 Total bloqueadas ahora: ${blocked.size}")

                    // Mostrar notificación al usuario
                    handler.post {
                        Toast.makeText(
                            applicationContext,
                            "Nueva app bloqueada: $pkg",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Log.e(TAG, "❌ FALLO al guardar en SharedPreferences")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error en packageReceiver: ${e.message}", e)
            }
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
                        AccessibilityEvent.TYPE_VIEW_CLICKED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                notificationTimeout = 100
            }
            servicioInicializado = true
            Log.d(TAG, "✅ Servicio listo - PROTECCIÓN ACTIVA")

            // ✅ REGISTRAR RECEIVER DINÁMICAMENTE
            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addDataScheme("package")
                }
                registerReceiver(packageReceiver, filter)
                Log.d(TAG, "✅ Package receiver registrado dinámicamente")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error registrando receiver: ${e.message}")
            }

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
                    if (KNOWN_LAUNCHERS.contains(packageName)) {
                        verificarProteccionEnLauncher()
                    }
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    detectarIntentDesinstalacion(event)
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    detectarClickDesinstalar(event)
                    detectarMenuDesinstalacion(event)
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

            Log.d(TAG, "🔍 Verificando app: $pkg")
            Log.d(TAG, "📋 Apps bloqueadas en lista: ${blocked.size}")

            if (!blocked.contains(pkg)) {
                return
            }

            if (pkg == lastBlockedPackage) {
                return
            }

            lastBlockedPackage = pkg
            Log.d(TAG, "🚫 ¡¡¡ APP BLOQUEADA: $pkg !!!")

            val pm = packageManager
            val appName = try {
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                pkg
            }

            // Mostrar overlay de bloqueo
            try {
                val intent = Intent(this, BlockOverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("blocked_package", appName)
                }
                startActivity(intent)
                Log.d(TAG, "✅ BlockOverlayActivity iniciada para: $appName")
            } catch (ex: Exception) {
                Log.e(TAG, "Error iniciando BlockOverlayActivity: ${ex.message}")
            }

            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ lastBlockedPackage = null }, 2000L)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en verificarBloqueApp: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun mostrarProteccionFullScreen() {
        try {
            val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
            val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)
            if (fechaFin <= System.currentTimeMillis()) return

            Log.d(TAG, "📺 Mostrando ProteccionActivity")
            val intent = Intent(this, ProteccionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
                Log.d(TAG, "⚠️ Detectado intento de desinstalación")
                verificarProteccionEnLauncher()
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
                    Log.d(TAG, "⚠️ Detectado menú de desinstalación")
                    verificarProteccionEnLauncher()
                    break
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
                Log.d(TAG, "⚠️ Click en botón desinstalar")
                verificarProteccionEnLauncher()
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

        // ✅ DESREGISTRAR RECEIVER
        try {
            unregisterReceiver(packageReceiver)
            Log.d(TAG, "✅ Package receiver desregistrado")
        } catch (e: Exception) {
            Log.e(TAG, "Error desregistrando receiver: ${e.message}")
        }

        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "🛑 Servicio destruido")
    }
}

// Las demás clases (ProteccionActivity, etc.) van aquí igual que antes...
class ProteccionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("ProteccionActivity", "🔒 Activity creada")

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
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }

        setFinishOnTouchOutside(false)

        try {
            setContentView(R.layout.activity_proteccion)
        } catch (e: Exception) {
            Log.e("ProteccionActivity", "Error cargando XML: ${e.message}")
            createFallbackUI()
            return
        }

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
                val home = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(home)
                finish()
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
        Log.d("ProteccionActivity", "📱 En pausa pero visible")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("ProteccionActivity", "🛑 Activity destruida")
    }
}
