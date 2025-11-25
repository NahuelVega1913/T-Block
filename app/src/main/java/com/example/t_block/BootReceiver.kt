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

            Log.d("BootReceiver", "Sistema iniciado - Servicio de accesibilidad debería estar activo")

            // El servicio de accesibilidad se inicia automáticamente si está habilitado en Ajustes
            // Solo necesitas asegurar que el usuario lo activó manualmente
        }
    }
}