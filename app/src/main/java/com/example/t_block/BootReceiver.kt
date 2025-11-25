package com.example.t_block

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        val action = intent?.action ?: return

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON") {

            Log.d("BootReceiver", "📱 Sistema iniciado - Servicio de accesibilidad debería estar activo")

            val prefs = context.getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
            val adminActive = prefs.getBoolean("admin_active", false)
            Log.d("BootReceiver", if (adminActive) "✅ Admin activo" else "❌ Admin no activo")
        }
    }
}