package com.example.tblock

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Switch
import android.widget.Toast
import android.content.SharedPreferences
import android.preference.PreferenceManager
//import androidx.preference.PreferenceManager
import com.example.t_block.R
//import com.example.tblock.R

class MainActivity : AppCompatActivity() {
    // Listener reference para poder anular registro en onDestroy
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Cambia aquí el nombre del layout que contiene el switch (sin R.layout.):
        // por ejemplo "activity_main", "activity_main2", "layout_principal", etc.
        val LAYOUT_NAME = "activity_main"
        val layoutId = resources.getIdentifier(LAYOUT_NAME, "layout", packageName)
        if (layoutId == 0) {
            Toast.makeText(this, "Layout '$LAYOUT_NAME' no encontrado. Edita LAYOUT_NAME.", Toast.LENGTH_LONG).show()
            return
        }
        setContentView(layoutId)

        // Claves de preferencias
        val KEY_BLOCK_NEW = "block_new_apps"
        val KEY_UNINSTALL = "unistall" // Ajusta si tu proyecto usa otra clave

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // Obtener referencia al Switch después de setContentView (null-safe)
        val switchBlockNew = findViewById<Switch?>(R.id.switch_block_new_apps)
        if (switchBlockNew == null) {
            Toast.makeText(this, "switch_block_new_apps no encontrado en el layout", Toast.LENGTH_LONG).show()
            return
        }

        // Función para actualizar el estado UI según prefs y la dependencia "unistall"
        fun updateBlockNewUi() {
            val enabled = prefs.getBoolean(KEY_BLOCK_NEW, false)
            val uninstallProtected = prefs.getBoolean(KEY_UNINSTALL, false)

            if (uninstallProtected) {
                if (!enabled) {
                    prefs.edit().putBoolean(KEY_BLOCK_NEW, true).apply()
                }
                switchBlockNew.isChecked = true
                switchBlockNew.isEnabled = false
            } else {
                switchBlockNew.isEnabled = true
                switchBlockNew.isChecked = enabled
            }
        }

        // Inicializar UI
        updateBlockNewUi()

        // Listener para togglear la opción desde el control
        switchBlockNew.setOnCheckedChangeListener { _, isChecked ->
            val uninstallProtected = prefs.getBoolean(KEY_UNINSTALL, false)
            if (uninstallProtected && !isChecked) {
                Toast.makeText(
                    this,
                    "No se puede desactivar mientras la opción 'Unistall' esté activa",
                    Toast.LENGTH_SHORT
                ).show()
                switchBlockNew.isChecked = true
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean(KEY_BLOCK_NEW, isChecked).apply()
        }

        // Registrar listener para reaccionar si la opción "unistall" cambia desde otra parte
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == KEY_UNINSTALL || changedKey == KEY_BLOCK_NEW) {
                runOnUiThread { updateBlockNewUi() }
            }
        }
        prefsListener?.let { prefs.registerOnSharedPreferenceChangeListener(it) }

        // ...existing code...
    }

    override fun onDestroy() {
        super.onDestroy()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefsListener?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
    }

    // ...existing code...
}
