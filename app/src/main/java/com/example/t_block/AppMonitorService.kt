package com.example.t_block

import android.accessibilityservice.AccessibilityService
import android.app.Activity
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
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Button
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo

class AppMonitorService : AccessibilityService() {

    private val TAG = "AppMonitorService"
    private var lastBlockedPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var overlayArrastre: View? = null
    private val KNOWN_LAUNCHERS = setOf(
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.oneplus.launcher",
        "com.android.launcher",
        "com.teslacoilsw.launcher",
        "com.microsoft.launcher"
    )

    private var proteccionActivityVisible = false

    // overlay view y window manager
    private var overlayView: View? = null
    private val overlayTag = "tblock_overlay"

    // Variables para detectar desinstalación
    private var isInEditMode = false
    private var lastLongPressWasOnMyApp = false
    private var lastActivePackageBeforeLauncher: String? = null
    private var miAppVisibleEnLauncher = false
    private var ultimaVezMiAppVisible = 0L
    private val myPackageName by lazy { applicationContext.packageName }

    // Obtener el nombre real de la app dinámicamente
    private val myAppName: String by lazy {
        try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(myPackageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            "T-Block" // fallback
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        bloquearArrastre()
        Log.d(TAG, "========================================")
        Log.d(TAG, "🚀 Servicio conectado")
        Log.d(TAG, "📱 Package: $myPackageName")
        Log.d(TAG, "🏷️  App Name: $myAppName")
        Log.d(TAG, "========================================")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val t = event.eventType
        val evtType = event.eventType
        if (evtType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // --- NUEVO: detectar launcher en este punto ---
            if (pkg in KNOWN_LAUNCHERS) {
                Log.d(TAG, "Launcher detectado: $pkg -> solicitar pantalla de protección")
                mostrarProteccionFullScreen()
            } else {
                // si estamos en alguna otra app, cerrar si estaba abierto
                cerrarProteccionFullScreen()
            }
        }
        // Manejar diferentes tipos de eventos
        when (t) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
                detectarLauncherEditMode(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleWindowContentChanged(event)
                // Verificar también si hay menú mientras el contenido cambia
                if (isInEditMode || miAppVisibleEnLauncher) {
                    detectarMenuDesinstalacion()
                }
            }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                detectarLongPressEnMiApp()
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Verificación extra durante scroll o cambios de contenido
                // Esto ayuda a detectar cuando arrastran la app
                if (miAppVisibleEnLauncher) {
                    handler.removeCallbacks(verificacionRapida)
                    handler.postDelayed(verificacionRapida, 50)
                }
            }
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
            // Ignorar
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val pkgObj = event.packageName ?: return
        val pkg = pkgObj.toString()

        if (pkg == myPackageName) return

        // Detectar si está en Ajustes intentando desinstalar
        if (event.className != null && event.className.toString().contains("com.android.settings")) {
            val node = rootInActiveWindow
            if (node != null) {
                val texto = node.findAccessibilityNodeInfosByText(myPackageName)
                if (texto.isNotEmpty()) {
                    // Verificar si la protección está activa
                    val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
                    val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)
                    val ahora = System.currentTimeMillis()

                    if (fechaFin > ahora) {
                        Log.w(TAG, "Intento de desinstalación desde Ajustes detectado")
                        mostrarBloqueoDesinstalacion(calcularDiasRestantes(fechaFin))
                    }
                    texto.forEach { it.recycle() }
                }
                node.recycle()
            }
        }

