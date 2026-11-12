package vn.edu.hcmute.androidchat.feature.media.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.edu.hcmute.androidchat.R
import vn.edu.hcmute.androidchat.feature.chat.ui.ChatStitchTokens
import vn.edu.hcmute.androidchat.feature.media.model.MediaError
import vn.edu.hcmute.androidchat.feature.media.model.MediaPhase
import vn.edu.hcmute.androidchat.feature.media.model.MediaUiState
import vn.edu.hcmute.androidchat.feature.media.model.SelectedMedia
import vn.edu.hcmute.androidchat.feature.media.model.UploadedMedia
import vn.edu.hcmute.androidchat.feature.media.viewmodel.MediaViewModel
import vn.edu.hcmute.androidchat.ui.theme.AndroidChatTheme

private enum class MediaPickerKind { PHOTO, VIDEO, DOCUMENT }

/** MEDIA entrypoint remains independently embeddable; chat navigation can host it later. */
@Composable
fun MediaPickerScreen(viewModel: MediaViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.onPickerResult(uri?.toString())
    }

    MediaPickerContent(
        state = state,
        onChooseClick = { kind ->
            viewModel.pickerOpened()
            val types = when (kind) {
                MediaPickerKind.PHOTO -> arrayOf("image/jpeg", "image/png")
                MediaPickerKind.VIDEO -> arrayOf("video/mp4")
                MediaPickerKind.DOCUMENT -> arrayOf("application/pdf", "text/plain")
            }
            try {
                picker.launch(types)
            } catch (_: ActivityNotFoundException) {
                viewModel.onPickerResult(null)
            }
        },
        onUploadClick = viewModel::upload,
        onCancelClick = viewModel::cancel,
        onRetryClick = viewModel::upload,
        onClearClick = viewModel::clear,
        onPreviewClick = {
            state.selected?.let { media ->
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(Uri.parse(media.uri), media.mimeType)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                } catch (_: ActivityNotFoundException) {
                    viewModel.previewFailed()
                } catch (_: SecurityException) {
                    viewModel.previewFailed()
                }
            }
        },
        selectedMediaContent = { media -> MediaThumbnail(media, viewModel::previewFailed) },
        modifier = modifier
    )
}

