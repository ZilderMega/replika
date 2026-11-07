package team.replika

enum class PlaybackAction { Open, Resume, None }

fun playbackAction(hasPlayer: Boolean, playing: Boolean): PlaybackAction = when {
    !hasPlayer -> PlaybackAction.Open
    playing -> PlaybackAction.None
    else -> PlaybackAction.Resume
}

fun boundedSeek(positionMs: Int, durationMs: Int): Int = positionMs.coerceIn(0, durationMs.coerceAtLeast(0))
fun skipPlayback(positionMs: Int, deltaMs: Int, durationMs: Int): Int =
    (positionMs.toLong() + deltaMs.toLong()).coerceIn(0L, durationMs.coerceAtLeast(0).toLong()).toInt()

fun speakerLabel(id: String): String {
    val number = id.removePrefix("speaker_").toIntOrNull()
    return if (id.startsWith("speaker_") && number != null && number > 0) "Спикер $number" else "Неизвестный участник"
}

fun formatPlaybackTime(positionMs: Int): String {
    val seconds = positionMs.coerceAtLeast(0) / 1000
    val hours = seconds / 3600
    return if (hours > 0) "%02d:%02d:%02d".format(hours, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}
