package com.example.tblock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.preference.PreferenceManager
import android.util.Log

//import androidx.preference.PreferenceManager

class BlockNewAppsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_PACKAGE_ADDED) return

        val pkg = intent.data?.schemeSpecificPart ?: return

        Log.d("BlockNewAppsReceiver", "📦 Nueva app instalada: $pkg")

        // Leer la configuración desde tblock_prefs
        val prefs = context.getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("block_new_apps", false)

        Log.d("BlockNewAppsReceiver", "⚙️ block_new_apps habilitado: $enabled")

        if (!enabled) return

        // Añadir a la lista de bloqueadas
        BlockListManager.addPackage(context, pkg)
        Log.d("BlockNewAppsReceiver", "✅ App $pkg añadida a la lista de bloqueadas")
    }
}
