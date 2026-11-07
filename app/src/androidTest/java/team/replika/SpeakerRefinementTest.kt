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
import android.os.SystemClock

@RunWith(AndroidJUnit4::class)
class SpeakerRefinementTest {
    @Test fun accurateTurnReviewKeepsWordsAndCachedRematchNeedsNoAudio() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = BundledModels(context).prepare()
        val input = File(context.filesDir, "ami-development.wav")
        org.junit.Assume.assumeTrue("Install public AMI audio and human word references explicitly", input.isFile && File(context.filesDir, "ami-development-words.json").isFile)
        val directory = File(context.cacheDir, "speaker-refinement-test").apply { deleteRecursively(); mkdirs() }
        try {
            val engine = LocalTranscriptionEngine(models, context.cacheDir)
            val result = engine.process(input, false, directory) {}
            val state = PipelineCheckpoint.load(directory)
            // English audio cannot use RU ASR as a timed text reference. Use independent AMI human words.
            val reference = JSONObject(File(context.filesDir, "ami-development-words.json").readText()).getJSONArray("words")
            val referenceWords = List(reference.length()) { i -> reference.getJSONObject(i).let { Word(it.getString("text"), it.getLong("start_ms"), it.getLong("end_ms")) } }
            val text = referenceWords.joinToString(" ") { it.text }
            assertTrue(text.isNotBlank())
            val started = SystemClock.elapsedRealtime()
            val candidate = FragmentProcessor.process(input, models, context.cacheDir, 0, result.durationMs,
                "speaker_unknown", mixedLanguage = false, separate = false, denoise = false,
                speakerTurns = true, originalText = text, sourceWords = referenceWords) {}
            val refinementMs = SystemClock.elapsedRealtime()-started
            val candidateText = candidate.utterances.joinToString(" ") { it.text }
            assertEquals(text, candidateText)
            assertTrue(candidate.utterances.map { it.speakerId }.distinct().size >= 2)
            assertTrue(candidate.utterances.all { it.speakerId == "speaker_unknown" || it.speakerId.startsWith("speaker_review_") })
            assertTrue(state.hasCompleteVoiceEvidence)
            File(directory, "audio.pcm").delete()
            val stages = mutableSetOf<String>()
            val rematchStarted = SystemClock.elapsedRealtime()
            engine.process(input, false, directory, forceDiarization = true) { stages += it.stage }
            val rematchMs = SystemClock.elapsedRealtime()-rematchStarted
            val after = PipelineCheckpoint.load(directory)
            assertEquals(state.words, after.words)
            assertFalse(stages.contains("Распознавание речи")); assertFalse(stages.contains("Подготовка звука"))
            assertNull(after.diarizationMetrics["native_calls"])
            assertTrue(requireNotNull(after.diarizationMetrics["cached_blocks"]) > 0)
            File(context.filesDir, "speaker-refinement-proof.json").writeText(JSONObject()
                .put("device", android.os.Build.MODEL).put("fixture", "ami-development.wav")
                .put("refinement_ms", refinementMs).put("rematch_ms", rematchMs)
                .put("initial_diarization_metrics", JSONObject(state.diarizationMetrics))
                .put("rematch_diarization_metrics", JSONObject(after.diarizationMetrics))
                .put("source_words_retained", true).put("native_calls_on_rematch", 0)
                .put("note", "Turn refinement uses independent AMI human word times/text, not RU ASR on English; rematch checks preservation of actual RU ASR separately")
                .put("candidate", JSONObject(candidate.toJson())).toString(2))
        } finally { directory.deleteRecursively() }
    }
}
