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
    private var lastBlockTime = 0L // ✅ AGREGAR ESTA LÍNEA

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

    private val CRITICAL_SYSTEM_PACKAGES = setOf(
        "com.android.systemui",           // UI del sistema
        "android",                         // Proceso del sistema Android
        "com.android.settings.intelligence", // Inteligencia de ajustes
        "com.google.android.permissioncontroller" // Controlador de permisos
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

    // ✅ RECEIVER DINÁMICO PARA NUEVAS APPS (AGREGADO)
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
                        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                notificationTimeout = 100
            }
            servicioInicializado = true
            Log.d(TAG, "✅ Servicio listo - PROTECCIÓN ACTIVA")

            // ✅ REGISTRAR RECEIVER DINÁMICAMENTE (AGREGADO)
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

            // ✅ No bloquear paquetes críticos del sistema
            if (CRITICAL_SYSTEM_PACKAGES.contains(pkg)) {
                Log.d(TAG, "⏭️ Paquete crítico del sistema, no bloqueando: $pkg")
                return
            }

            val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
            val blocked = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()

            if (!blocked.contains(pkg)) {
                return
            }

            // ✅ Manejo especial para Settings/Ajustes
            if (pkg.contains("settings", ignoreCase = true)) {
                Log.d(TAG, "⚙️ Detectado intento de abrir Ajustes bloqueado")

                // Prevenir llamadas repetidas
                val currentTime = System.currentTimeMillis()
                if (pkg == lastBlockedPackage && (currentTime - lastBlockTime) < 2000L) {
                    Log.d(TAG, "⏭️ Ya se bloqueó recientemente: $pkg")
                    return
                }

                lastBlockedPackage = pkg
                lastBlockTime = currentTime

                // ✅ Cerrar Settings inmediatamente y mostrar overlay
                try {
                    // Simular botón HOME para salir de Settings
                    performGlobalAction(GLOBAL_ACTION_HOME)

                    // Esperar un momento y mostrar overlay
                    handler.postDelayed({
                        val pm = packageManager
                        val appName = try {
                            val ai = pm.getApplicationInfo(pkg, 0)
                            pm.getApplicationLabel(ai).toString()
                        } catch (e: Exception) {
                            "Ajustes"
                        }

                        val intent = Intent(this, BlockOverlayService::class.java).apply {
                            putExtra("blocked_package", appName)
                            putExtra("overlay_type", "block")
                        }
                        startService(intent)
                        Log.d(TAG, "✅ BlockOverlayService iniciado para Ajustes")
                    }, 300) // 300ms de delay

                } catch (ex: Exception) {
                    Log.e(TAG, "Error bloqueando Ajustes: ${ex.message}")
                }

                handler.postDelayed({
                    lastBlockedPackage = null
                    lastBlockTime = 0L
                }, 5000L)

                return
            }

            // ✅ Bloqueo normal para apps que no son Settings
            val currentTime = System.currentTimeMillis()
            if (pkg == lastBlockedPackage && (currentTime - lastBlockTime) < 3000L) {
                Log.d(TAG, "⏭️ Ya se bloqueó recientemente: $pkg")
                return
            }

            lastBlockedPackage = pkg
            lastBlockTime = currentTime
            Log.d(TAG, "🚫 ¡¡¡ APP BLOQUEADA: $pkg !!!")

            val pm = packageManager
            val appName = try {
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                pkg
            }

            try {
                val intent = Intent(this, BlockOverlayService::class.java).apply {
                    putExtra("blocked_package", appName)
                    putExtra("overlay_type", "block")
                }
                startService(intent)
                Log.d(TAG, "✅ BlockOverlayService iniciado")
            } catch (ex: Exception) {
                Log.e(TAG, "Error iniciando BlockOverlayService: ${ex.message}")
            }

            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({
                lastBlockedPackage = null
                lastBlockTime = 0L
            }, 5000L)

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

            Log.d(TAG, "📺 Mostrando Protección Overlay")
            val intent = Intent(this, BlockOverlayService::class.java).apply {
                putExtra("overlay_type", "protection")
            }
            startService(intent)
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

        // ✅ DESREGISTRAR RECEIVER (AGREGADO)
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

class ProteccionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("ProteccionActivity", "🔒 Activity creada")

        setFinishOnTouchOutside(false)

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

        val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
        val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)
        val diasRemaining = if (fechaFin > System.currentTimeMillis()) {
            ((fechaFin - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt() + 1
        } else {
            0
        }

        // Intentar cargar XML simple primero
        try {
            setContentView(R.layout.activity_proteccion_simple)

            val diasRestantes = findViewById<TextView>(R.id.dias_restantes)
            val btnCerrar = findViewById<Button>(R.id.btn_cerrar)

            diasRestantes?.text = "$diasRemaining días"
            btnCerrar?.setOnClickListener { goHome() }

            Log.d("ProteccionActivity", "✅ XML simple cargado")
        } catch (e: Exception) {
            Log.e("ProteccionActivity", "Error con XML simple: ${e.message}")
            // Intentar con el XML complejo
            try {
                setContentView(R.layout.activity_proteccion)

                val diasRestantes = findViewById<TextView>(R.id.dias_restantes)
                val btnCerrar = findViewById<Button>(R.id.btn_cerrar)

                diasRestantes?.text = "$diasRemaining días"
                btnCerrar?.setOnClickListener { goHome() }

                Log.d("ProteccionActivity", "✅ XML complejo cargado")
            } catch (e2: Exception) {
                Log.e("ProteccionActivity", "Error con ambos XMLs: ${e2.message}")
            }
        }
    }

    private fun goHome() {
        Log.d("ProteccionActivity", "✅ Enviando al inicio")
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            startActivity(home)
            finish()
        } catch (e: Exception) {
            Log.e("ProteccionActivity", "Error: ${e.message}")
            finish()
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

    override fun onPause() {
        super.onPause()
        Log.d("ProteccionActivity", "📱 En pausa")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("ProteccionActivity", "🛑 Activity destruida")
    }
}

// Las demás clases (ProteccionActivity, etc.) van aquí igual que antes...

