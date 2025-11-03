package com.example.t_block

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.preference.PreferenceManager
import android.util.Log
import com.example.tblock.BlockListManager

// Renombrada la clase para evitar redeclaration con otra definición existente.
class BlockNewAppsReceiverKt : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_PACKAGE_ADDED) return

        val pkg = intent.data?.schemeSpecificPart ?: return

        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val enabled = prefs.getBoolean("block_new_apps", false)
            if (!enabled) return

            // Llamada al gestor (asegúrate de que BlockListManager existe en el package com.example.t_block)
            try {
                BlockListManager.addPackage(context, pkg)
            } catch (e: Exception) {
                Log.e("BlockNewAppsReceiverKt", "Error añadiendo paquete a lista: $pkg", e)
            }
        } catch (e: Exception) {
            Log.e("BlockNewAppsReceiverKt", "onReceive error", e)
        }
    }
}
