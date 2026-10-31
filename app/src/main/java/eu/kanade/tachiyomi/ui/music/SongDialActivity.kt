package eu.kanade.tachiyomi.ui.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import chimahon.DictionaryRepository
import chimahon.MediaInfo
import chimahon.novel.audiobook.AudiobookMining
import chimahon.novel.audiobook.AudiobookScreen
import chimahon.novel.audiobook.AudiobookStore
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPopupWebViewWarmup
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryPaths
import eu.kanade.tachiyomi.ui.reader.viewer.OcrLookupPopup
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest

/**
 * The dial for songs: the same screen as the audiobook dial, but the "book" is a song and the
 * subtitle lines are its lyrics (found online, next to the file, or picked by hand).
 * Tapping a word opens the dictionary and can create the Anki card with the song's audio line.
 */
class SongDialActivity : ComponentActivity() {

    private enum class Phase { Loading, Ready, Missing }

    private var phase by mutableStateOf(Phase.Loading)
    private lateinit var songUri: Uri
    private lateinit var songTitle: String
    private lateinit var key: String
    private var info: SongInfo? = null
    private var cover: ByteArray? = null

    // ---- dictionary popup (same pieces the reader uses)
    private val prefs: DictionaryPreferences = Injekt.get()
    private val profile by lazy {
        prefs.profileResolver.resolve(novelId = "songs", sourceId = 0L, sourceLang = "ja")
    }
    private val termPaths by lazy { getDictionaryPaths(this, profile) }
    private var popupWebView: WebView? = null
    private var lookup by mutableStateOf<Lookup?>(null)
    private var popupVisible by mutableStateOf(false)
    private var lookupDeferred: Deferred<DictionaryRepository.LookupResult2>? = null

    private data class Lookup(
        val word: String,
        val sentence: String,
        val offset: Int,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString == null) {
            finish()
            return
        }
        songUri = Uri.parse(uriString)
        songTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        key = "song_" + sha1(uriString).take(20)

        // The dial has its own player: stop the one of the Music tab so they don't overlap.
        MusicPlayer.stop()

        DictionaryPopupWebViewWarmup.warm(this, profile.languageCode)
        lifecycleScope.launch(Dispatchers.Default) {
            Injekt.get<DictionaryRepository>().warmUp(termPaths, profile.id)
        }

