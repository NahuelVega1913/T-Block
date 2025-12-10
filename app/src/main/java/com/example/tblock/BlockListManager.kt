package com.example.tblock

import android.content.Context
import android.preference.PreferenceManager
//import androidx.preference.PreferenceManager

object BlockListManager {
    /**
     * Añade el paquete a la lista de aplicaciones bloqueadas.
     */

    private const val PREFS_NAME = "tblock_prefs"
    private const val KEY_BLOCKED_APPS = "blocked_apps"
    fun addPackage(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_BLOCKED_APPS, emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        if (current.add(packageName)) {
            // Guardamos una copia para evitar compartir referencias con SharedPreferences
            prefs.edit().putStringSet(KEY_BLOCKED_APPS, HashSet(current)).apply()
        }
    }
}
