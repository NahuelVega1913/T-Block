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
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Button
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Calendar



class AppMonitorService : AccessibilityService() {

    private val TAG = "T-Block-Monitor"
    private var lastBlockedPackage: String? = null
    private var lastBlockTime = 0L // ✅ AGREGAR ESTA LÍNEA

    private val handler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var countdown = 5
    private var countdownRunnable: Runnable? = null
    private var windowManager: WindowManager? = null
    private var isOverlayShowing = false

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
    private fun setupCountdown(button: Button?) {
        if (button == null) return

        button.isEnabled = false
        button.alpha = 0.5f
        countdown = 5

        val originalText = button.text.toString()
        button.text = "$originalText ($countdown)"

        countdownRunnable = object : Runnable {
            override fun run() {
                countdown--

                if (countdown > 0) {
                    button.text = "$originalText ($countdown)"
                    handler.postDelayed(this, 1000)
                } else {
                    button.isEnabled = true
                    button.alpha = 1.0f
                    button.text = originalText
                    button.setOnClickListener {
                        removeBlockOverlay()
                    }
                }
            }
        }

        handler.postDelayed(countdownRunnable!!, 1000)
    }

    private fun removeBlockOverlay() {
        countdownRunnable?.let { handler.removeCallbacks(it) }

        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
                overlayView = null
                isOverlayShowing = false
                Log.d(TAG, "🛑 Overlay removido")
            } catch (e: Exception) {
                Log.e(TAG, "Error removiendo overlay: ${e.message}")
            }
        }

        // Ir al home
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(home)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "🚀 ACCESSIBILITY SERVICE CONECTADO")
        Log.d(TAG, "📱 Package: $myPackageName")

        // ✅ Inicializar WindowManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        try {
            serviceInfo = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS // ✅ Para overlay
                notificationTimeout = 100
            }
            servicioInicializado = true
            Log.d(TAG, "✅ Servicio listo - PROTECCIÓN ACTIVA")

            // Registrar receiver
            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addDataScheme("package")
                }
                registerReceiver(packageReceiver, filter)
                Log.d(TAG, "✅ Package receiver registrado")
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
    private fun showBlockOverlay(appName: String, isProtection: Boolean) {
        if (overlayView != null) {
            Log.d(TAG, "⏭️ Overlay ya existe, no recreando")
            return
        }

        try {
            // ✅ TYPE_ACCESSIBILITY_OVERLAY - El más privilegiado, se muestra sobre TODO
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                // ✅ Flags para que el overlay esté sobre TODO
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            )

            params.gravity = Gravity.FILL
            params.x = 0
            params.y = 0

            val inflater = LayoutInflater.from(this)

            overlayView = if (isProtection) {
                inflater.inflate(R.layout.activity_proteccion_simple, null)
            } else {
                inflater.inflate(R.layout.activity_block_overlay_simple, null)
            }

            overlayView?.let { view ->
                // ✅ Configurar fullscreen inmersivo
                view.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        )

                // ✅ Capturar todos los toques
                view.setOnTouchListener { _, event ->
                    true // Consumir todos los eventos
                }

                if (isProtection) {
                    val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
                    val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)
                    val diasRemaining = if (fechaFin > System.currentTimeMillis()) {
                        ((fechaFin - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt() + 1
                    } else {
                        0
                    }

                    view.findViewById<TextView>(R.id.dias_restantes)?.text = "$diasRemaining días"
                    val btnCerrar = view.findViewById<Button>(R.id.btn_cerrar)
                    setupCountdown(btnCerrar)

                } else {
                    view.findViewById<TextView>(R.id.app_name_blocked)?.text = appName
                    val btnHome = view.findViewById<Button>(R.id.btn_home)
                    setupCountdown(btnHome)
                }

                windowManager?.addView(view, params)
                isOverlayShowing = true
                Log.d(TAG, "✅ Overlay TYPE_ACCESSIBILITY mostrado (sobre notificaciones)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error mostrando overlay: ${e.message}")
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
            if (CRITICAL_SYSTEM_PACKAGES.contains(pkg)) return

            val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
            val blocked = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()

            if (!blocked.contains(pkg)) return

            val scheduledUnlock = prefs.getInt("horario_desbloqueo_$pkg", -1)
            val now = Calendar.getInstance()
            val currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            if (UnlockSchedule.isWithinWindow(scheduledUnlock, currentMinute)) return

            if (pkg.contains("settings", ignoreCase = true)) {
                val currentTime = System.currentTimeMillis()
                if (pkg == lastBlockedPackage && (currentTime - lastBlockTime) < 2000L) return

                lastBlockedPackage = pkg
                lastBlockTime = currentTime

                performGlobalAction(GLOBAL_ACTION_HOME)

                handler.postDelayed({
                    showBlockOverlay("Ajustes", false)
                }, 300)

                handler.postDelayed({
                    lastBlockedPackage = null
                    lastBlockTime = 0L
                }, 5000L)

                return
            }

            val currentTime = System.currentTimeMillis()
            if (pkg == lastBlockedPackage && (currentTime - lastBlockTime) < 3000L) return

            lastBlockedPackage = pkg
            lastBlockTime = currentTime

            val pm = packageManager
            val appName = try {
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                pkg
            }

            showBlockOverlay(appName, false)

            handler.postDelayed({
                lastBlockedPackage = null
                lastBlockTime = 0L
            }, 5000L)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en verificarBloqueApp: ${e.message}")
        }
    }

    private fun mostrarProteccionFullScreen() {
        try {
            val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
            val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)
            if (fechaFin <= System.currentTimeMillis()) return

            showBlockOverlay("", true)
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

        overlayView?.let { view ->
            windowManager?.removeView(view)
            overlayView = null
        }

        try {
            unregisterReceiver(packageReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
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

