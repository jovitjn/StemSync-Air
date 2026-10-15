package air.stemsync

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import air.stemsync.ui.StemSyncApp

class MainActivity : ComponentActivity() {
    private val vm: SessionViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            var requestedAction by remember { mutableStateOf<String?>(null) }
            val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                if (results.values.all { it }) requestedAction?.let { start(it) }
                else vm.failure("Nearby access was declined. You can enable it in App settings, then tap Host or Join again.")
                requestedAction=null
            }
            val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.importAudio(uris) }
            val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                if (uri!=null) runCatching { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(vm.diagnostics()) } }.onFailure { vm.failure("Export failed: ${it.message}") }
            }
            fun access(action: String) {
                val required=requiredPermissions().filter { ContextCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED }
                if (required.isEmpty()) start(action)
                else {
                    val prefs=getPreferences(MODE_PRIVATE)
                    val previouslyDenied=required.any { prefs.getBoolean(it,false) && !shouldShowRequestPermissionRationale(it) }
                    if (previouslyDenied) vm.failure("Nearby permissions are off. Open App settings → Permissions to enable access.")
                    else { prefs.edit { required.forEach { putBoolean(it,true) } }; requestedAction=action; permissionLauncher.launch(required.toTypedArray()) }
                }
            }
            StemSyncApp(state,vm,
                host={ access("host") },join={ access("join") },reconnect={ access("reconnect") },
                import={ picker.launch(arrayOf("*/*")) },
                export={ exporter.launch("stemsync-diagnostics.csv") },
                settings={ startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,"package:$packageName".toUri())) },
                radios={ startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) })
        }
    }
    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT<=32) addAll(listOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.READ_EXTERNAL_STORAGE))
        if (Build.VERSION.SDK_INT>=31) addAll(listOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE))
        if (Build.VERSION.SDK_INT>=33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    private fun start(action: String) {
        val availability=GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
        if (availability!=ConnectionResult.SUCCESS) { vm.failure("Google Play services is missing or needs an update (code $availability). Set it up before using Nearby offline."); return }
        val radiosOn=runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled==true && applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrDefault(false)
        if (!radiosOn) { vm.failure("Enable Bluetooth and Wi-Fi in Wireless settings, then try again. An internet connection is not required."); return }
        if (Build.VERSION.SDK_INT<=32 && !getSystemService(LocationManager::class.java).isLocationEnabled) { vm.failure("This Android version needs Location enabled for Nearby discovery. Enable it in system settings, then retry."); return }
        when (action) { "host"->vm.hostSession(); "join"->vm.joinSession(); else->vm.reconnect() }
    }
    override fun onStart() { super.onStart(); vm.foreground(true) }
    override fun onStop() { if (!isChangingConfigurations) vm.foreground(false); super.onStop() }
}
