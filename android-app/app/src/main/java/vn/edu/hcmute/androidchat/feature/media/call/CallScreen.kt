package vn.edu.hcmute.androidchat.feature.media.call

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.Locale

@Composable
fun CallScreen(
    viewModel: CallViewModel,
    modifier: Modifier = Modifier,
    onCallEnded: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsState()
    val localTrack by viewModel.localVideoTrack.collectAsState()
    val remoteTrack by viewModel.remoteVideoTrack.collectAsState()
    val context = LocalContext.current
    val eglContext = viewModel.eglBaseContext

    LaunchedEffect(state.phase) {
        if (state.phase == CallPhase.ENDED || state.phase == CallPhase.DECLINED) {
            onCallEnded?.invoke()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .statusBarsPadding()
    ) {
        if (state.media == CallMedia.VIDEO && remoteTrack != null && eglContext != null) {
            WebRtcVideoSurface(
                videoTrack = remoteTrack,
                eglContext = eglContext,
                mirror = false,
                zOrderMediaOverlay = false,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AudioOrPlaceholderOverlay(
                state = state,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Top call header (visible over video or audio screen)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 24.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.35f),
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    text = callStatusLabel(state),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = Color(0xFFB42318).copy(alpha = 0.9f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = state.error.orEmpty(),
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Picture-in-picture local video preview
        if (state.media == CallMedia.VIDEO && state.cameraEnabled && localTrack != null && eglContext != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 72.dp, end = 16.dp)
                    .size(width = 108.dp, height = 152.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1E293B),
                shadowElevation = 6.dp
            ) {
                WebRtcVideoSurface(
                    videoTrack = localTrack,
                    eglContext = eglContext,
                    mirror = true,
                    zOrderMediaOverlay = true,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Bottom controls bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp)
        ) {
            if (state.phase == CallPhase.INCOMING_RINGING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallCircleButton(
                        icon = Icons.Filled.CallEnd,
                        label = "Từ chối",
                        containerColor = Color(0xFFD92D20),
                        onClick = viewModel::declineIncoming
                    )
                    CallCircleButton(
                        icon = Icons.Filled.Call,
                        label = "Chấp nhận",
                        containerColor = Color(0xFF16A34A),
                        onClick = { viewModel.acceptWebRtcCall(context) }
                    )
                }
            } else {
                Surface(
                    color = Color(0xFF1E293B).copy(alpha = 0.88f),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CallCircleButton(
                            icon = if (state.microphoneMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            label = if (state.microphoneMuted) "Bật mic" else "Tắt mic",
                            containerColor = if (state.microphoneMuted) Color(0xFF475569) else Color(0xFF334155),
                            onClick = viewModel::toggleMicrophone
                        )
                        CallCircleButton(
                            icon = Icons.AutoMirrored.Filled.VolumeUp,
                            label = if (state.speakerEnabled) "Loa ngoài" else "Loa trong",
                            containerColor = if (state.speakerEnabled) Color(0xFF2563EB) else Color(0xFF334155),
                            onClick = viewModel::toggleSpeaker
                        )
                        if (state.media == CallMedia.VIDEO) {
                            CallCircleButton(
                                icon = if (state.cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                                label = if (state.cameraEnabled) "Tắt cam" else "Bật cam",
                                containerColor = if (state.cameraEnabled) Color(0xFF334155) else Color(0xFF475569),
                                onClick = viewModel::toggleCamera
                            )
                            CallCircleButton(
                                icon = Icons.Filled.Cameraswitch,
                                label = "Đổi cam",
                                containerColor = Color(0xFF334155),
                                onClick = viewModel::switchCamera
                            )
                        }
                        CallCircleButton(
                            icon = Icons.Filled.CallEnd,
                            label = "Kết thúc",
                            containerColor = Color(0xFFD92D20),
                            onClick = {
                                viewModel.end()
                                onCallEnded?.invoke()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun WebRtcVideoSurface(
    videoTrack: VideoTrack?,
    eglContext: EglBase.Context,
    mirror: Boolean,
    zOrderMediaOverlay: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val renderer = remember(eglContext, zOrderMediaOverlay) {
        SurfaceViewRenderer(context).apply {
            init(eglContext, null)
            setEnableHardwareScaler(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setMirror(mirror)
            setZOrderMediaOverlay(zOrderMediaOverlay)
        }
    }

    DisposableEffect(renderer, videoTrack) {
        if (videoTrack != null) {
            runCatching { videoTrack.addSink(renderer) }
        }
        onDispose {
            if (videoTrack != null) {
                runCatching { videoTrack.removeSink(renderer) }
            }
        }
    }

    DisposableEffect(renderer) {
        onDispose {
            runCatching { renderer.release() }
        }
    }

    AndroidView(
        factory = { renderer },
        update = { it.setMirror(mirror) },
        modifier = modifier
    )
}

@Composable
private fun AudioOrPlaceholderOverlay(
    state: CallUiState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(Color(0xFF1E3A8A)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(54.dp)
            )
        }
        Text(
            text = state.peerName?.takeIf { it.isNotBlank() }
                ?: state.peerId?.let { "Người dùng #$it" }
                ?: "Cuộc gọi",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = if (state.media == CallMedia.VIDEO) "Cuộc gọi Video" else "Cuộc gọi Thoại",
            color = Color(0xFF94A3B8),
            fontSize = 14.sp
        )
    }
}

@Composable
private fun CallCircleButton(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            color = containerColor,
            shape = CircleShape,
            modifier = Modifier.size(54.dp)
        ) {
            IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Text(
            text = label,
            color = Color(0xFFE2E8F0),
            fontSize = 11.sp
        )
    }
}

private fun callStatusLabel(state: CallUiState): String = when (state.phase) {
    CallPhase.IDLE -> "Sẵn sàng"
    CallPhase.OUTGOING_RINGING -> "Đang đổ chuông..."
    CallPhase.INCOMING_RINGING -> "Cuộc gọi đến..."
    CallPhase.CONNECTED -> formatElapsed(state.elapsedSeconds)
    CallPhase.RECONNECTING -> "Đang kết nối lại..."
    CallPhase.DECLINED -> "Đã từ chối"
    CallPhase.ENDED -> "Đã kết thúc"
    CallPhase.ERROR -> "Lỗi kết nối"
}

private fun formatElapsed(seconds: Long): String {
    val mins = seconds / 60L
    val secs = seconds % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
