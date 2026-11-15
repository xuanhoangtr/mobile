package vn.edu.hcmute.androidchat.feature.media.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CallEnd
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.webrtc.EglBase
import org.webrtc.VideoTrack

@Composable
fun GroupCallScreen(state: GroupCallUiState) {
    GroupCallContent(
        state = state,
        localVideoTrack = null,
        remoteVideoTracks = emptyMap(),
        eglContext = null,
        onToggleMic = {},
        onToggleCamera = {},
        onLeave = {},
        onBackClick = null
    )
}

@Composable
fun GroupCallScreen(
    viewModel: GroupCallViewModel,
    modifier: Modifier = Modifier,
    onBackClick: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val localTrack by viewModel.localVideoTrack.collectAsState()
    val remoteTracks by viewModel.remoteVideoTracks.collectAsState()

    GroupCallContent(
        state = state,
        localVideoTrack = localTrack,
        remoteVideoTracks = remoteTracks,
        eglContext = viewModel.eglBaseContext,
        onToggleMic = viewModel::toggleMicrophone,
        onToggleCamera = viewModel::toggleCamera,
        onLeave = {
            viewModel.leaveCall()
            onBackClick()
        },
        onBackClick = onBackClick,
        modifier = modifier
    )
}

@Composable
private fun GroupCallContent(
    state: GroupCallUiState,
    localVideoTrack: VideoTrack?,
    remoteVideoTracks: Map<Long, VideoTrack>,
    eglContext: EglBase.Context?,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onLeave: () -> Unit,
    onBackClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val orderedParticipants = state.participantIds.toList().sorted()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .statusBarsPadding()
    ) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onBackClick != null) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Quay lại",
                        tint = Color.White
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Cuộc gọi nhóm",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${state.participantIds.size} người tham gia · ${state.phase.name}",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            }
        }

        if (state.error != null) {
            Surface(
                color = Color(0xFFB42318),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text(
                    text = state.error,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        // Participant Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(orderedParticipants, key = { it }) { participantId ->
                val isLocal = participantId == state.localUserId
                val track = if (isLocal) {
                    if (state.cameraEnabled) localVideoTrack else null
                } else {
                    remoteVideoTracks[participantId]
                }
                ParticipantTile(
                    label = if (isLocal) "Bạn (#$participantId)" else "Thành viên #$participantId",
                    videoTrack = track,
                    eglContext = eglContext,
                    isLocal = isLocal
                )
            }
        }

        // Bottom Controls
        Surface(
            color = Color(0xFF1E293B),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GroupControlButton(
                    icon = if (state.microphoneMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = if (state.microphoneMuted) "Bật mic" else "Tắt mic",
                    containerColor = if (state.microphoneMuted) Color(0xFF475569) else Color(0xFF334155),
                    onClick = onToggleMic
                )
                GroupControlButton(
                    icon = if (state.cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                    label = if (state.cameraEnabled) "Tắt cam" else "Bật cam",
                    containerColor = if (state.cameraEnabled) Color(0xFF334155) else Color(0xFF475569),
                    onClick = onToggleCamera
                )
                GroupControlButton(
                    icon = Icons.Filled.CallEnd,
                    label = "Rời cuộc gọi",
                    containerColor = Color(0xFFD92D20),
                    onClick = onLeave
                )
            }
        }
    }
}

@Composable
private fun ParticipantTile(
    label: String,
    videoTrack: VideoTrack?,
    eglContext: EglBase.Context?,
    isLocal: Boolean
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.82f),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1E293B)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (videoTrack != null && eglContext != null) {
                WebRtcVideoSurface(
                    videoTrack = videoTrack,
                    eglContext = eglContext,
                    mirror = isLocal,
                    zOrderMediaOverlay = false,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E3A8A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }

            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            ) {
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun GroupControlButton(
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
            modifier = Modifier.size(52.dp)
        ) {
            IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
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
