package team.replika

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import team.replika.engine.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class SpeakerAnchorTest {
    @Test fun confirmedSampleProposesIdsWithoutChangingOriginal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = BundledModels(context).prepare()
        val input = File(context.filesDir, "window-test.mp3")
        val directory = File(context.cacheDir, "speaker-anchor-test").apply { deleteRecursively(); mkdirs() }
        try {
            val result = LocalTranscriptionEngine(models, context.cacheDir).process(input, false, directory) {}
            val raw = result.raw.toJson()
            val checkpoint = PipelineCheckpoint.load(directory)
            val suggestions = SpeakerAssistance.confirmAndSuggest(input, models, context.cacheDir, checkpoint,
                Utterance("speaker_2", 3000, 9000, ""), result.raw) {}
            assertTrue("A clean sample should find the same public monologue voice", suggestions.isNotEmpty())
            assertTrue(File(directory, "confirmed-voices.json").isFile)
            assertEquals(raw, result.raw.toJson())
            val reviewed = TranscriptTools.applySpeakerSuggestions(result.raw, listOf(suggestions.first()))
            assertEquals(result.raw.utterances.map { it.text }, reviewed.utterances.map { it.text })
            assertEquals(result.raw.utterances.map { it.startMs to it.endMs }, reviewed.utterances.map { it.startMs to it.endMs })
            assertEquals("speaker_2", reviewed.utterances.first { it.startMs == suggestions.first().original.startMs }.speakerId)
            File(context.filesDir, "speaker-anchor-proof.json").writeText(JSONObject().put("device", android.os.Build.MODEL)
                .put("fixture", "window-test.mp3").put("proposals", suggestions.size).put("selected_proposals_applied", 1)
                .put("original_unchanged", true).put("words_times_preserved", true).toString(2))
        } finally { directory.deleteRecursively() }
    }
}
