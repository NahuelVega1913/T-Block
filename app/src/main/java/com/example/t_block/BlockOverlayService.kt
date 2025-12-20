package com.example.t_block

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

class BlockOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var countdown = 5
    private var countdownRunnable: Runnable? = null

    companion object {
        private var isOverlayShowing = false
        private var lastOverlayTime = 0L
        private const val OVERLAY_COOLDOWN = 3000L // 3 segundos de cooldown
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ✅ Prevenir llamadas duplicadas
        val currentTime = System.currentTimeMillis()
        if (isOverlayShowing && (currentTime - lastOverlayTime) < OVERLAY_COOLDOWN) {
            Log.d("BlockOverlayService", "⏭️ Overlay ya está mostrándose, ignorando llamada duplicada")
            return START_NOT_STICKY
        }

        lastOverlayTime = currentTime

        val blockedPackage = intent?.getStringExtra("blocked_package") ?: "Aplicación bloqueada"
        val overlayType = intent?.getStringExtra("overlay_type") ?: "block"

        showOverlay(blockedPackage, overlayType)

        return START_NOT_STICKY
    }

    private fun showOverlay(blockedPackage: String, overlayType: String) {
        if (overlayView != null) {
            Log.d("BlockOverlayService", "Overlay ya existe, no recreando")
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.CENTER

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

        overlayView = if (overlayType == "protection") {
            inflater.inflate(R.layout.activity_proteccion_simple, null)
        } else {
            inflater.inflate(R.layout.activity_block_overlay_simple, null)
        }

        overlayView?.let { view ->
            // Configurar elementos según el tipo
            if (overlayType == "protection") {
                val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
                val fechaFin = prefs.getLong("fin_evitar_desinstalacion", 0L)
                val diasRemaining = if (fechaFin > System.currentTimeMillis()) {
                    ((fechaFin - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt() + 1
                } else {
                    0
                }

                view.findViewById<TextView>(R.id.dias_restantes)?.text = "$diasRemaining días"

                val btnCerrar = view.findViewById<Button>(R.id.btn_cerrar)
                setupCountdown(btnCerrar, overlayType)

            } else {
                view.findViewById<TextView>(R.id.app_name_blocked)?.text = blockedPackage

                val btnHome = view.findViewById<Button>(R.id.btn_home)
                setupCountdown(btnHome, overlayType)
            }

            // Bloquear interacciones fuera del overlay
            view.setOnTouchListener { _, _ -> true }

            windowManager?.addView(view, params)
            isOverlayShowing = true
            Log.d("BlockOverlayService", "✅ Overlay mostrado por encima del sistema")
        }
    }

    private fun setupCountdown(button: Button?, overlayType: String) {
        if (button == null) return

        // ✅ Deshabilitar botón inicialmente
        button.isEnabled = false
        button.alpha = 0.5f
        countdown = 5

        // ✅ Actualizar texto con contador
        val originalText = button.text.toString()
        button.text = "$originalText ($countdown)"

        // ✅ Crear runnable del contador
        countdownRunnable = object : Runnable {
            override fun run() {
                countdown--

                if (countdown > 0) {
                    button.text = "$originalText ($countdown)"
                    handler.postDelayed(this, 1000)
                    Log.d("BlockOverlayService", "⏱️ Countdown: $countdown")
                } else {
                    // ✅ Habilitar botón después de 5 segundos
                    button.isEnabled = true
                    button.alpha = 1.0f
                    button.text = originalText
                    button.setOnClickListener {
                        goHomeAndClose()
                    }
                    Log.d("BlockOverlayService", "✅ Botón habilitado después del countdown")
                }
            }
        }

        // ✅ Iniciar countdown
        handler.postDelayed(countdownRunnable!!, 1000)
    }

    private fun goHomeAndClose() {
        Log.d("BlockOverlayService", "✅ Enviando al inicio y cerrando overlay")

        // Cancelar countdown si existe
        countdownRunnable?.let { handler.removeCallbacks(it) }

        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        try {
            startActivity(home)
        } catch (e: Exception) {
            Log.e("BlockOverlayService", "Error: ${e.message}")
        }

        removeOverlay()
        stopSelf()
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            windowManager?.removeView(view)
            overlayView = null
            isOverlayShowing = false
            Log.d("BlockOverlayService", "🛑 Overlay removido")
        }

        // Limpiar callbacks del handler
        countdownRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }
}