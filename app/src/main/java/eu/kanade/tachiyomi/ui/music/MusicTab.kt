package eu.kanade.tachiyomi.ui.music

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.Tab
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.presentation.core.components.material.Scaffold
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.library.LibraryModeTitleContent
import eu.kanade.tachiyomi.ui.library.LibraryViewMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Music" tab: you add folders with your songs and pick what to play.
 * (Synced lyrics and the dial come in a later step.)
 */
data object MusicTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 7u,
            title = stringResource(R.string.music_tab),
            icon = rememberVectorPainter(Icons.Outlined.MusicNote),
        )

    @Composable
    override fun Content() {
        MusicScreen(
            titleContent = {
                Text(
                    stringResource(R.string.music_tab),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
        )
    }
}

/** The Music screen inside the Library (the Manga / Anime / Novels / Music dropdown). */
@Composable
fun MusicLibraryPanel(
    libraryMode: LibraryViewMode,
    showModeDropdown: Boolean,
    onToggleDropdown: () -> Unit,
    onDismissDropdown: () -> Unit,
    onModeSelected: (LibraryViewMode) -> Unit,
) {
    MusicScreen(
        titleContent = {
            LibraryModeTitleContent(
                title = LibraryToolbarTitle(text = stringResource(R.string.music_tab)),
                showModeDropdown = showModeDropdown,
                onToggleDropdown = onToggleDropdown,
                onDismissDropdown = onDismissDropdown,
                libraryMode = libraryMode,
                onModeSelected = onModeSelected,
            )
        },
    )
}

@Composable
private fun MusicScreen(titleContent: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val folders = remember { mutableStateListOf<MusicFolder>() }
    var loading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf<String?>(null) }
    var sortDescending by remember { mutableStateOf(false) }

    // Load the saved folders once.
    LaunchedEffect(Unit) {
        loading = true
        folders.clear()
        MusicFolderStore.load(context).forEach { folders += MusicScanner.scan(context, it) }
        loading = false
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null && folders.none { it.uri == uri }) {
            MusicFolderStore.keepAccess(context, uri)
            scope.launch {
                folders += MusicScanner.scan(context, uri)
                MusicFolderStore.save(context, folders.map { it.uri })
            }
        }
    }

    // Back closes the search first.
    androidx.activity.compose.BackHandler(enabled = searchQuery != null) { searchQuery = null }

    // Songs shown: filtered by the search text and sorted.
    val query = searchQuery?.trim().orEmpty()
    val shown = folders.map { folder ->
        val tracks = folder.tracks
            .filter { query.isEmpty() || it.title.contains(query, ignoreCase = true) }
            .let { list -> if (sortDescending) list.sortedByDescending { it.title.lowercase() } else list }
        folder.copy(tracks = tracks)
    }

    Scaffold(
        topBar = { scrollBehavior ->
            SearchToolbar(
                titleContent = titleContent,
                searchQuery = searchQuery,
                onChangeSearchQuery = { searchQuery = it },
                actions = {
                    AppBarActions(
                        persistentListOf(
                            AppBar.Action(
                                title = stringResource(R.string.music_add_folder),
                                icon = Icons.Outlined.Add,
                                onClick = { picker.launch(null) },
                            ),
                            AppBar.OverflowAction(
                                title = stringResource(if (sortDescending) R.string.music_sort_az else R.string.music_sort_za),
                                onClick = { sortDescending = !sortDescending },
                            ),
                        ),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = { MusicPlayer.current?.let { MiniPlayer(it) } },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                folders.isEmpty() -> EmptyState(onAdd = { picker.launch(null) })
                else -> TrackList(
                    folders = shown,
                    searching = query.isNotEmpty(),
                    onRemove = { folder ->
                        MusicFolderStore.releaseAccess(context, folder.uri)
                        folders.removeAll { it.uri == folder.uri }
                        MusicFolderStore.save(context, folders.map { it.uri })
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.MusicNote, contentDescription = null, modifier = Modifier.size(48.dp))
        Text(
            stringResource(R.string.music_empty_help),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onAdd) { Text(stringResource(R.string.music_add_folder)) }
    }
}

@Composable
private fun TrackList(folders: List<MusicFolder>, searching: Boolean, onRemove: (MusicFolder) -> Unit) {
    val context = LocalContext.current
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        folders.filter { !searching || it.tracks.isNotEmpty() }.forEach { folder ->
            item(key = "folder_${folder.uri}") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        folder.name,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    IconButton(onClick = { onRemove(folder) }) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.music_remove_folder),
                        )
                    }
                }
                HorizontalDivider()
            }
            if (folder.tracks.isEmpty()) {
                item(key = "empty_${folder.uri}") {
                    Text(
                        stringResource(R.string.music_no_tracks),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(folder.tracks, key = { it.uri.toString() }) { track ->
                val selected = MusicPlayer.current?.uri == track.uri
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { MusicPlayer.play(context, track, folder.tracks) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (selected && MusicPlayer.isPlaying) Icons.Filled.PlayArrow else Icons.Outlined.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        track.title,
                        modifier = Modifier.padding(start = 12.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(track: MusicTrack) {
    val context = LocalContext.current
    var position by remember { mutableStateOf(0) }
    var duration by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    LaunchedEffect(track.uri) {
        while (true) {
            if (!dragging) position = MusicPlayer.positionMs
            duration = MusicPlayer.durationMs
            delay(300)
        }
    }

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            MusicPlayer.error?.let {
                Text(
                    stringResource(R.string.music_play_error, it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Slider(
                value = if (dragging) dragValue else if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    MusicPlayer.seekTo((dragValue * duration).toInt())
                    position = (dragValue * duration).toInt()
                    dragging = false
                },
                enabled = duration > 0,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { MusicPlayer.previous(context) }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.music_previous))
                }
                IconButton(onClick = { MusicPlayer.togglePlay() }, modifier = Modifier.size(56.dp)) {
                    Icon(
                        if (MusicPlayer.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (MusicPlayer.isPlaying) R.string.dial_pause else R.string.music_play,
                        ),
                        modifier = Modifier.size(34.dp),
                    )
                }
                IconButton(onClick = { MusicPlayer.next(context) }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.music_next))
                }
                TextButton(onClick = { SongDialActivity.launch(context, track) }) {
                    Text(stringResource(R.string.song_lyrics_button))
                }
                TextButton(onClick = { MusicPlayer.stop() }) {
                    Text(stringResource(R.string.music_close))
                }
            }
        }
    }
}