        // Lógica original de bloqueo de apps
        verificarBloqueApp(pkg)
    }

    private fun handleWindowContentChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == myPackageName) return

        // Si estamos en el launcher, verificar si mi app está visible
        val launchers = listOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.oneplus.launcher",
            "com.android.launcher",
            "com.teslacoilsw.launcher",
            "com.microsoft.launcher"
        )

        if (pkg in launchers) {
            verificarSiMiAppEstaVisible()
        }

        // Lógica original de bloqueo de apps
        verificarBloqueApp(pkg)
    }
    private fun mostrarProteccionFullScreen() {
        // evita lanzar repetidamente
        if (proteccionActivityVisible) return

        // Verificar que la protección esté activa según tus prefs
        val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
        val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)
        if (fechaFin <= System.currentTimeMillis()) {
            Log.d(TAG, "Protección no activa -> no mostrar pantalla")
            return
        }

        try {
            val intent = Intent(this, ProteccionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                // extra opcional para saber quién la abrió
                putExtra("source", "launcher_detected")
            }
            startActivity(intent)
            proteccionActivityVisible = true
            Log.d(TAG, "ProteccionActivity lanzada")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo lanzar ProteccionActivity: ${e.message}")
        }
    }

    private fun cerrarProteccionFullScreen() {
        if (!proteccionActivityVisible) return
        try {
            // mandar broadcast a la Activity para que se cierre
            val b = Intent("ACTION_CLOSE_PROTECCION_ACTIVITY")
            sendBroadcast(b)
            proteccionActivityVisible = false
            Log.d(TAG, "Solicitada cierre de ProteccionActivity")
        } catch (e: Exception) {
            Log.e(TAG, "Error cerrando ProteccionActivity: ${e.message}")
        }
    }

    private fun verificarSiMiAppEstaVisible() {
        try {
            val rootNode = rootInActiveWindow ?: return

            // Buscar si mi app está visible en el launcher
            val appNodes = rootNode.findAccessibilityNodeInfosByText(myAppName)

            if (appNodes.isNotEmpty()) {
                if (!miAppVisibleEnLauncher) {
                    Log.d(TAG, "👁️ Mi app ahora VISIBLE en launcher - activando vigilancia")
                }
                miAppVisibleEnLauncher = true
                ultimaVezMiAppVisible = System.currentTimeMillis()

                // VERIFICACIÓN INMEDIATA: Buscar si hay menú de desinstalación AHORA
                verificarMenuDesinstalacionInmediato(rootNode)

                appNodes.forEach { it.recycle() }
            } else {
                if (miAppVisibleEnLauncher) {
                    Log.d(TAG, "🙈 Mi app ya NO visible en launcher")
                }
                miAppVisibleEnLauncher = false
            }

            rootNode.recycle()
        } catch (e: Exception) {
            // Ignorar errores
        }
    }

    private fun verificarMenuDesinstalacionInmediato(rootNode: AccessibilityNodeInfo) {
        try {
            // Si mi app está visible, buscar INMEDIATAMENTE si hay menú de desinstalación
            val uninstallKeywords = listOf(
                "Uninstall",
                "Desinstalar",
                "Remove",
                "Eliminar",
                "Delete",
                "Quitar"
            )

            for (keyword in uninstallKeywords) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
                if (nodes.isNotEmpty()) {
                    Log.w(TAG, "⚡ DETECCIÓN INMEDIATA: Mi app visible + '$keyword' en pantalla")
                    nodes.forEach { it.recycle() }

                    // Verificar que no sea menú de recientes
                    if (!estaEnMenuRecientes(rootNode)) {
                        Log.w(TAG, "🔴 BLOQUEANDO INMEDIATAMENTE")
                        bloquearYVolverHome()
                    }
                    return
                }
            }
        } catch (e: Exception) {
            // Ignorar
        }
    }

    private fun verificarBloqueApp(pkg: String) {
        try {
            val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
            val blocked = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()

            if (blocked.isEmpty()) return

            if (blocked.contains(pkg)) {
                if (pkg == lastBlockedPackage) return
                lastBlockedPackage = pkg

                // Si tenemos permiso de overlay, mostrar overlay desde el servicio
                if (Settings.canDrawOverlays(applicationContext)) {
                    showOverlay(pkg, false, 0)
                    Log.d(TAG, "Overlay mostrado para $pkg")
                } else {
                    // fallback: intentar iniciar Activity
                    try {
                        val intent = Intent(applicationContext, BlockOverlayActivity::class.java).apply {
                            putExtra("blocked_package", pkg)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        applicationContext.startActivity(intent)
                        Log.d(TAG, "BlockOverlayActivity lanzada como fallback para $pkg")
                    } catch (tEx: Throwable) {
                        Log.w(TAG, "No se pudo abrir Activity de bloqueo: ${tEx.message}")
                        handler.post {
                            Toast.makeText(
                                applicationContext,
                                "Permitir 'mostrar sobre otras apps' para bloquear correctamente",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

                // permitir relanzar más tarde
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
            Log.e(TAG, "error en verificarBloqueApp: ${e.message}")
        }
    }

    // ========== DETECCIÓN DE DESINSTALACIÓN ==========

    private fun detectarLauncherEditMode(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // Lista de launchers comunes
        val launchers = listOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.oneplus.launcher",
            "com.android.launcher",
            "com.teslacoilsw.launcher",
            "com.microsoft.launcher"
        )

        if (packageName in launchers) {
            Log.d(TAG, "📱 En launcher: $packageName")

            // Si acabamos de salir de nuestra app para ir al launcher, es sospechoso
            if (lastActivePackageBeforeLauncher == myPackageName) {
                Log.d(TAG, "⚠️ Sospechoso: volvimos al launcher desde nuestra app")
                lastLongPressWasOnMyApp = true

                // SOLO verificar modo edición si hubo transición sospechosa
                handler.postDelayed({
                    verificarModoEdicion()
                    // Resetear después de verificar
                    handler.postDelayed({
                        lastLongPressWasOnMyApp = false
                    }, 1000)
                }, 300)
            }
            // Si NO venimos de nuestra app, NO hacer nada (no verificar)
        } else {
            // Guardar el último paquete activo (que no sea launcher ni nosotros)
            if (packageName != myPackageName && !launchers.contains(packageName)) {
                lastActivePackageBeforeLauncher = packageName
            }
        }
    }

    private fun verificarModoEdicion() {
        try {
            val rootNode = rootInActiveWindow ?: return

            Log.d(TAG, "=== Verificando modo edición ===")

            // IMPORTANTE: Detectar si estamos en el menú de recientes/multitarea
            if (estaEnMenuRecientes(rootNode)) {
                Log.d(TAG, "📱 En menú de Recientes/Multitarea - NO bloquear")
                rootNode.recycle()
                return
            }

            // Palabras clave de desinstalación
            val editIndicators = listOf(
                "Desinstalar",
                "Eliminar",
                "Quitar",
                "Uninstall",
                "Remove",
                "Delete"
            )

            var foundUninstallMenu = false

            for (indicator in editIndicators) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(indicator)
                if (nodes.isNotEmpty()) {
                    Log.d(TAG, "Encontrado indicador: $indicator")
                    foundUninstallMenu = true
                    nodes.forEach { it.recycle() }
                    break
                }
            }

            if (!foundUninstallMenu) {
                rootNode.recycle()
                return
            }

            // ESTRATEGIA AGRESIVA: Si hay menú de desinstalación Y mi app está visible AHORA MISMO
            if (miAppVisibleEnLauncher) {
                Log.w(TAG, "🔴 BLOQUEADO INMEDIATO: Mi app visible + menú desinstalación")
                rootNode.recycle()
                bloquearYVolverHome()
                return
            }

            // ESTRATEGIA 2: Si mi app estuvo visible recientemente (menos de 3 segundos)
            val tiempoDesdeUltimaVez = System.currentTimeMillis() - ultimaVezMiAppVisible
            val miAppRecienteVisible = tiempoDesdeUltimaVez < 3000

            if (lastLongPressWasOnMyApp || miAppRecienteVisible) {
                Log.w(TAG, "🔴 BLOQUEADO: Mi app visible recientemente + menú de desinstalación")
                Log.d(TAG, "  - Tiempo desde última vez visible: ${tiempoDesdeUltimaVez}ms")
                rootNode.recycle()
                bloquearYVolverHome()
                return
            }

            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error en verificarModoEdicion", e)
        }
    }

    private fun estaEnMenuRecientes(rootNode: AccessibilityNodeInfo): Boolean {
        try {
            // Buscar indicadores del menú de recientes/multitarea
            val recentesIndicators = listOf(
                "Clear all",
                "Cerrar todo",
                "Borrar todo",
                "Screenshot",
                "Captura",
                "Split screen",
                "Pantalla dividida",
                "App info",
                "Select",
                "Seleccionar"
            )

            for (indicator in recentesIndicators) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(indicator)
                if (nodes.isNotEmpty()) {
                    Log.d(TAG, "✓ Detectado indicador de Recientes: $indicator")
                    nodes.forEach { it.recycle() }
                    return true
                }
            }

            // También verificar por className típico del overview/recents
            if (buscarClaseRecents(rootNode)) {
                return true
            }

            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error en estaEnMenuRecientes", e)
            return false
        }
    }

    private fun buscarClaseRecents(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        try {
            val className = node.className?.toString() ?: ""

            // Clases típicas del menú de recientes
            if (className.contains("Overview", ignoreCase = true) ||
                className.contains("Recents", ignoreCase = true) ||
                className.contains("RecentTasks", ignoreCase = true) ||
                className.contains("TaskView", ignoreCase = true)) {
                Log.d(TAG, "✓ Detectada clase de Recientes: $className")
                return true
            }

            // Buscar recursivamente
            for (i in 0 until node.childCount) {
                if (buscarClaseRecents(node.getChild(i))) {
                    return true
                }
            }
        } catch (e: Exception) {
            // Ignorar errores
        }

        return false
    }

    private fun detectarLongPressEnMiApp() {
        try {
            val rootNode = rootInActiveWindow ?: return

            Log.d(TAG, "========== LONG PRESS DETECTADO ==========")
            Log.d(TAG, "Buscando app: $myAppName")

            val appNodes = rootNode.findAccessibilityNodeInfosByText(myAppName)
            if (appNodes.isNotEmpty()) {
                lastLongPressWasOnMyApp = true
                isInEditMode = true
                Log.d(TAG, "🔴 ✅ Long press en nuestra app: $myAppName")

                // 🔹 Mostrar overlay transparente que bloquea interacción
                mostrarOverlayArrastre()

                // Después de unos segundos, verificar si sigue en modo edición
                handler.postDelayed({
                    Log.d(TAG, "⏰ Verificando modo edición después del long press...")
                    verificarModoEdicion()
                    removerOverlayArrastre() // 🔹 Quita el overlay cuando termina el intento
                    isInEditMode = false
                    handler.postDelayed({
                        lastLongPressWasOnMyApp = false
                    }, 2000)
                }, 1000)

                appNodes.forEach { it.recycle() }
            } else {
                lastLongPressWasOnMyApp = false
                removerOverlayArrastre()
                Log.d(TAG, "❌ Long press en OTRA app")
            }

            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error en detectarLongPressEnMiApp", e)
        }
    }
    private fun mostrarPopupProteccion() {
        val intent = Intent(this, ProteccionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error al mostrar protección: ${e.message}")
        }
    }

    private fun cerrarPopupProteccion() {
        sendBroadcast(Intent("CERRAR_PROTECCION"))
    }
    private fun removerOverlayArrastre() {
        if (overlayArrastre == null) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(overlayArrastre)
            Log.d(TAG, "✅ Overlay de arrastre eliminado")
        } catch (e: Exception) {
            Log.e(TAG, "Error al eliminar overlay de arrastre: ${e.message}")
        } finally {
            overlayArrastre = null
        }
    }
    private fun mostrarOverlayArrastre() {
        if (!Settings.canDrawOverlays(applicationContext) || overlayArrastre != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )

        val overlay = View(applicationContext).apply {
            setBackgroundColor(Color.argb(80, 0, 0, 0)) // semi-transparente
        }

        try {
            wm.addView(overlay, params)
            overlayArrastre = overlay
            Log.d(TAG, "🛑 Overlay de arrastre mostrado")
        } catch (e: Exception) {
            Log.e(TAG, "Error mostrando overlay de arrastre: ${e.message}")
        }
    }

    // Método de debug para listar todos los textos visibles
    private fun listarTodosLosTextos(node: AccessibilityNodeInfo?, nivel: Int = 0) {
        if (node == null) return

        try {
            val indent = "  ".repeat(nivel)

            // Log del texto del nodo
            val text = node.text?.toString()
            if (!text.isNullOrBlank()) {
                Log.d(TAG, "${indent}📝 Texto: $text")
            }

            // Log del contentDescription
            val desc = node.contentDescription?.toString()
            if (!desc.isNullOrBlank()) {
                Log.d(TAG, "${indent}📋 Desc: $desc")
            }

            // Recursivo para hijos (limitar a 3 niveles para no saturar logs)
            if (nivel < 3) {
                for (i in 0 until node.childCount) {
                    listarTodosLosTextos(node.getChild(i), nivel + 1)
                }
            }
        } catch (e: Exception) {
            // Ignorar errores en nodos individuales
        }
    }

    private fun detectarMenuDesinstalacion() {
        try {
            val rootNode = rootInActiveWindow ?: return

            val uninstallKeywords = listOf(
                "Desinstalar",
                "Uninstall",
                "Eliminar",
                "Remove",
                "Info de la app",
                "App info"
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
            Log.e(TAG, "Error en detectarMenuDesinstalacion", e)
        }
    }

    private fun esMiAppEnPeligro(rootNode: AccessibilityNodeInfo): Boolean {
        try {
            Log.d(TAG, "🔎 Buscando mi app en el contexto actual...")

            // 1. Buscar por el nombre real de la app
            val nameNodes = rootNode.findAccessibilityNodeInfosByText(myAppName)
            if (nameNodes.isNotEmpty()) {
                Log.d(TAG, "✅ App detectada por nombre: $myAppName")
                nameNodes.forEach { it.recycle() }
                return true
            }

            // 2. Buscar por package name completo
            val packageNodes = rootNode.findAccessibilityNodeInfosByText(myPackageName)
            if (packageNodes.isNotEmpty()) {
                Log.d(TAG, "✅ App detectada por package: $myPackageName")
                packageNodes.forEach { it.recycle() }
                return true
            }

            // 3. Buscar variaciones del nombre (sin espacios, minúsculas, etc)
            val variaciones = generarVariacionesNombre(myAppName)
            for (variacion in variaciones) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(variacion)
                if (nodes.isNotEmpty()) {
                    Log.d(TAG, "✅ App detectada por variación: $variacion")
                    nodes.forEach { it.recycle() }
                    return true
                }
            }

            // 4. Recorrer todos los nodos buscando el package en ContentDescription o ViewId
            val found = buscarEnNodos(rootNode)
            if (found) {
                Log.d(TAG, "✅ App detectada en nodos recursivos")
                return true
            }

            Log.d(TAG, "❌ App NO detectada en contexto")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error en esMiAppEnPeligro", e)
            return false
        }
    }

    private fun generarVariacionesNombre(nombre: String): List<String> {
        val variaciones = mutableListOf<String>()

        // Original
        variaciones.add(nombre)

        // Sin espacios
        variaciones.add(nombre.replace(" ", ""))

        // Minúsculas
        variaciones.add(nombre.lowercase())

        // Mayúsculas
        variaciones.add(nombre.uppercase())

        // Sin espacios y minúsculas
        variaciones.add(nombre.replace(" ", "").lowercase())

        // Sin guiones
        variaciones.add(nombre.replace("-", ""))
        variaciones.add(nombre.replace("-", " "))

        return variaciones.distinct()
    }
    private fun bloquearArrastre() {
        if (Settings.canDrawOverlays(applicationContext)) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )

            val overlay = View(applicationContext).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setOnTouchListener { _, _ -> true } // bloquea toques
            }

            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.addView(overlay, params)
        }
    }

    private fun buscarEnNodos(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        try {
            // Verificar contentDescription
            val desc = node.contentDescription?.toString()
            if (desc != null && (desc.contains(myPackageName) || desc.contains(myAppName))) {
                return true
            }

            // Verificar viewIdResourceName
            val viewId = node.viewIdResourceName
            if (viewId != null && viewId.contains(myPackageName)) {
                return true
            }

            // Verificar text
            val text = node.text?.toString()
            if (text != null && (text.equals(myAppName, ignoreCase = true) || text.contains(myPackageName))) {
                return true
            }

            // Buscar recursivamente en hijos
            for (i in 0 until node.childCount) {
                if (buscarEnNodos(node.getChild(i))) {
                    return true
                }
            }
        } catch (e: Exception) {
            // Ignorar errores en nodos individuales
        }

        return false
    }

    private fun bloquearYVolverHome() {
        // Verificar si la protección está activa
        val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
        val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)
        val ahora = System.currentTimeMillis()

        Log.d(TAG, "=== bloquearYVolverHome ===")
        Log.d(TAG, "Fecha fin: $fechaFin")
        Log.d(TAG, "Ahora: $ahora")
        Log.d(TAG, "Protección activa: ${fechaFin > ahora}")

        if (fechaFin <= ahora) {
            Log.w(TAG, "⚠️ Protección NO activa, no se bloqueará")
            // Resetear banderas
            lastLongPressWasOnMyApp = false
            miAppVisibleEnLauncher = false
            return
        }

        try {
            Log.d(TAG, "🏠 Ejecutando GLOBAL_ACTION_HOME")
            performGlobalAction(GLOBAL_ACTION_HOME)

            // Resetear banderas inmediatamente
            lastLongPressWasOnMyApp = false
            miAppVisibleEnLauncher = false
            ultimaVezMiAppVisible = 0L

            // Mostrar bloqueo después de un momento
            handler.postDelayed({
                val diasRestantes = calcularDiasRestantes(fechaFin)
                Log.d(TAG, "🛑 Mostrando bloqueo, días restantes: $diasRestantes")
                mostrarBloqueoDesinstalacion(diasRestantes)
            }, 150)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en bloquearYVolverHome", e)
        }
    }

    private fun mostrarBloqueoDesinstalacion(diasRestantes: Int) {
        try {
            Log.d(TAG, "=== mostrarBloqueoDesinstalacion ===")
            Log.d(TAG, "Días restantes: $diasRestantes")
            Log.d(TAG, "Tiene permiso overlay: ${Settings.canDrawOverlays(applicationContext)}")

            if (Settings.canDrawOverlays(applicationContext)) {
                Log.d(TAG, "📺 Mostrando overlay de bloqueo")
                showOverlay("TBlock", true, diasRestantes)
            } else {
                Log.d(TAG, "📱 Mostrando Activity de bloqueo (sin overlay)")
                // Fallback a Activity
                val intent = Intent(applicationContext, BlockOverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("blocked_package", "TBlock")
                    putExtra("is_uninstall_attempt", true)
                    putExtra("dias_restantes", diasRestantes)
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error mostrando bloqueo desinstalación", e)
        }
    }

    private fun calcularDiasRestantes(fechaFin: Long): Int {
        val ahora = System.currentTimeMillis()
        val diferencia = fechaFin - ahora
        return (diferencia / (24 * 60 * 60 * 1000)).toInt() + 1
    }

    // ========== OVERLAY (modificado para soportar modo desinstalación) ==========

    override fun onInterrupt() {
        removeOverlay()
    }

    private fun showOverlay(blockedPkg: String, isUninstallAttempt: Boolean, diasRestantes: Int) {
        if (overlayView != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // Contenedor full-screen
        val bgColor = if (isUninstallAttempt) {
            Color.argb(240, 183, 28, 28) // rojo intenso para desinstalación
        } else {
            Color.argb(220, 0, 0, 0) // negro para bloqueo normal
        }

        val layout = FrameLayout(this).apply {
            setBackgroundColor(bgColor)
            isClickable = true
            isFocusable = true
            tag = overlayTag
        }

        layout.setOnTouchListener { _, _ -> true }

        // Mensaje central
        val mensaje = if (isUninstallAttempt) {
            "⚠️ INTENTO DE DESINSTALACIÓN BLOQUEADO\n\n" +
                    "Esta aplicación está protegida\n" +
                    "Días restantes: $diasRestantes"
        } else {
            "Acceso bloqueado\n$blockedPkg"
        }

        val tv = TextView(this).apply {
            text = mensaje
            setTextColor(Color.WHITE)
            textSize = if (isUninstallAttempt) 18f else 20f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }

        // Botón
        val btn = Button(this).apply {
            text = "Volver al inicio"
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

        // Añadir views
        val paramsTv = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER }
        layout.addView(tv, paramsTv)

        val paramsBtn = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 120
        }
        layout.addView(btn, paramsBtn)

        // LayoutParams
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
        } catch (e: Exception) {
            Log.w(TAG, "addView overlay fallo: ${e.message}")
            // Fallback a Activity
            try {
                val intent = Intent(applicationContext, BlockOverlayActivity::class.java).apply {
                    putExtra("blocked_package", blockedPkg)
                    putExtra("is_uninstall_attempt", isUninstallAttempt)
                    putExtra("dias_restantes", diasRestantes)
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
            Log.w(TAG, "removeView overlay fallo: ${e.message}")
        } finally {
            overlayView = null
        }
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
