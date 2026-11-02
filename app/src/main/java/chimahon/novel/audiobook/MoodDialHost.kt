package chimahon.novel.audiobook

/** What the mood dial needs from whoever owns the background music (audiobook dial or novel reader). */
interface MoodDialHost {
    val music: BackgroundMusic
    val autoMusic: Boolean
    fun toggleAuto()
    fun pickMood(mood: Mood)
}
