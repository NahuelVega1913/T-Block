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

    private val TAG = "T-BlockMonitor"
    private var lastBlockedPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var overlayArrastre: View? = null

    // Launchers conocidos
    private val KNOWN_LAUNCHERS = setOf(
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.motorola.launcher3",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.oneplus.launcher",
        "com.android.launcher",
        "com.teslacoilsw.launcher",
        "com.microsoft.launcher"
    )

    // Control de protección
    private var proteccionActivityVisible = false
    private var overlayView: View? = null
    private val overlayTag = "tblock_overlay"

    // Detección de desinstalación
    private var isInEditMode = false
    private var lastLongPressWasOnMyApp = false
    private var lastActivePackageBeforeLauncher: String? = null
    private var miAppVisibleEnLauncher = false
    private var ultimaVezMiAppVisible = 0L
    private val myPackageName by lazy { applicationContext.packageName }

    // Control de inicio
    private var servicioInicializado = false
    private val TIEMPO_ESPERA_INICIO = 5000L

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
        Log.d(TAG, "========================================")
        Log.d(TAG, "🚀 Servicio conectado")
        Log.d(TAG, "📱 Package: $myPackageName")
        Log.d(TAG, "🏷️  App Name: $myAppName")
        Log.d(TAG, "========================================")

        // Configurar el servicio de accesibilidad
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }

        servicioInicializado = false
        handler.postDelayed({
            servicioInicializado = true
            Log.d(TAG, "✅ Servicio completamente inicializado, protección activa")
        }, TIEMPO_ESPERA_INICIO)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !servicioInicializado) return

        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleWindowStateChanged(event)
                    detectarLauncherEditMode(event)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    handleWindowContentChanged(event)
                    if (isInEditMode || miAppVisibleEnLauncher) {
                        detectarMenuDesinstalacion()
                    }
                }
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                    detectarLongPressEnMiApp()
                }
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    if (miAppVisibleEnLauncher) {
                        handler.removeCallbacks(verificacionRapida)
                        handler.postDelayed(verificacionRapida, 50)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando evento: ${e.message}")
        }
    }

    private val verificacionRapida = Runnable {
        try {
            val rootNode = rootInActiveWindow ?: return@Runnable
            if (miAppVisibleEnLauncher) {
                verificarMenuDesinstalacionInmediato(rootNode)
            }
            rootNode.recycle()
        } catch (e: Exception) {
            Log.d(TAG, "Error verificación rápida: ${e.message}")
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val pkgObj = event.packageName ?: return
        val pkg = pkgObj.toString()

        if (pkg == myPackageName) return

        // Verificar protección contra desinstalación en launcher
        if (pkg in KNOWN_LAUNCHERS) {
            Log.d(TAG, "📱 Launcher detectado: $pkg")
            val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
            val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)
            if (fechaFin > System.currentTimeMillis()) {
                Log.d(TAG, "🔒 Protección activa -> mostrar pantalla")
                mostrarProteccionFullScreen()
            }
        } else {
            cerrarProteccionFullScreen()
        }

        // Detectar intent de desinstalación desde Ajustes
        if (event.className?.toString()?.contains("com.android.settings") == true) {
            verificarDesinstalacionEnAjustes()
        }

        // Lógica de bloqueo de apps
        verificarBloqueApp(pkg)
    }

    private fun handleWindowContentChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == myPackageName) return

        if (pkg in KNOWN_LAUNCHERS) {
            verificarSiMiAppEstaVisible()
        }

        verificarBloqueApp(pkg)
    }

    /**
     * 🔒 BLOQUEO DE APPS - Núcleo principal de BlockHero
     * Verifica si la app está en lista negra y muestra overlay
     */
    private fun verificarBloqueApp(pkg: String) {
        try {
            val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
            val blocked = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()

            // Si no hay apps bloqueadas, retornar
            if (blocked.isEmpty()) return

            // Si la app NO está bloqueada, limpiar overlay anterior
            if (!blocked.contains(pkg)) {
                if (lastBlockedPackage == pkg) lastBlockedPackage = null
                if (overlayView != null) removeOverlay()
                return
            }

            // App ESTÁ BLOQUEADA
            if (pkg == lastBlockedPackage) return // Ya bloqueada, no repetir
            lastBlockedPackage = pkg

            Log.d(TAG, "🚫 APP BLOQUEADA DETECTADA: $pkg")

            // Obtener nombre de la app
            val pm = packageManager
            val appName = try {
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                pkg
            }

            // Mostrar overlay o activity de bloqueo
            if (Settings.canDrawOverlays(applicationContext)) {
                showBlockOverlay(pkg, appName)
                Log.d(TAG, "📺 Overlay de bloqueo mostrado para $appName")
            } else {
                try {
                    val intent = Intent(applicationContext, BlockOverlayActivity::class.java).apply {
                        putExtra("blocked_package", appName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    applicationContext.startActivity(intent)
                    Log.d(TAG, "📱 BlockOverlayActivity lanzada para $appName")
                } catch (ex: Throwable) {
                    Log.w(TAG, "No se pudo mostrar bloqueo: ${ex.message}")
                    handler.post {
                        Toast.makeText(
                            applicationContext,
                            "Permitir 'mostrar sobre otras apps' en ajustes",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            // Limpiar después de 1 segundo
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ lastBlockedPackage = null }, 1000L)

        } catch (e: Exception) {
            Log.e(TAG, "Error en verificarBloqueApp: ${e.message}")
        }
    }

    /**
     * Mostrar overlay de bloqueo (similar a BlockHero)
     */
    private fun showBlockOverlay(blockedPkg: String, appName: String) {
        if (overlayView != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val layout = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(240, 20, 20, 20))
            isClickable = true
            isFocusable = true
            tag = overlayTag
        }

        layout.setOnTouchListener { _, _ -> true }

        // Icono de bloqueo
        val icono = TextView(this).apply {
            text = "🚫"
            textSize = 80f
            gravity = Gravity.CENTER
        }

        // Mensaje
        val mensaje = TextView(this).apply {
            text = "Aplicación bloqueada\n\n$appName"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
            typeface = Typeface.DEFAULT_BOLD
        }

        // Botón para ir al home
        val btnHome = Button(this).apply {
            text = "Volver al Inicio"
            setBackgroundColor(Color.argb(255, 200, 50, 50))
            setTextColor(Color.WHITE)
            setPadding(60, 30, 60, 30)
            textSize = 16f

            setOnClickListener {
                try {
                    val home = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(home)
                    removeOverlay()
                } catch (e: Exception) {
                    Log.e(TAG, "Error al ir al home: ${e.message}")
                }
            }
        }

        // Agregar vistas al layout
        val paramsIcon = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            topMargin = -150
        }
        layout.addView(icono, paramsIcon)

        val paramsMensaje = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        layout.addView(mensaje, paramsMensaje)

        val paramsBtn = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 150
        }
        layout.addView(btnHome, paramsBtn)

        // Agregar al window manager
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
            layout.isFocusableInTouchMode = true
            layout.requestFocus()
            Log.d(TAG, "✅ Overlay de bloqueo agregado")
        } catch (e: Exception) {
            Log.w(TAG, "Error agregando overlay: ${e.message}")
        }
    }

    /**
     * 🔒 PROTECCIÓN CONTRA DESINSTALACIÓN
     */
    private fun mostrarProteccionFullScreen() {
        if (proteccionActivityVisible) return

        val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
        val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)
        if (fechaFin <= System.currentTimeMillis()) return

        try {
            val intent = Intent(this, ProteccionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("source", "launcher_detected")
            }
            startActivity(intent)
            proteccionActivityVisible = true
            Log.d(TAG, "🔒 ProteccionActivity lanzada")
        } catch (e: Exception) {
            Log.e(TAG, "Error lanzando ProteccionActivity: ${e.message}")
        }
    }

    private fun cerrarProteccionFullScreen() {
        if (!proteccionActivityVisible) return
        try {
            val b = Intent("ACTION_CLOSE_PROTECCION_ACTIVITY")
            sendBroadcast(b)
            proteccionActivityVisible = false
        } catch (e: Exception) {
            Log.e(TAG, "Error cerrando protección: ${e.message}")
        }
    }

    private fun verificarDesinstalacionEnAjustes() {
        try {
            val rootNode = rootInActiveWindow ?: return

            // Buscar referencias a la app
            val nameNodes = rootNode.findAccessibilityNodeInfosByText(myPackageName)
            if (nameNodes.isEmpty()) {
                rootNode.recycle()
                return
            }

            val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
            val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)

            if (fechaFin > System.currentTimeMillis()) {
                Log.w(TAG, "⚠️ Intento de desinstalación desde Ajustes")
                val diasRestantes = ((fechaFin - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt() + 1
                mostrarBloqueoDesinstalacion(diasRestantes)
            }

            nameNodes.forEach { it.recycle() }
            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error verificando desinstalación: ${e.message}")
        }
    }

    private fun verificarSiMiAppEstaVisible() {
        try {
            val rootNode = rootInActiveWindow ?: return
            val appNodes = rootNode.findAccessibilityNodeInfosByText(myAppName)

            if (appNodes.isNotEmpty()) {
                if (!miAppVisibleEnLauncher) {
                    Log.d(TAG, "👁️ Mi app VISIBLE en launcher")
                }
                miAppVisibleEnLauncher = true
                ultimaVezMiAppVisible = System.currentTimeMillis()

                verificarMenuDesinstalacionInmediato(rootNode)
                appNodes.forEach { it.recycle() }
            } else {
                miAppVisibleEnLauncher = false
            }

            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error verificando visibilidad: ${e.message}")
        }
    }

    private fun verificarMenuDesinstalacionInmediato(rootNode: AccessibilityNodeInfo) {
        try {
            val uninstallKeywords = listOf(
                "Uninstall", "Desinstalar", "Remove", "Eliminar", "Delete", "Quitar"
            )

            for (keyword in uninstallKeywords) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
                if (nodes.isNotEmpty()) {
                    Log.w(TAG, "🔴 DETECCIÓN INMEDIATA: Menú '$keyword'")
                    nodes.forEach { it.recycle() }

                    if (!estaEnMenuRecientes(rootNode) && miAppVisibleEnLauncher) {
                        bloquearYVolverHome()
                    }
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error verificando menú: ${e.message}")
        }
    }

    private fun detectarLauncherEditMode(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        if (packageName in KNOWN_LAUNCHERS) {
            if (lastActivePackageBeforeLauncher == myPackageName) {
                Log.d(TAG, "⚠️ Volvimos al launcher desde nuestra app")
                lastLongPressWasOnMyApp = true

                handler.postDelayed({
                    verificarModoEdicion()
                    handler.postDelayed({
                        lastLongPressWasOnMyApp = false
                    }, 1000)
                }, 300)
            }
        } else if (packageName != myPackageName && packageName !in KNOWN_LAUNCHERS) {
            lastActivePackageBeforeLauncher = packageName
        }
    }

    private fun verificarModoEdicion() {
        try {
            val rootNode = rootInActiveWindow ?: return

            if (estaEnMenuRecientes(rootNode)) {
                rootNode.recycle()
                return
            }

            val editIndicators = listOf(
                "Desinstalar", "Eliminar", "Quitar", "Uninstall", "Remove", "Delete"
            )

            var foundUninstallMenu = false
            for (indicator in editIndicators) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(indicator)
                if (nodes.isNotEmpty()) {
                    foundUninstallMenu = true
                    nodes.forEach { it.recycle() }
                    break
                }
            }

            if (!foundUninstallMenu) {
                rootNode.recycle()
                return
            }

            if (miAppVisibleEnLauncher) {
                Log.w(TAG, "🔴 BLOQUEADO: Menú desinstalación detectado")
                rootNode.recycle()
                bloquearYVolverHome()
                return
            }

            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error en verificarModoEdicion: ${e.message}")
        }
    }

    private fun detectarLongPressEnMiApp() {
        if (!servicioInicializado) return

        try {
            val rootNode = rootInActiveWindow ?: return

            val appNodes = rootNode.findAccessibilityNodeInfosByText(myAppName)
            if (appNodes.isNotEmpty()) {
                lastLongPressWasOnMyApp = true
                isInEditMode = true
                Log.d(TAG, "🔴 Long press en nuestra app")

                mostrarOverlayArrastre()

                handler.postDelayed({
                    verificarModoEdicion()
                    removerOverlayArrastre()
                    isInEditMode = false
                    handler.postDelayed({
                        lastLongPressWasOnMyApp = false
                    }, 2000)
                }, 2000)

                appNodes.forEach { it.recycle() }
            }

            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error en long press: ${e.message}")
        }
    }

    private fun mostrarOverlayArrastre() {
        if (!Settings.canDrawOverlays(applicationContext) || overlayArrastre != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val layout = FrameLayout(applicationContext).apply {
            setBackgroundColor(Color.argb(240, 30, 30, 30))
            isClickable = true
            isFocusable = true
        }

        layout.setOnTouchListener { _, _ -> true }

        val mensaje = TextView(applicationContext).apply {
            text = "⚠️ PROTECCIÓN ACTIVA\n\nNo puedes mover ni desinstalar\nesta aplicación"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
            typeface = Typeface.DEFAULT_BOLD
        }

        val icono = TextView(applicationContext).apply {
            text = "🔒"
            textSize = 60f
            gravity = Gravity.CENTER
        }

        val botonHome = Button(applicationContext).apply {
            text = "Volver al Inicio"
            setBackgroundColor(Color.argb(255, 200, 50, 50))
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(60, 30, 60, 30)

            setOnClickListener {
                try {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    handler.postDelayed({
                        removerOverlayArrastre()
                    }, 300)
                } catch (e: Exception) {
                    Log.e(TAG, "Error en home: ${e.message}")
                }
            }
        }

        val paramsIcon = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            topMargin = -200
        }
        layout.addView(icono, paramsIcon)

        val paramsMensaje = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        layout.addView(mensaje, paramsMensaje)

        val paramsBtn = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 150
        }
        layout.addView(botonHome, paramsBtn)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            wm.addView(layout, params)
            overlayArrastre = layout
            layout.requestFocus()
            Log.d(TAG, "✅ Overlay de arrastre mostrado")
        } catch (e: Exception) {
            Log.e(TAG, "Error mostrando overlay: ${e.message}")
        }
    }

    private fun removerOverlayArrastre() {
        if (overlayArrastre == null) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(overlayArrastre)
            Log.d(TAG, "✅ Overlay de arrastre removido")
        } catch (e: Exception) {
            Log.e(TAG, "Error removiendo overlay: ${e.message}")
        } finally {
            overlayArrastre = null
        }
    }

    private fun estaEnMenuRecientes(rootNode: AccessibilityNodeInfo): Boolean {
        try {
            val indicators = listOf(
                "Clear all", "Cerrar todo", "Borrar todo", "Screenshot",
                "Split screen", "App info", "Select"
            )

            for (indicator in indicators) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(indicator)
                if (nodes.isNotEmpty()) {
                    nodes.forEach { it.recycle() }
                    return true
                }
            }

            return buscarClaseRecents(rootNode)
        } catch (e: Exception) {
            Log.e(TAG, "Error verificando Recientes: ${e.message}")
            return false
        }
    }

    private fun buscarClaseRecents(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        try {
            val className = node.className?.toString() ?: ""

            if (className.contains("Overview", ignoreCase = true) ||
                className.contains("Recents", ignoreCase = true) ||
                className.contains("RecentTasks", ignoreCase = true) ||
                className.contains("TaskView", ignoreCase = true)) {
                return true
            }

            for (i in 0 until node.childCount) {
                if (buscarClaseRecents(node.getChild(i))) {
                    return true
                }
            }
        } catch (e: Exception) {
            // Ignorar
        }

        return false
    }

    private fun detectarMenuDesinstalacion() {
        try {
            val rootNode = rootInActiveWindow ?: return

            val uninstallKeywords = listOf(
                "Desinstalar", "Uninstall", "Eliminar", "Remove", "Info de la app", "App info"
            )

            for (keyword in uninstallKeywords) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
                if (nodes.isNotEmpty()) {
                    if (esMiAppEnPeligro(rootNode)) {
                        Log.w(TAG, "Menú de desinstalación detectado")
                        bloquearYVolverHome()
                        nodes.forEach { it.recycle() }
                        rootNode.recycle()
                        return
                    }
                    nodes.forEach { it.recycle() }
                }
            }

            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error detectando menú: ${e.message}")
        }
    }

    private fun esMiAppEnPeligro(rootNode: AccessibilityNodeInfo): Boolean {
        try {
            val nameNodes = rootNode.findAccessibilityNodeInfosByText(myAppName)
            if (nameNodes.isNotEmpty()) {
                nameNodes.forEach { it.recycle() }
                return true
            }

            val packageNodes = rootNode.findAccessibilityNodeInfosByText(myPackageName)
            if (packageNodes.isNotEmpty()) {
                packageNodes.forEach { it.recycle() }
                return true
            }

            return buscarEnNodos(rootNode)
        } catch (e: Exception) {
            Log.e(TAG, "Error verificando app: ${e.message}")
            return false
        }
    }

    private fun buscarEnNodos(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        try {
            val desc = node.contentDescription?.toString()
            if (desc != null && (desc.contains(myPackageName) || desc.contains(myAppName))) {
                return true
            }

            val text = node.text?.toString()
            if (text != null && (text.equals(myAppName, ignoreCase = true) || text.contains(myPackageName))) {
                return true
            }

            for (i in 0 until node.childCount) {
                if (buscarEnNodos(node.getChild(i))) {
                    return true
                }
            }
        } catch (e: Exception) {
            // Ignorar
        }

        return false
    }

    private fun bloquearYVolverHome() {
        val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
        val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)
        val ahora = System.currentTimeMillis()

        if (fechaFin <= ahora) {
            Log.w(TAG, "⚠️ Protección no activa")
            return
        }

        try {
            Log.d(TAG, "🏠 Volviendo a inicio")
            performGlobalAction(GLOBAL_ACTION_HOME)

            lastLongPressWasOnMyApp = false
            miAppVisibleEnLauncher = false

            handler.postDelayed({
                val diasRestantes = ((fechaFin - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt() + 1
                mostrarBloqueoDesinstalacion(diasRestantes)
            }, 150)
        } catch (e: Exception) {
            Log.e(TAG, "Error en bloqueo: ${e.message}")
        }
    }

    private fun mostrarBloqueoDesinstalacion(diasRestantes: Int) {
        try {
            Log.d(TAG, "Mostrando bloqueo desinstalación: $diasRestantes días")

            if (Settings.canDrawOverlays(applicationContext)) {
                showBlockOverlay("TBlock", "Protección Activa - $diasRestantes días")
            } else {
                val intent = Intent(applicationContext, BlockOverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("blocked_package", "TBlock")
                    putExtra("is_uninstall_attempt", true)
                    putExtra("dias_restantes", diasRestantes)
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error mostrando bloqueo: ${e.message}")
        }
    }

    private fun removeOverlay() {
        if (overlayView == null) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(overlayView)
            overlayView = null
        } catch (e: Exception) {
            Log.w(TAG, "Error removiendo overlay: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Servicio interrumpido")
        removeOverlay()
        removerOverlayArrastre()
    }

    override fun onDestroy() {
        super.onDestroy()
        removerOverlayArrastre()
        removeOverlay()
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "🛑 Servicio destruido")
    }
}
class ProteccionActivity : Activity() {

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == "ACTION_CLOSE_PROTECCION_ACTIVITY") finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bandera: mostrar encima de lock screen / encender pantalla
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Configurar ventana full-screen y bloquear interacción con el launcher debajo
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        // Layout simple: fondo semitransparente + mensaje + botón para salir
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            isClickable = true
            isFocusable = true
        }

        val tv = TextView(this).apply {
            text = "Protección activa\nNo se puede modificar la posición ni desinstalar."
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(40, 40, 40, 40)
        }

        val btn = Button(this).apply {
            text = "Cerrar protección"
            setOnClickListener {
                // opcional: requerir PIN aquí antes de finish()
                finish()
            }
        }

        val paramsTv = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }

        val paramsBtn = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = 120 }

        root.addView(tv, paramsTv)
        root.addView(btn, paramsBtn)

        setContentView(root)

        // registrar receptor para cierre desde el servicio
        //registerReceiver(closeReceiver, IntentFilter("ACTION_CLOSE_PROTECCION_ACTIVITY"))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(closeReceiver) } catch (_: Exception) {}

    }

   // override fun onBackPressed() {
        // bloquear back o permit? acá lo bloqueamos para impedir salir fácilmente
        // para permitir, comentar la siguiente línea
     //    super.onBackPressed()
    //}
}
