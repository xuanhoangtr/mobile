package vn.edu.hcmute.androidchat.feature.media.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import vn.edu.hcmute.androidchat.R
import vn.edu.hcmute.androidchat.feature.chat.ui.ChatStitchTokens
import kotlin.coroutines.resume

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationShareScreen(
    context: Context,
    onLocationSelected: (LocationAttachment) -> Unit,
    modifier: Modifier = Modifier,
    onBackClick: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var selectedLocation by remember { mutableStateOf<LocationAttachment?>(null) }
    var labelInput by remember { mutableStateOf("") }
    var isFetching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Nhấn để lấy vị trí hiện tại hoặc chọn điểm mẫu") }

    val triggerFetch: () -> Unit = {
        scope.launch {
            isFetching = true
            status = "Đang tìm tín hiệu GPS/Mạng..."
            val fetched = fetchBestLocation(context)
            isFetching = false
            if (fetched != null) {
                selectedLocation = fetched
                status = "Đã xác định vị trí hiện tại"
            } else {
                status = "Không lấy được GPS (mất tín hiệu trong nhà). Bạn có thể chọn nhanh vị trí UTE."
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            triggerFetch()
        } else {
            status = "Quyền truy cập vị trí bị từ chối. Bạn vẫn có thể chọn vị trí mẫu bên dưới."
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ChatStitchTokens.ChatSurfaceBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ChatStitchTokens.CardWhite,
                    titleContentColor = ChatStitchTokens.TextPrimary
                ),
                navigationIcon = {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.chat_back),
                                tint = ChatStitchTokens.TextPrimary
                            )
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Chia sẻ vị trí",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ChatStitchTokens.TextPrimary
                        )
                        Text(
                            text = "Gửi toạ độ GPS vào cuộc trò chuyện",
                            fontSize = 12.sp,
                            color = ChatStitchTokens.TextMuted
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(color = ChatStitchTokens.CardWhite, tonalElevation = 3.dp) {
                Button(
                    onClick = {
                        selectedLocation?.let { loc ->
                            val attachment = loc.copy(label = labelInput.trim().ifBlank { loc.label })
                            LocationSharePolicy.validate(attachment)
                            onLocationSelected(attachment)
                        }
                    },
                    enabled = selectedLocation != null && !isFetching,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ChatStitchTokens.BrandBlue)
                ) {
                    Text("Gửi vị trí")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                color = ChatStitchTokens.CardWhite,
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(ChatStitchTokens.SoftBlueContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.LocationOn,
                                contentDescription = null,
                                tint = ChatStitchTokens.BrandBlue,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedLocation?.let {
                                    labelInput.trim().ifBlank { it.label ?: "Vị trí đã chọn" }
                                } ?: "Chưa chọn vị trí",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ChatStitchTokens.TextPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = selectedLocation?.formattedCoordinates() ?: status,
                                fontSize = 13.sp,
                                color = ChatStitchTokens.TextMuted
                            )
                        }
                        if (isFetching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = ChatStitchTokens.BrandBlue
                            )
                        }
                    }

                    if (selectedLocation != null) {
                        Text(
                            text = status,
                            fontSize = 12.sp,
                            color = ChatStitchTokens.OnlineGreen
                        )
                    }

                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        label = { Text("Nhãn địa điểm (tuỳ chọn)") },
                        placeholder = { Text("Ví dụ: Cổng chính HCMUTE") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !isFetching,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ChatStitchTokens.BrandBlue),
                    onClick = {
                        val permissions = arrayOf(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        )
                        if (permissions.any {
                                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                            }
                        ) {
                            triggerFetch()
                        } else {
                            permissionLauncher.launch(permissions)
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MyLocation,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Vị trí hiện tại")
                }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !isFetching,
                    shape = RoundedCornerShape(14.dp),
                    onClick = {
                        val preset = LocationPresets.UTE_CAMPUS
                        selectedLocation = preset
                        if (labelInput.isBlank()) {
                            labelInput = preset.label.orEmpty()
                        }
                        status = "Đã chọn vị trí mẫu ĐH SPKT TP.HCM"
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.School,
                        contentDescription = null,
                        tint = ChatStitchTokens.BrandBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("UTE Campus", color = ChatStitchTokens.BrandBlue)
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private suspend fun fetchBestLocation(context: Context): LocationAttachment? {
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val providers = listOfNotNull(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )

    val lastKnown = providers
        .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull { it.time }

    if (lastKnown != null) {
        return LocationAttachment(lastKnown.latitude, lastKnown.longitude)
    }

    val activeProvider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
        ?: return null

    val fresh: Location? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                runCatching {
                    manager.getCurrentLocation(
                        activeProvider,
                        signal,
                        ContextCompat.getMainExecutor(context)
                    ) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                }.onFailure {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    } else {
        withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        runCatching { manager.removeUpdates(this) }
                        if (continuation.isActive) continuation.resume(location)
                    }

                    @Deprecated("Deprecated in Android API")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                continuation.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
                runCatching {
                    manager.requestLocationUpdates(
                        activeProvider,
                        0L,
                        0f,
                        listener,
                        Looper.getMainLooper()
                    )
                }.onFailure {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    return fresh?.let { LocationAttachment(it.latitude, it.longitude) }
}
