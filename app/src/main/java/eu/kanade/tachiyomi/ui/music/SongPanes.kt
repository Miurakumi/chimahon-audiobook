package eu.kanade.tachiyomi.ui.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R

/** Same look as the lyrics player: cover color (or the app theme) fading to dark, white text. */
@Composable
private fun SongBackdrop(cover: ByteArray?, content: @Composable () -> Unit) {
    val bitmap = remember(cover) { cover?.let { MusicCovers.decodeBitmap(it) } }
    val themeColor = MaterialTheme.colorScheme.primary
    val accent = remember(bitmap, themeColor) { bitmap?.let { MusicCovers.averageColor(it) } ?: themeColor }
    val top = remember(accent) { lerp(accent, Color.Black, 0.25f) }
    val bottom = remember(accent) { lerp(accent, Color.Black, 0.8f) }
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(top, bottom))),
        ) { content() }
    }
}

@Composable
private fun CoverBox(cover: ByteArray?, size: Int) {
    val bitmap = remember(cover) { cover?.let { MusicCovers.decodeBitmap(it) } }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(16.dp)),
        )
    }
}

/** Shown while the lyrics are being searched. */
@Composable
fun SongLoadingPane(title: String, artist: String?, cover: ByteArray?) {
    SongBackdrop(cover) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CoverBox(cover, 160)
            Text(title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
            artist?.let { Text(it, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center) }
            Spacer(Modifier.height(8.dp))
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            Text(
                stringResource(R.string.song_dial_searching),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Shown when no lyrics were found: search again with another name, paste them, or pick a file. */
@Composable
fun SongMissingPane(
    title: String,
    artist: String?,
    cover: ByteArray?,
    initialQuery: String,
    onSearch: (String) -> Unit,
    onPaste: (String) -> Unit,
    onPickFile: () -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var pasting by remember { mutableStateOf(false) }

    SongBackdrop(cover) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.song_dial_back))
            }
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CoverBox(cover, 120)
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge,
                )
                artist?.let { Text(it, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center) }
                Text(
                    stringResource(R.string.song_dial_not_found),
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )

                // Search again with another name.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.song_dial_search_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color.White,
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                            focusedLabelColor = Color.White,
                            unfocusedLabelColor = Color.White.copy(alpha = 0.7f),
                        ),
                    )
                    Button(
                        onClick = { onSearch(query) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    ) { Text(stringResource(R.string.song_dial_retry)) }
                }

                OptionRow(
                    icon = { Icon(Icons.Filled.ContentPaste, contentDescription = null) },
                    title = stringResource(R.string.song_dial_paste),
                    subtitle = stringResource(R.string.song_dial_paste_sub),
                    onClick = { pasting = true },
                )
                OptionRow(
                    icon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                    title = stringResource(R.string.song_dial_pick_file),
                    subtitle = stringResource(R.string.song_dial_file_sub),
                    onClick = onPickFile,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (pasting) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text(stringResource(R.string.song_dial_paste)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text(stringResource(R.string.song_dial_paste_hint)) },
                        minLines = 8,
                        maxLines = 12,
                        keyboardOptions = KeyboardOptions.Default,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.song_dial_paste_note), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pasting = false
                        onPaste(text)
                    },
                    enabled = text.isNotBlank(),
                ) { Text(stringResource(R.string.song_dial_save)) }
            },
            dismissButton = {
                TextButton(onClick = { pasting = false }) { Text(stringResource(R.string.song_dial_cancel)) }
            },
        )
    }
}

@Composable
private fun OptionRow(icon: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        }
    }
}
