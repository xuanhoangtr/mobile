package vn.edu.hcmute.androidchat.feature.media.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

@Composable
fun CallPermissionButton(context: Context, media: CallMedia, onGranted: () -> Unit) {
    var status by remember { mutableStateOf("Permissions required") }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            onGranted()
        } else {
            status = "Microphone and camera permissions are required"
        }
    }
    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (media == CallMedia.VIDEO) add(Manifest.permission.CAMERA)
    }
    val granted = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(granted) {
        if (granted) onGranted()
    }
    if (!granted) {
        Button(onClick = { launcher.launch(permissions.toTypedArray()) }) {
            Text(status)
        }
    }
}
