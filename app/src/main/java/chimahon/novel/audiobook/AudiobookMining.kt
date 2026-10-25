package chimahon.novel.audiobook

import chimahon.anki.LazyAnkiSentenceAudioProvider

/**
 * Bridge between the dial and the dictionary popup: while the dial is open and a word is
 * looked up, [provider] cuts the audio of that line so Anki's {sentence-audio} marker gets it.
 */
object AudiobookMining {
    @Volatile
    var provider: LazyAnkiSentenceAudioProvider? = null
}
