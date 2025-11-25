package com.example.t_block

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d("DeviceAdmin", "✅ Device Admin ACTIVADO")
        Toast.makeText(context, "Administrador de dispositivo activado", Toast.LENGTH_SHORT).show()

        val prefs = context.getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("admin_active", true).apply()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d("DeviceAdmin", "❌ Device Admin DESACTIVADO")
        Toast.makeText(context, "Administrador de dispositivo desactivado", Toast.LENGTH_SHORT).show()

        val prefs = context.getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("admin_active", false).apply()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.w("DeviceAdmin", "⚠️ Se intenta desactivar Device Admin")
        return "Debes desactivar la protección anti-desinstalación primero"
    }
}