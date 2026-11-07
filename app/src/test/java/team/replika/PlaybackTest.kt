package team.replika

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTest {
    @Test fun seekIsBoundedByAudioDuration() {
        assertEquals(0, boundedSeek(-300, 5000))
        assertEquals(2500, boundedSeek(2500, 5000))
        assertEquals(5000, boundedSeek(9000, 5000))
        assertEquals(0, boundedSeek(100, 0))
    }

    @Test fun skipButtonsNeverSeekOutsideTheRecording() {
        assertEquals(0, skipPlayback(3000, -10000, 90000))
        assertEquals(20000, skipPlayback(10000, 10000, 90000))
        assertEquals(90000, skipPlayback(85000, 10000, 90000))
    }

    @Test fun timeDisplaysHoursForLongRecordings() {
        assertEquals("00:09", formatPlaybackTime(9000))
        assertEquals("01:22:03", formatPlaybackTime(4923000))
    }

    @Test fun speakerLabelsRemainHumanReadableWithoutInventingNames() {
        assertEquals("Спикер 2", speakerLabel("speaker_2"))
        assertEquals("Неизвестный участник", speakerLabel("speaker_unknown"))
        assertEquals("Неизвестный участник", speakerLabel(""))
    }
    @Test fun playResumesPausedAudioButStartsFreshOtherwise() {
        assertEquals(PlaybackAction.Resume, playbackAction(hasPlayer = true, playing = false))
        assertEquals(PlaybackAction.None, playbackAction(hasPlayer = true, playing = true))
        assertEquals(PlaybackAction.Open, playbackAction(hasPlayer = false, playing = false))
    }
}
