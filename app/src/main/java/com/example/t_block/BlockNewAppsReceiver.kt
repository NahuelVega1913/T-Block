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
        Log.d("BlockNewAppsReceiver", "🎯 RECEIVER LLAMADO")

        if (context == null || intent == null) {
            Log.e("BlockNewAppsReceiver", "❌ Context o Intent nulo")
            return
        }

        Log.d("BlockNewAppsReceiver", "📋 Action: ${intent.action}")

        if (intent.action != Intent.ACTION_PACKAGE_ADDED) {
            Log.d("BlockNewAppsReceiver", "⏭️ No es PACKAGE_ADDED, saliendo")
            return
        }

        // Filtrar actualizaciones (EXTRA_REPLACING = true significa que es actualización)
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            Log.d("BlockNewAppsReceiver", "⏭️ Es actualización, no instalación nueva")
            return
        }

        val pkg = intent.data?.schemeSpecificPart
        Log.d("BlockNewAppsReceiver", "📦 Package: $pkg")

        if (pkg == null) {
            Log.e("BlockNewAppsReceiver", "❌ Package name es null")
            return
        }

        // No bloquear nuestra propia app
        if (pkg == context.packageName) {
            Log.d("BlockNewAppsReceiver", "⏭️ Es nuestra app, ignorando")
            return
        }

        try {
            val prefs = context.getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
            val bloqueosHabilitado = prefs.getBoolean("bloquear_recien", false)

            Log.d("BlockNewAppsReceiver", "🔍 bloquear_recien = $bloqueosHabilitado")

            if (!bloqueosHabilitado) {
                Log.d("BlockNewAppsReceiver", "❌ Función deshabilitada")
                return
            }

            // Obtener lista actual
            val blocked = prefs.getStringSet("blocked_apps", emptySet())?.toMutableSet()
                ?: mutableSetOf()

            Log.d("BlockNewAppsReceiver", "📊 Apps bloqueadas actuales: ${blocked.size}")

            if (blocked.contains(pkg)) {
                Log.d("BlockNewAppsReceiver", "⚠️ $pkg ya estaba bloqueada")
                return
            }

            // AGREGAR con commit() para forzar escritura inmediata
            blocked.add(pkg)
            val success = prefs.edit()
                .putStringSet("blocked_apps", HashSet(blocked))
                .commit()

            if (success) {
                Log.d("BlockNewAppsReceiver", "✅✅✅ APP AGREGADA: $pkg")
                Log.d("BlockNewAppsReceiver", "📊 Total bloqueadas ahora: ${blocked.size}")
            } else {
                Log.e("BlockNewAppsReceiver", "❌ FALLO al guardar en SharedPreferences")
            }

        } catch (e: Exception) {
            Log.e("BlockNewAppsReceiver", "❌ Error: ${e.message}", e)
        }
    }
}
