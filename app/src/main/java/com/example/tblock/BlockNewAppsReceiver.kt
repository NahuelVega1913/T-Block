package com.example.tblock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.preference.PreferenceManager
//import androidx.preference.PreferenceManager

class BlockNewAppsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_PACKAGE_ADDED) return

        val pkg = intent.data?.schemeSpecificPart ?: return

        // Leer la configuración: bloquear apps nuevas
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val enabled = prefs.getBoolean("block_new_apps", false)
        if (!enabled) return

        // Añadir a la lista de bloqueadas
        BlockListManager.addPackage(context, pkg)
    }
}
