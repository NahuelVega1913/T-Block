package com.example.t_block

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import android.widget.Button

import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.t_block.ui.theme.TBlockTheme
import android.content.Intent
import android.util.Log
import android.widget.TextView
import androidx.wear.compose.material3.Button

class BlockOverlayActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("BlockOverlay", "🚫 Activity de bloqueo creada")

        setFinishOnTouchOutside(false)

        val blockedPackage = intent.getStringExtra("blocked_package") ?: "Aplicación bloqueada"

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
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            )
            decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
            setBackgroundDrawableResource(android.R.color.black)
        }

        // ✅ USAR XML DE LAYOUT
        try {
            setContentView(R.layout.activity_block_overlay)
        } catch (e: Exception) {
            Log.e("BlockOverlay", "Error cargando XML: ${e.message}")
            createFallbackUI()
            return
        }

        // Referencias a elementos del XML
        try {
            val appNameBlocked = findViewById<TextView>(R.id.app_name_blocked)
            val btnHome = findViewById<Button>(R.id.btn_home)

            appNameBlocked?.text = blockedPackage
            Log.d("BlockOverlay", "📦 App bloqueada: $blockedPackage")

            btnHome?.setOnClickListener {
                Log.d("BlockOverlay", "✅ Botón HOME presionado")
                val home = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                try {
                    startActivity(home)
                    finish()
                } catch (e: Exception) {
                    Log.e("BlockOverlay", "Error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("BlockOverlay", "Error vinculando elementos: ${e.message}")
        }
    }

    private fun createFallbackUI() {
        Log.w("BlockOverlay", "⚠️ Usando UI fallback")
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

    override fun onDestroy() {
        super.onDestroy()
        Log.d("BlockOverlay", "🛑 Activity destruida")
    }
}
