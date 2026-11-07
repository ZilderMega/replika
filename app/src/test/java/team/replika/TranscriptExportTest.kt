package team.replika

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptExportTest {
    @Test fun exportPreservesUnicodeSpeakersAndMilliseconds() {
        val transcript = Transcript(listOf(Utterance("speaker_1", 1250, 4380, "Добрый день!")))
        val entry = JSONObject(transcript.toJson()).getJSONArray("transcript").getJSONObject(0)
        assertEquals("speaker_1", entry.getString("speaker_id"))
        assertEquals(1250L, entry.getLong("start_ms"))
        assertEquals(4380L, entry.getLong("end_ms"))
        assertEquals("Добрый день!", entry.getString("text"))
    }

    @Test fun silenceExportsEmptyArray() {
        assertEquals(0, JSONObject(Transcript(emptyList()).toJson()).getJSONArray("transcript").length())
    }
}
