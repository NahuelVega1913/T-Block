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
import android.view.accessibility.AccessibilityNodeInfo

class AppMonitorService : AccessibilityService() {

    private val TAG = "AppMonitorService"
    private var lastBlockedPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())

    // overlay view y window manager
    private var overlayView: View? = null
    private val overlayTag = "tblock_overlay"

    // Variables para detectar desinstalación
    private var isInEditMode = false
    private val myPackageName by lazy { applicationContext.packageName }
    private val myAppNames = listOf(
        "T-Block"
        // Ajusta según el nombre real de tu app
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val t = event.eventType

        // Manejar diferentes tipos de eventos
        when (t) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
                detectarLauncherEditMode(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleWindowContentChanged(event)
                if (isInEditMode) {
                    detectarMenuDesinstalacion()
                }
            }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                detectarLongPressEnMiApp()
            }
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
        verificarBloqueApp(pkg)
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
            handler.postDelayed({
                verificarModoEdicion()
            }, 200)
        }
    }

    private fun verificarModoEdicion() {
        try {
            val rootNode = rootInActiveWindow ?: return

            // Palabras clave de desinstalación
            val editIndicators = listOf(
                "Desinstalar",
                "Eliminar",
                "Quitar",
                "Uninstall",
                "Remove",
                "Delete",
                "Información",
                "Info"
            )

            for (indicator in editIndicators) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(indicator)
                if (nodes.isNotEmpty()) {
                    if (esMiAppEnPeligro(rootNode)) {
                        Log.w(TAG, "¡Intento de desinstalación desde launcher detectado!")
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
            Log.e(TAG, "Error en verificarModoEdicion", e)
        }
    }

    private fun detectarLongPressEnMiApp() {
        try {
            val rootNode = rootInActiveWindow ?: return

            // Buscar si se hizo long press en nuestra app
            for (appName in myAppNames) {
                val appNodes = rootNode.findAccessibilityNodeInfosByText(appName)
                if (appNodes.isNotEmpty()) {
                    isInEditMode = true
                    Log.d(TAG, "Long press detectado en nuestra app")

                    handler.postDelayed({
                        verificarModoEdicion()
                        isInEditMode = false
                    }, 500)

                    appNodes.forEach { it.recycle() }
                    break
                }
            }

            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error en detectarLongPressEnMiApp", e)
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
            // Buscar por nombre de app
            for (appName in myAppNames) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(appName)
                if (nodes.isNotEmpty()) {
                    nodes.forEach { it.recycle() }
                    return true
                }
            }

            // Buscar por package name
            val packageNodes = rootNode.findAccessibilityNodeInfosByText(myPackageName)
            if (packageNodes.isNotEmpty()) {
                packageNodes.forEach { it.recycle() }
                return true
            }

            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error en esMiAppEnPeligro", e)
            return false
        }
    }

    private fun bloquearYVolverHome() {
        // Verificar si la protección está activa
        val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
        val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)
        val ahora = System.currentTimeMillis()

        if (fechaFin <= ahora) {
            // Protección no activa, no bloquear
            return
        }

        try {
            // Volver al home
            performGlobalAction(GLOBAL_ACTION_HOME)

            // Mostrar bloqueo después de un momento
            handler.postDelayed({
                val diasRestantes = calcularDiasRestantes(fechaFin)
                mostrarBloqueoDesinstalacion(diasRestantes)
            }, 150)
        } catch (e: Exception) {
            Log.e(TAG, "Error en bloquearYVolverHome", e)
        }
    }

    private fun mostrarBloqueoDesinstalacion(diasRestantes: Int) {
        try {
            if (Settings.canDrawOverlays(applicationContext)) {
                showOverlay("TBlock", true, diasRestantes)
            } else {
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
            Log.e(TAG, "Error mostrando bloqueo desinstalación", e)
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
