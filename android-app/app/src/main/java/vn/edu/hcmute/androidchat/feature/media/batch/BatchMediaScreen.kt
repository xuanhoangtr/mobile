package vn.edu.hcmute.androidchat.feature.media.batch

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.edu.hcmute.androidchat.R
import vn.edu.hcmute.androidchat.feature.chat.ui.ChatStitchTokens
import vn.edu.hcmute.androidchat.feature.media.model.SelectedMedia
import vn.edu.hcmute.androidchat.feature.media.model.UploadedMedia

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchMediaScreen(
    viewModel: BatchMediaViewModel,
    onPick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    conversationId: Long? = null,
    onBackClick: () -> Unit = {},
    onSendUploaded: (List<Pair<UploadedMedia, SelectedMedia>>) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var pickerLaunchError by remember { mutableStateOf<String?>(null) }

    val multiPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        pickerLaunchError = null
        if (uris.isNotEmpty()) {
            viewModel.select(uris.map { it.toString() })
        }
    }

    val launchPicker: () -> Unit = {
        pickerLaunchError = null
        if (onPick != null) {
            onPick()
        } else {
            try {
                multiPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            } catch (_: ActivityNotFoundException) {
                pickerLaunchError = "Không mở được bộ chọn ảnh trên thiết bị"
            } catch (_: SecurityException) {
                pickerLaunchError = "Thiếu quyền truy cập bộ chọn ảnh"
            }
        }
    }

    val uploadedPairs = remember(state.items) {
        state.items.mapNotNull { item ->
            item.uploaded?.let { uploaded -> uploaded to item.selected }
        }
    }
    val hasPendingOrFailed = state.items.any {
        it.phase == BatchItemPhase.READY || it.phase == BatchItemPhase.ERROR
    }
    val isUploading = state.items.any { it.phase == BatchItemPhase.UPLOADING }

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
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.chat_back),
                            tint = ChatStitchTokens.TextPrimary
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Gửi nhiều ảnh",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ChatStitchTokens.TextPrimary
                        )
                        Text(
                            text = if (state.items.isEmpty()) {
                                "Chọn nhiều ảnh cùng lúc"
                            } else {
                                "${uploadedPairs.size}/${state.items.size} ảnh đã tải lên"
                            },
                            fontSize = 12.sp,
                            color = ChatStitchTokens.TextMuted
                        )
                    }
                },
                actions = {
                    if (state.items.isNotEmpty()) {
                        TextButton(onClick = viewModel::clear, enabled = !isUploading) {
                            Text("Xoá hết", color = ChatStitchTokens.BrandBlue)
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (state.items.isNotEmpty()) {
                Surface(color = ChatStitchTokens.CardWhite, tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (hasPendingOrFailed) {
                            OutlinedButton(
                                onClick = { viewModel.uploadAll(conversationId) },
                                enabled = !state.isSelecting,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Text(
                                    text = if (isUploading) "Đang tải..." else "Tải lên tất cả",
                                    color = ChatStitchTokens.BrandBlue
                                )
                            }
                        }
                        if (uploadedPairs.isNotEmpty()) {
                            Button(
                                onClick = { onSendUploaded(uploadedPairs) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ChatStitchTokens.BrandBlue)
                            ) {
                                Text("Gửi ${uploadedPairs.size} ảnh")
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedButton(
                    onClick = launchPicker,
                    enabled = !state.isSelecting && !isUploading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AddPhotoAlternate,
                        contentDescription = null,
                        tint = ChatStitchTokens.BrandBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (state.items.isEmpty()) "Chọn nhiều ảnh" else "Chọn lại bộ ảnh",
                        color = ChatStitchTokens.BrandBlue
                    )
                }
            }

            if (state.isSelecting) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = ChatStitchTokens.BrandBlue
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Đang kiểm tra ảnh đã chọn...", color = ChatStitchTokens.TextSecondary, fontSize = 13.sp)
                    }
                }
            }

            pickerLaunchError?.let { errorMsg ->
                item {
                    BatchErrorBanner(text = errorMsg)
                }
            }

            if (state.selectionErrors.isNotEmpty()) {
                item {
                    BatchErrorBanner(
                        text = "Không thể chọn ${state.selectionErrors.size} tệp không hợp lệ hoặc vượt quá 10 MB."
                    )
                }
            }

            itemsIndexed(
                items = state.items,
                key = { _, item -> item.selected.uri }
            ) { index, item ->
                BatchMediaItemCard(
                    item = item,
                    formattedSize = item.selected.byteSize?.let {
                        Formatter.formatFileSize(context, it)
                    } ?: "Không rõ dung lượng",
                    onRetry = { viewModel.retry(index, conversationId) }
                )
            }
        }
    }
}