        setContent {
            TachiyomiTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (phase) {
                        Phase.Loading -> Surface(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier.fillMaxSize().statusBarsPadding().padding(32.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.song_dial_searching), textAlign = TextAlign.Center)
                            }
                        }
                        Phase.Missing -> MissingPane()
                        Phase.Ready -> AudiobookScreen(
                            bookKey = key,
                            title = songTitle,
                            fontName = "",
                            fontUrl = null,
                            fontSize = 22f,
                            lineHeight = 1.6f,
                            characterSpacing = 0f,
                            isPopupActive = popupVisible,
                            onLookup = { word, sentence, offset, x, y, w, h ->
                                startLookup(word, sentence, offset, x, y, w, h)
                            },
                            onDismissPopup = ::dismissPopup,
                            onClose = { finish() },
                        )
                    }
                    PopupOverlay()
                }
            }
        }

        findLyrics()
    }

    // ---------------------------------------------------------------- lyrics

    private fun findLyrics(query: String? = null) {
        phase = Phase.Loading
        lifecycleScope.launch {
            val song = withContext(Dispatchers.IO) { LyricsResolver.readInfo(this@SongDialActivity, songUri, songTitle) }
            info = song
            cover = song.cover
            when (val result = LyricsResolver.resolve(this@SongDialActivity, songUri, song, songTitle, key, query)) {
                is LyricsResolver.Result.Found -> openDial(result, resetPosition = false)
                LyricsResolver.Result.NotFound -> phase = Phase.Missing
            }
        }
    }

    private fun importLyrics(picked: Uri) {
        val song = info ?: return
        phase = Phase.Loading
        lifecycleScope.launch {
            when (val result = LyricsResolver.importFile(this@SongDialActivity, picked, song, key)) {
                is LyricsResolver.Result.Found -> openDial(result, resetPosition = true)
                LyricsResolver.Result.NotFound -> phase = Phase.Missing
            }
        }
    }

    /** Writes the song + lyrics into the dial's saved state, then shows the dial. */
    private fun openDial(found: LyricsResolver.Result.Found, resetPosition: Boolean) {
        val saved = AudiobookStore.load(this, key)
        AudiobookStore.save(
            this,
            key,
            saved.copy(
                audioUri = songUri.toString(),
                srtUri = Uri.fromFile(found.srt).toString(),
                audioName = songTitle,
                srtName = found.srt.name,
                lineIndex = if (resetPosition) 0 else saved.lineIndex,
                moodLabels = if (resetPosition) emptyList() else saved.moodLabels,
            ),
        )
        phase = Phase.Ready
    }

    @Composable
    private fun MissingPane() {
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importLyrics(uri)
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(songTitle, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.song_dial_not_found),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.song_dial_pick_file))
                }
                var query by remember { mutableStateOf(LyricsResolver.cleanName(songTitle)) }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.song_dial_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { findLyrics(query) }) {
                    Text(stringResource(R.string.song_dial_retry))
                }
                OutlinedButton(onClick = { finish() }) {
                    Text(stringResource(R.string.song_dial_back))
                }
            }
        }
    }

    // ---------------------------------------------------------------- dictionary popup

    private fun startLookup(word: String, sentence: String, offset: Int, x: Float, y: Float, w: Float, h: Float) {
        lookupDeferred?.cancel()
        lookupDeferred = lifecycleScope.async(Dispatchers.Default) {
            Injekt.get<DictionaryRepository>().lookup(word.trim(), termPaths, profile.languageCode)
        }
        lookup = Lookup(word, sentence, offset, x, y, w, h)
        popupVisible = true
    }

    private fun dismissPopup() {
        popupVisible = false
        lookupDeferred?.cancel()
        lookupDeferred = null
    }

    @Composable
    private fun PopupOverlay() {
        val state = lookup ?: return
        val repo = remember { Injekt.get<DictionaryRepository>() }
        BackHandler(enabled = popupVisible) { dismissPopup() }
        val webView = remember {
            popupWebView ?: DictionaryPopupWebViewWarmup.acquire(this, profile.languageCode).also { popupWebView = it }
        }
        OcrLookupPopup(
            visible = popupVisible,
            lookupString = if (popupVisible) state.word else "",
            fullText = state.sentence,
            charOffset = state.offset,
            onDismiss = ::dismissPopup,
            webView = webView,
            repository = repo,
            anchorX = state.x,
            anchorY = state.y,
            anchorWidth = state.w,
            anchorHeight = state.h,
            isVertical = false,
            activeProfile = profile,
            type = "novel",
            mediaInfo = MediaInfo(mangaTitle = songTitle, chapterName = "", coverBytes = cover),
            mediaRequest = AudiobookMining.provider?.let { chimahon.anki.AnkiMediaRequest(it) },
            onRequestScreenshot = null,
            onCropTriggered = null,
            initialLookupDeferred = if (popupVisible) lookupDeferred else null,
            usePopup = false,
            onTermMatched = null,
            onContentReadyChange = {},
            dismissOnOutsideTap = false,
            modifier = Modifier,
            titleId = null,
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        lookupDeferred?.cancel()
        popupWebView?.let { DictionaryPopupWebViewWarmup.recycle(this, it) }
        popupWebView = null
        lookup = null
    }

    companion object {
        private const val EXTRA_URI = "song_uri"
        private const val EXTRA_TITLE = "song_title"

        fun launch(context: Context, track: MusicTrack) {
            context.startActivity(
                Intent(context, SongDialActivity::class.java)
                    .putExtra(EXTRA_URI, track.uri.toString())
                    .putExtra(EXTRA_TITLE, track.title),
            )
        }

        private fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