/** Stateless rendering surface; previews inject placeholders and never resolve content URIs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaPickerContent(
    state: MediaUiState,
    onChooseClick: (MediaPickerKind) -> Unit,
    onUploadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onRetryClick: () -> Unit,
    onClearClick: () -> Unit,
    onPreviewClick: () -> Unit,
    selectedMediaContent: @Composable (SelectedMedia) -> Unit,
    modifier: Modifier = Modifier
) {
    val media = state.selected
    val busy = state.phase == MediaPhase.UPLOADING || state.phase == MediaPhase.SELECTING

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ChatStitchTokens.ChatSurfaceBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ChatStitchTokens.CardWhite,
                    titleContentColor = ChatStitchTokens.TextPrimary
                ),
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.media_foundation_title),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ChatStitchTokens.TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.media_topbar_subtitle),
                            fontSize = 12.sp,
                            color = ChatStitchTokens.TextMuted
                        )
                    }
                },
                actions = {
                    if (media != null) {
                        TextButton(onClick = onClearClick, enabled = !busy) {
                            Text(
                                text = stringResource(R.string.media_clear),
                                color = ChatStitchTokens.BrandBlue
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (media == null) {
                Surface(color = ChatStitchTokens.CardWhite, tonalElevation = 2.dp) {
                    Text(
                        text = stringResource(R.string.media_supported_summary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        color = ChatStitchTokens.TextMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                MediaActionBar(
                    phase = state.phase,
                    busy = busy,
                    onUploadClick = onUploadClick,
                    onCancelClick = onCancelClick,
                    onRetryClick = onRetryClick,
                    onClearClick = onClearClick
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (media == null) {
                EmptyPickerContent(
                    enabled = !busy,
                    onChooseClick = onChooseClick
                )
            } else {
                SelectedMediaContent(
                    state = state,
                    media = media,
                    onPreviewClick = onPreviewClick,
                    selectedMediaContent = selectedMediaContent
                )
            }
        }
    }
}

@Composable
private fun EmptyPickerContent(
    enabled: Boolean,
    onChooseClick: (MediaPickerKind) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(ChatStitchTokens.SoftBlueContainer),
            contentAlignment = Alignment.Center
        ) {
            Text("＋", color = ChatStitchTokens.BrandBlue, fontSize = 48.sp, fontWeight = FontWeight.Light)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.media_empty_title),
                color = ChatStitchTokens.TextPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.media_empty_description),
                color = ChatStitchTokens.TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MediaKindTile(
                label = stringResource(R.string.media_kind_photos),
                mark = "▧",
                enabled = enabled,
                onClick = { onChooseClick(MediaPickerKind.PHOTO) },
                modifier = Modifier.weight(1f)
            )
            MediaKindTile(
                label = stringResource(R.string.media_kind_videos),
                mark = "▶",
                enabled = enabled,
                onClick = { onChooseClick(MediaPickerKind.VIDEO) },
                modifier = Modifier.weight(1f)
            )
            MediaKindTile(
                label = stringResource(R.string.media_kind_documents),
                mark = "PDF",
                enabled = enabled,
                onClick = { onChooseClick(MediaPickerKind.DOCUMENT) },
                modifier = Modifier.weight(1f)
            )
        }
        if (!enabled) CircularProgressIndicator(color = ChatStitchTokens.BrandBlue)
    }
}

@Composable
private fun MediaKindTile(
    label: String,
    mark: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = ChatStitchTokens.CardWhite,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(ChatStitchTokens.SoftBlueContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = mark,
                    color = ChatStitchTokens.BrandBlue,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (mark == "PDF") 12.sp else 22.sp
                )
            }
            Text(
                text = label,
                color = ChatStitchTokens.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SelectedMediaContent(
    state: MediaUiState,
    media: SelectedMedia,
    onPreviewClick: () -> Unit,
    selectedMediaContent: @Composable (SelectedMedia) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = stringResource(R.string.media_preview_heading),
            color = ChatStitchTokens.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        Surface(
            color = ChatStitchTokens.CardWhite,
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                when {
                    media.mimeType.startsWith("image/") || media.mimeType.startsWith("video/") -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(ChatStitchTokens.SoftBlueContainer)
                        ) {
                            selectedMediaContent(media)
                            if (media.mimeType.startsWith("video/")) {
                                Surface(
                                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                                    color = Color.Black.copy(alpha = 0.68f),
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text(
                                        text = stringResource(R.string.media_kind_videos),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                    else -> DocumentPreview(media = media)
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MediaFileBadge(media = media, size = 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = media.name,
                            color = ChatStitchTokens.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = fileMetadata(media),
                            color = ChatStitchTokens.TextMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onPreviewClick, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(stringResource(R.string.media_open_preview), color = ChatStitchTokens.BrandBlue)
                }
            }
        }
        when (state.phase) {
            MediaPhase.SELECTING -> InlineStatus(text = stringResource(R.string.media_picker_opening))
            MediaPhase.UPLOADING -> UploadProgress(state = state, media = media)
            MediaPhase.UPLOADED -> InlineStatus(
                text = stringResource(R.string.media_uploaded),
                successful = true
            )
            MediaPhase.CANCELLED -> InlineStatus(text = stringResource(R.string.media_cancelled))
            else -> Unit
        }
        state.error?.let { error ->
            Surface(
                color = Color(0xFFFFEDEA),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = stringResource(errorString(error)),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    color = ChatStitchTokens.AlertRed,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun DocumentPreview(media: SelectedMedia) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth().height(164.dp),
        color = ChatStitchTokens.ChatSurfaceBackground,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            MediaFileBadge(media, 58.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                text = when (media.mimeType) {
                    "application/pdf" -> stringResource(R.string.media_document_pdf)
                    "text/plain" -> stringResource(R.string.media_document_text)
                    else -> stringResource(R.string.media_kind_documents)
                },
                color = ChatStitchTokens.TextSecondary,
                fontSize = 13.sp
            )
            media.byteSize?.let {
                Text(
                    text = Formatter.formatFileSize(context, it),
                    color = ChatStitchTokens.TextMuted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun MediaFileBadge(media: SelectedMedia, size: androidx.compose.ui.unit.Dp) {
    val label = when {
        media.mimeType.startsWith("image/") -> "IMG"
        media.mimeType.startsWith("video/") -> "MP4"
        media.mimeType == "application/pdf" -> "PDF"
        else -> "TXT"
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(ChatStitchTokens.SoftBlueContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = ChatStitchTokens.BrandBlue,
            fontSize = if (size < 50.dp) 11.sp else 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun UploadProgress(state: MediaUiState, media: SelectedMedia) {
    val context = LocalContext.current
    val total = media.byteSize
    val progress = total?.takeIf { it > 0 }?.let {
        (state.bytesSent.toFloat() / it.toFloat()).coerceIn(0f, 1f)
    }
    Surface(
        color = ChatStitchTokens.CardWhite,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = ChatStitchTokens.BrandBlue
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        stringResource(R.string.media_uploading),
                        color = ChatStitchTokens.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (total != null && total > 0) {
                            stringResource(
                                R.string.media_progress_detail,
                                Formatter.formatFileSize(context, state.bytesSent),
                                Formatter.formatFileSize(context, total)
                            )
                        } else {
                            stringResource(R.string.media_progress_unknown_total, Formatter.formatFileSize(context, state.bytesSent))
                        },
                        color = ChatStitchTokens.TextMuted,
                        fontSize = 12.sp
                    )
                }
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
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
    }
}

@Composable
private fun InlineStatus(text: String, successful: Boolean = false) {
    Surface(
        color = if (successful) Color(0xFFE7F4EA) else ChatStitchTokens.SoftBlueContainer,
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            color = if (successful) Color(0xFF176B32) else ChatStitchTokens.TextSecondary,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun MediaActionBar(
    phase: MediaPhase,
    busy: Boolean,
    onUploadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onRetryClick: () -> Unit,
    onClearClick: () -> Unit
) {
    Surface(color = ChatStitchTokens.CardWhite, tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (phase == MediaPhase.UPLOADING) {
                    OutlinedButton(
                        onClick = onCancelClick,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Text(stringResource(R.string.media_cancel), color = ChatStitchTokens.TextPrimary)
                    }
                } else {
                    Button(
                        onClick = if (phase == MediaPhase.ERROR || phase == MediaPhase.CANCELLED) onRetryClick else onUploadClick,
                        enabled = !busy && phase != MediaPhase.UPLOADED,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ChatStitchTokens.BrandBlue)
                    ) {
                        Text(
                            stringResource(
                                when (phase) {
                                    MediaPhase.ERROR, MediaPhase.CANCELLED -> R.string.media_retry
                                    MediaPhase.UPLOADED -> R.string.media_uploaded_action
                                    else -> R.string.media_upload
                                }
                            )
                        )
                    }
                }
                OutlinedButton(
                    onClick = onClearClick,
                    enabled = !busy,
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text(stringResource(R.string.media_clear), color = ChatStitchTokens.TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun MediaThumbnail(media: SelectedMedia, onFailure: () -> Unit) {
    val context = LocalContext.current
    val image by produceState<Bitmap?>(initialValue = null, key1 = media.uri) {
        value = withContext(Dispatchers.IO) {
            try {
                if (media.mimeType.startsWith("image/")) {
                    val uri = Uri.parse(media.uri)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                    val options = BitmapFactory.Options().apply {
                        var sample = 1
                        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                        inSampleSize = sample
                    }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                } else if (media.mimeType == "video/mp4" && Build.VERSION.SDK_INT >= 27) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, Uri.parse(media.uri))
                        retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512)
                    } finally {
                        retriever.release()
                    }
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
        if (value == null && (media.mimeType.startsWith("image/") || media.mimeType.startsWith("video/"))) {
            onFailure()
        }
    }
    image?.let { bitmap ->
        DisposableEffect(bitmap) {
            onDispose { if (!bitmap.isRecycled) bitmap.recycle() }
        }
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.media_preview_description),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun fileMetadata(media: SelectedMedia): String = media.byteSize?.let {
    Formatter.formatFileSize(LocalContext.current, it)
} ?: stringResource(R.string.media_size_unknown)

@Composable
private fun MediaPreviewPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatStitchTokens.SoftBlueContainer, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            stringResource(R.string.media_preview_description),
            color = ChatStitchTokens.BrandBlue,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun MediaPickerPreview(state: MediaUiState) {
    AndroidChatTheme {
        MediaPickerContent(
            state = state,
            onChooseClick = {},
            onUploadClick = {},
            onCancelClick = {},
            onRetryClick = {},
            onClearClick = {},
            onPreviewClick = {},
            selectedMediaContent = { MediaPreviewPlaceholder() }
        )
    }
}

private const val MIB = 1024L * 1024L

@Preview(name = "MEDIA — no selection", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun MediaPickerEmptyPreview() = MediaPickerPreview(MediaUiState())

@Preview(name = "MEDIA — selected image", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun MediaPickerSelectedPreview() = MediaPickerPreview(
    MediaUiState(
        selected = SelectedMedia("content://preview/sample-image", "sample_image.jpg", "image/jpeg", 2 * MIB),
        phase = MediaPhase.SELECTED
    )
)

@Preview(name = "MEDIA — uploading", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun MediaPickerUploadingPreview() = MediaPickerPreview(
    MediaUiState(
        selected = SelectedMedia("content://preview/sample-video", "sample_video.mp4", "video/mp4", 10 * MIB),
        phase = MediaPhase.UPLOADING,
        bytesSent = 4 * MIB
    )
)

@Preview(name = "MEDIA — uploaded", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun MediaPickerUploadedPreview() = MediaPickerPreview(
    MediaUiState(
        selected = SelectedMedia("content://preview/sample-file", "sample_document.pdf", "application/pdf", MIB / 2),
        phase = MediaPhase.UPLOADED,
        uploaded = UploadedMedia(mediaId = 42, mimeType = "application/pdf", byteSize = MIB / 2)
    )
)

@Preview(name = "MEDIA — error", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun MediaPickerErrorPreview() = MediaPickerPreview(
    MediaUiState(
        selected = SelectedMedia("content://preview/sample-image", "sample_image.jpg", "image/jpeg", 2 * MIB),
        phase = MediaPhase.ERROR,
        error = MediaError.UPLOAD_FAILED
    )
)

@Preview(name = "MEDIA — selected document", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun MediaPickerDocumentPreview() = MediaPickerPreview(
    MediaUiState(
        selected = SelectedMedia("content://preview/sample-file", "meeting-notes.txt", "text/plain", 12 * 1024),
        phase = MediaPhase.SELECTED
    )
)

private fun errorString(error: MediaError): Int = when (error) {
    MediaError.URI_ACCESS -> R.string.media_error_uri
    MediaError.UNSUPPORTED_TYPE -> R.string.media_error_type
    MediaError.TOO_LARGE -> R.string.media_error_size
    MediaError.EMPTY_FILE -> R.string.media_error_empty
    MediaError.UPLOAD_FAILED -> R.string.media_error_upload
    MediaError.INTEGRATION_BLOCKED -> R.string.media_error_blocked
    MediaError.PREVIEW_FAILED -> R.string.media_error_preview
}
