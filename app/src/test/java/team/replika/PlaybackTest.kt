package team.replika

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTest {
    @Test fun playResumesPausedAudioButStartsFreshOtherwise() {
        assertEquals(PlaybackAction.Resume, playbackAction(hasPlayer = true, playing = false))
        assertEquals(PlaybackAction.None, playbackAction(hasPlayer = true, playing = true))
        assertEquals(PlaybackAction.Open, playbackAction(hasPlayer = false, playing = false))
    }
}
