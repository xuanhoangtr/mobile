package vn.edu.hcmute.androidchat.feature.media.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun LocationShareScreen(
    context: Context,
    onLocationSelected: (LocationAttachment) -> Unit,
    modifier: Modifier = Modifier
) {
    var status by remember { mutableStateOf("Location permission is required") }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            shareLastKnownLocation(context, onLocationSelected) { status = it }
        } else {
            status = "Location permission denied"
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(status)
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val permissions = arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
                if (permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                    shareLastKnownLocation(context, onLocationSelected) { status = it }
                } else {
                    permissionLauncher.launch(permissions)
                }
            }
        ) {
            Text("Use current location")
        }
    }
}

private fun shareLastKnownLocation(
    context: Context,
    onLocationSelected: (LocationAttachment) -> Unit,
    onError: (String) -> Unit
) {
    val manager = context.getSystemService(LocationManager::class.java)
    val location = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }
        .maxByOrNull { it.time }
    if (location == null) {
        onError("No recent location is available")
    } else {
        onLocationSelected(LocationAttachment(location.latitude, location.longitude))
    }
}
