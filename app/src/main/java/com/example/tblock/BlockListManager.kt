package com.example.tblock

import android.content.Context
import android.preference.PreferenceManager
//import androidx.preference.PreferenceManager

object BlockListManager {
    /**
     * Añade el paquete a la lista de aplicaciones bloqueadas.
     */
    fun addPackage(context: Context, packageName: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = "blocked_apps"
        val current = prefs.getStringSet(key, null)?.toMutableSet() ?: mutableSetOf()
        if (current.add(packageName)) {
            prefs.edit().putStringSet(key, current).apply()
        }
    }
}
