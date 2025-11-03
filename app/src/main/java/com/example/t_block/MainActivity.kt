package com.example.t_block

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Switch
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.t_block.ui.theme.TBlockTheme
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Settings.Secure
import android.util.Log



sealed class Screen(val title: String, val icon: ImageVector) {
    object Home : Screen("Home", Icons.Default.Home)
    object Busqueda : Screen("Apps", Icons.Default.Search)
    object Prioridad : Screen("About", Icons.Default.Star)
}
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            // IMPORTANTE: establecer el layout antes de usar findViewById
            setContentView(R.layout.activity_main)

            // Obtener referencias de UI (null-safe) - usar tipo plenamente cualificado para evitar colisión con Compose Switch
            val switchBlockNew = try {
                findViewById<android.widget.Switch?>(R.id.switch_block_new_apps)
            } catch (e: Exception) {
                null
            }

            if (switchBlockNew == null) {
                // no abortar la app; informar y seguir con la UI Compose
                Toast.makeText(this, "switch_block_new_apps no encontrado en el layout, continuando...", Toast.LENGTH_SHORT).show()
            }

            // Preferencias y DPM (usa la clase correcta MyDeviceAdminReceiver)
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as? DevicePolicyManager

            // pedir permiso de overlays sin lanzar excepción
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!Settings.canDrawOverlays(this)) {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        startActivity(intent)
                    }
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "No se pudo abrir ajuste de overlays: ${e.message}")
            }

            // comprobar AccessibilityService sin abortar
            try {
                if (!isAccessibilityServiceEnabled(this, AppMonitorService::class.java)) {
                    val accIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(accIntent)
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "No se pudo comprobar servicio de accesibilidad: ${e.message}")
            }

            // edge-to-edge y Compose UI
            try {
                enableEdgeToEdge()
            } catch (e: Exception) {
                Log.w("MainActivity", "enableEdgeToEdge falló: ${e.message}")
            }

            setContent {
                TBlockTheme {
                    var selectedScreen by remember { mutableStateOf<Screen>(Screen.Home) }
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        bottomBar = {
                            BottomNavigationBar(
                                selectedScreen = selectedScreen,
                                onScreenSelected = { selectedScreen = it }
                            )
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            contentAlignment = Alignment.Center
                        ) {
                            when (selectedScreen) {
                                Screen.Home -> Home()
                                Screen.Busqueda -> AppLockScreen()
                                Screen.Prioridad -> Text("Pantalla de Prioridad")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Capturamos cualquier excepción no prevista en onCreate para evitar crash
            Log.e("MainActivity", "onCreate fallo: ", e)
            try {
                Toast.makeText(this, "Error iniciando la app: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (_: Exception) { }
            // opcional: persistir stacktrace en prefs para recuperación remota / inspección
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                prefs.edit().putString("last_start_error", Log.getStackTraceString(e)).apply()
            } catch (_: Exception) { }
        }
    }

    // Helper: comprobar si el servicio de accesibilidad está activado
    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        return try {
            val flat = ComponentName(context, service).flattenToString()
            val enabled = Secure.getString(context.contentResolver, Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            enabled.split(":").any { it.equals(flat, ignoreCase = true) }
        } catch (e: Exception) {
            false
        }
    }
}
fun drawableToImageBitmap(drawable: Drawable): ImageBitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap.asImageBitmap()
    }
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 48
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 48
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap.asImageBitmap()
}
fun loadInstalledApps(context: Context): List<AppItem> {
    val pm = context.packageManager
    val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolveList = pm.queryIntentActivities(mainIntent, 0)

    return resolveList.asSequence()
        .mapNotNull { resolveInfo ->
            val ai = resolveInfo.activityInfo ?: return@mapNotNull null
            val appInfo = ai.applicationInfo

            // filtrar apps no habilitadas y apps del sistema
           // if (!ai.enabled) return@mapNotNull null
            val sysFlags = android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                    android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
         //   if (appInfo.flags and sysFlags != 0) return@mapNotNull null

            val pkg = ai.packageName ?: return@mapNotNull null
            val label = try { pm.getApplicationLabel(appInfo).toString() } catch (e: Exception) { pkg }
            val drawable = try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null }
            val iconBitmap = drawable?.let { drawableToImageBitmap(it) }

            AppItem(pkg, label, iconBitmap)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label }
        .toList()
}
@Composable
fun AppLockScreen() {
    val context = LocalContext.current

    val apps = remember { loadInstalledApps(context) }

    var showDialog by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // SharedPreferences para persistir apps bloqueadas
    val prefs = context.getSharedPreferences("tblock_prefs", Context.MODE_PRIVATE)
    val keyBlocked = "blocked_apps"
    val keyDias = "dias_evitar_desinstalacion"

    // Estado que indica si la protección contra desinstalación está activa (se sincroniza con prefs)
    var isPreventUninstallActive by remember { mutableStateOf(prefs.getInt(keyDias, 0) > 0) }

    // Escuchar cambios en SharedPreferences para actualizar isPreventUninstallActive cuando se modifica desde Home()
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == keyDias) {
                isPreventUninstallActive = prefs.getInt(keyDias, 0) > 0
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // Cargar bloqueadas desde prefs y reconstruir AppItem usando PackageManager
    val blockedApps = remember {
        val list = mutableStateListOf<AppItem>()
        val initial = prefs.getStringSet(keyBlocked, emptySet()) ?: emptySet()
        val pm = context.packageManager
        initial.forEach { pkg ->
            try {
                val ai = pm.getApplicationInfo(pkg, 0)
                val label = try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { pkg }
                val drawable = try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null }
                val iconBitmap = drawable?.let { drawableToImageBitmap(it) }
                list.add(AppItem(pkg, label, iconBitmap))
            } catch (e: Exception) {
                // paquete no encontrado -> ignorar
            }
        }
        list
    }

    val filtered = remember(query, apps) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column {
            Text(
                text = "Control de aplicaciones",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Apps bloqueadas:",
                fontSize = 18.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (blockedApps.isEmpty()) {
                Text("No hay aplicaciones bloqueadas", modifier = Modifier.padding(8.dp))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(blockedApps) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (app.icon != null) {
                                    Image(
                                        bitmap = app.icon,
                                        contentDescription = app.label,
                                        modifier = Modifier.size(40.dp)
                                    )
                                } else {
                                    Box(modifier = Modifier.size(40.dp)) { }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(app.label, fontSize = 16.sp)
                            }

                            // Switch para desbloquear (remover de la lista)
                            Switch(
                                checked = true,
                                enabled = !isPreventUninstallActive, // deshabilitar si la protección está activa
                                onCheckedChange = { checked ->
                                    if (isPreventUninstallActive) {
                                        // informar al usuario; no permitir quitar mientras protección activa
                                        Toast.makeText(
                                            context,
                                            "No puedes quitar aplicaciones mientras la protección contra desinstalación esté activa",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        if (!checked) {
                                            // remover de la lista y actualizar prefs
                                            blockedApps.removeAll { it.packageName == app.packageName }
                                            val current = prefs.getStringSet(keyBlocked, emptySet())?.toMutableSet() ?: mutableSetOf()
                                            current.remove(app.packageName)
                                            prefs.edit().putStringSet(keyBlocked, current).apply()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        FloatingActionButton(
            onClick = { showDialog = true },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Search, contentDescription = "Buscar apps")
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Buscar aplicaciones") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Buscar...") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.height(300.dp)) {
                            items(filtered) { app ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { /* seleccionar app si se desea */ }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (app.icon != null) {
                                            Image(
                                                bitmap = app.icon,
                                                contentDescription = app.label,
                                                modifier = Modifier.size(40.dp)
                                            )
                                        } else {
                                            Box(modifier = Modifier.size(40.dp)) { /* placeholder */ }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(app.label)
                                    }

                                    Button(
                                        onClick = {
                                            if (blockedApps.none { it.packageName == app.packageName }) {
                                                blockedApps.add(app)
                                                // guardar en prefs (copiar set actual y actualizar)
                                                val current = prefs.getStringSet(keyBlocked, emptySet())?.toMutableSet() ?: mutableSetOf()
                                                current.add(app.packageName)
                                                prefs.edit().putStringSet(keyBlocked, current).apply()
                                            }
                                        }
                                    ) {
                                        Text("Bloquear")
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showDialog = false }) {
                        Text("Cerrar")
                    }
                }
            )
        }
    }
}
@Composable
fun BottomNavigationBar(
    selectedScreen: Screen,
    onScreenSelected: (Screen) -> Unit
) {
    val items = listOf(
        Screen.Home,
        Screen.Busqueda,
        Screen.Prioridad
    )

    NavigationBar {
        items.forEach { screen ->
            NavigationBarItem(
                selected = selectedScreen == screen,
                onClick = { onScreenSelected(screen) },
                icon = { Icon(screen.icon, contentDescription = screen.title) },
                label = { Text(screen.title) }
            )
        }
    }
}
@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    TBlockTheme {

    }
}
@Composable
fun Home(){
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    // Cambiado: usar la clase real MyDeviceAdminReceiver (coincide con la definición)
    val adminComponent = remember { ComponentName(context, MyDeviceAdminReceiver::class.java) } // { changed code }

    // SharedPreferences
    val prefsName = "tblock_prefs"
    val keyBloquearRec = "bloquear_recien"
    val keyDias = "dias_evitar_desinstalacion"
    val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    // Estados UI (inicial neutrales; se cargarán desde prefs)
    var bloquearRecienInstaladas by remember { mutableStateOf(false) }
    var showPreventUninstallDialog by remember { mutableStateOf(false) }
    var diasInput by remember { mutableStateOf("") }
    var diasConfigurados by remember { mutableStateOf<Int?>(null) }
    var evitarDesinstalacionSwitch by remember { mutableStateOf(false) }

    // estado admin (inicial)
    var isAdminActive by remember { mutableStateOf(devicePolicyManager?.isAdminActive(adminComponent) == true) }

    // Launcher para abrir la pantalla de activación de admin de forma segura
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Al volver, refrescar estado
        isAdminActive = devicePolicyManager?.isAdminActive(adminComponent) == true
    }

    // Cargar valores guardados una sola vez al entrar en composición
    LaunchedEffect(Unit) {
        bloquearRecienInstaladas = prefs.getBoolean(keyBloquearRec, false)
        if (prefs.contains(keyDias)) {
            val d = prefs.getInt(keyDias, 0)
            if (d > 0) {
                diasConfigurados = d
                evitarDesinstalacionSwitch = true
            } else {
                diasConfigurados = null
                evitarDesinstalacionSwitch = false
            }
        } else {
            diasConfigurados = null
            evitarDesinstalacionSwitch = false
        }
    }

    // Observador para refrescar el estado cuando la actividad vuelve (ON_RESUME)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAdminActive = devicePolicyManager?.isAdminActive(adminComponent) == true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Ajustes",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Cajita rectangular: Bloquear apps recién instaladas (switch izquierda, texto derecha)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp)
                .clickable {
                    bloquearRecienInstaladas = !bloquearRecienInstaladas
                    // guardar cambio
                    prefs.edit().putBoolean(keyBloquearRec, bloquearRecienInstaladas).apply()
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            // interruptor a la izquierda
            Switch(
                checked = bloquearRecienInstaladas,
                onCheckedChange = { checked ->
                    bloquearRecienInstaladas = checked
                    prefs.edit().putBoolean(keyBloquearRec, bloquearRecienInstaladas).apply()
                }
            )

            Spacer(modifier = Modifier.width(12.dp))

            // título y estado a la derecha
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Bloquear aplicaciones recién instaladas",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (bloquearRecienInstaladas) "ACTIVADO" else "DESACTIVADO",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Cajita rectangular: Evitar la desinstalación (switch izquierda -> abre diálogo para dias)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = evitarDesinstalacionSwitch,
                onCheckedChange = { checked ->
                    if (checked) {
                        // intentar activar: abrir diálogo para pedir días
                        showPreventUninstallDialog = true
                    } else {
                        // desactivar: borrar configuración y estado
                        evitarDesinstalacionSwitch = false
                        diasConfigurados = null
                        prefs.edit().remove(keyDias).apply()
                    }
                }
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        // click en la fila abre diálogo si no está activo, o muestra estado si está activo
                        if (!evitarDesinstalacionSwitch) showPreventUninstallDialog = true
                    },
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Evitar la desinstalación",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = diasConfigurados?.let { "Protegido por $it días" } ?: "No configurado",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Mostrar resumen simple
        Text(
            text = "Estado: ${if (bloquearRecienInstaladas) "Bloqueo nuevas apps activo" else "Bloqueo nuevas apps inactivo"}",
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            text = diasConfigurados?.let { "Protección contra desinstalación por $it días" } ?: "Protección contra desinstalación: NO configurada",
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }

    // Diálogo para ingresar cantidad de días (se abre al intentar activar evitar desinstalación)
    if (showPreventUninstallDialog) {
        AlertDialog(
            onDismissRequest = {
                showPreventUninstallDialog = false
                evitarDesinstalacionSwitch = diasConfigurados != null
            },
            title = { Text("Evitar desinstalación") },
            text = {
                Column {
                    Text("Ingrese la cantidad de días para evitar la desinstalación:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = diasInput,
                        onValueChange = { value ->
                            diasInput = value.filter { it.isDigit() }
                        },
                        placeholder = { Text("Ej. 7") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val dias = diasInput.toIntOrNull()
                    if (dias != null && dias > 0) {
                        diasConfigurados = dias
                        evitarDesinstalacionSwitch = true
                        showPreventUninstallDialog = false
                        diasInput = ""
                        // guardar en prefs
                        prefs.edit().putInt(keyDias, diasConfigurados!!).apply()

                        // Intent para activar Device Admin (usa adminComponent corregido)
                        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Activa administrador para permitir funciones de bloqueo")
                        }
                        val activity = (context as? Activity)
                        try {
                            if (activity != null) {
                                activity.startActivity(intent)
                            } else {
                                // fallback usando FLAG_ACTIVITY_NEW_TASK
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                        } catch (e: Exception) {
                            try {
                                val fallback = Intent("android.app.action.DEVICE_ADMIN_SETTINGS")
                                if (activity != null) {
                                    activity.startActivity(fallback)
                                } else {
                                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(fallback)
                                }
                            } catch (_: Exception) { /* ignore */ }
                            Toast.makeText(context, "No se pudo abrir la pantalla de administrador", Toast.LENGTH_SHORT).show()
                        }

                        // isAdminActive se actualizará en ON_RESUME cuando el usuario vuelva
                    } else {
                        // invalid input: limpiar o mantener
                        diasInput = ""
                    }
                }) {
                    Text("Confirmar")
                }
            },
            dismissButton = {
                Button(onClick = {
                    showPreventUninstallDialog = false
                    // cancelar activación si no existe configuración previa
                    evitarDesinstalacionSwitch = diasConfigurados != null
                }) {
                    Text("Cancelar")
                }
            }
        )
    }
}
// Agregar un DeviceAdminReceiver simple que se usará en el Intent de activación
class MyDeviceAdminReceiver : android.app.admin.DeviceAdminReceiver() {
    // ...existing code or leave empty...
}
data class BottomNavItem(
    val label: String,
    val icon: ImageVector
)
data class AppItem(val packageName: String, val label: String, val icon: ImageBitmap?)
