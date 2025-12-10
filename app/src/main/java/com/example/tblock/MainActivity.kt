package com.example.tblock

import android.content.Context
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

        val LAYOUT_NAME = "activity_main"
        val layoutId = resources.getIdentifier(LAYOUT_NAME, "layout", packageName)
        if (layoutId == 0) {
            Toast.makeText(this, "Layout '$LAYOUT_NAME' no encontrado. Edita LAYOUT_NAME.", Toast.LENGTH_LONG).show()
            return
        }
        setContentView(layoutId)

        // Claves de preferencias
        val KEY_BLOCK_NEW = "block_new_apps"
        val KEY_UNINSTALL = "unistall"

        // ✅ USAR tblock_prefs en lugar de default
        val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)

        val switchBlockNew = findViewById<Switch?>(R.id.switch_block_new_apps)
        if (switchBlockNew == null) {
            Toast.makeText(this, "switch_block_new_apps no encontrado en el layout", Toast.LENGTH_LONG).show()
            return
        }

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

        updateBlockNewUi()

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

        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == KEY_UNINSTALL || changedKey == KEY_BLOCK_NEW) {
                runOnUiThread { updateBlockNewUi() }
            }
        }
        prefsListener?.let { prefs.registerOnSharedPreferenceChangeListener(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        val prefs = getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
        prefsListener?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
    }

    // ...existing code...
}
