package com.example.t_block

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.preference.PreferenceManager
import android.util.Log
import com.example.tblock.BlockListManager

// Renombrada la clase para evitar redeclaration con otra definición existente.
class BlockNewAppsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action ?: return

        if (action != Intent.ACTION_PACKAGE_ADDED) return

        val pkg = intent.data?.schemeSpecificPart ?: return

        try {
            val prefs = context.getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean("bloquear_recien", false)

            if (!enabled) return

            Log.d("BlockNewApps", "📦 Nueva app instalada: $pkg")

            val current = prefs.getStringSet("blocked_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
            if (!current.contains(pkg)) {
                current.add(pkg)
                prefs.edit().putStringSet("blocked_apps", current).apply()
                Log.d("BlockNewApps", "✅ App $pkg agregada a bloqueo automático")
            }

        } catch (e: Exception) {
            Log.e("BlockNewAppsReceiver", "Error: ${e.message}")
        }
    }
}