@Composable
private fun BatchMediaItemCard(
    item: BatchMediaItem,
    formattedSize: String,
    onRetry: () -> Unit
) {
    Surface(
        color = ChatStitchTokens.CardWhite,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BatchImageThumbnail(
                    media = item.selected,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.selected.name,
                        color = ChatStitchTokens.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "$formattedSize · ${phaseLabel(item.phase)}",
                        color = when (item.phase) {
                            BatchItemPhase.ERROR -> ChatStitchTokens.AlertRed
                            BatchItemPhase.UPLOADED -> ChatStitchTokens.OnlineGreen
                            else -> ChatStitchTokens.TextMuted
                        },
                        fontSize = 12.sp
                    )
                }
                when (item.phase) {
                    BatchItemPhase.UPLOADED -> Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Đã tải lên",
                        tint = ChatStitchTokens.OnlineGreen,
                        modifier = Modifier.size(22.dp)
                    )
                    BatchItemPhase.ERROR -> IconButton(onClick = onRetry) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Thử lại",
                            tint = ChatStitchTokens.BrandBlue
                        )
                    }
                    BatchItemPhase.UPLOADING -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = ChatStitchTokens.BrandBlue
                    )
                    else -> Unit
                }
            }

            if (item.phase == BatchItemPhase.UPLOADING) {
                val total = item.selected.byteSize
                if (total != null && total > 0L) {
                    LinearProgressIndicator(
                        progress = { (item.bytesSent.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = ChatStitchTokens.BrandBlue,
                        trackColor = ChatStitchTokens.SoftBlueContainer
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = ChatStitchTokens.BrandBlue,
                        trackColor = ChatStitchTokens.SoftBlueContainer
                    )
                }
            }

            if (item.phase == BatchItemPhase.ERROR) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = ChatStitchTokens.AlertRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Tải lên thất bại (${item.error?.name ?: "UPLOAD_FAILED"})",
                        color = ChatStitchTokens.AlertRed,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("Thử lại", color = ChatStitchTokens.BrandBlue)
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchImageThumbnail(
    media: SelectedMedia,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = media.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(media.uri)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                val options = BitmapFactory.Options().apply {
                    var sample = 1
                    while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) {
                        sample *= 2
                    }
                    inSampleSize = sample
                }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }.getOrNull()
        }
    }

    Box(
        modifier = modifier.background(ChatStitchTokens.SoftBlueContainer),
        contentAlignment = Alignment.Center
    ) {
        val decoded = bitmap
        if (decoded != null) {
            DisposableEffect(decoded) {
                onDispose { if (!decoded.isRecycled) decoded.recycle() }
            }
            Image(
                bitmap = decoded.asImageBitmap(),
                contentDescription = media.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = null,
                    tint = ChatStitchTokens.BrandBlue,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun BatchErrorBanner(text: String) {
    Surface(
        color = Color(0xFFFFEDEA),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            color = ChatStitchTokens.AlertRed,
            fontSize = 12.sp
        )
    }
}

private fun phaseLabel(phase: BatchItemPhase): String = when (phase) {
    BatchItemPhase.SELECTING -> "Đang đọc"
    BatchItemPhase.READY -> "Sẵn sàng"
    BatchItemPhase.UPLOADING -> "Đang tải lên..."
    BatchItemPhase.UPLOADED -> "Đã tải lên"
    BatchItemPhase.ERROR -> "Lỗi"
}
