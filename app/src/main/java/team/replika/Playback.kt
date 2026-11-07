package team.replika

enum class PlaybackAction { Open, Resume, None }

fun playbackAction(hasPlayer: Boolean, playing: Boolean): PlaybackAction = when {
    !hasPlayer -> PlaybackAction.Open
    playing -> PlaybackAction.None
    else -> PlaybackAction.Resume
}
