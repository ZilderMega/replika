package team.replika.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import kotlin.random.Random

class LongProcessingTest {
    @Test fun longPunctuationKeepsEveryWordAndBoundsPieces() {
        val text = (1..400).joinToString(" ") { "реплика$it" }
        val pieces = PunctuationPieces.split(text, { it.split(' ').size }, 40)
        assertEquals(text, pieces.joinToString(" "))
        assertEquals(10, pieces.size)
        assertTrue(pieces.all { it.split(' ').size <= 40 })
        assertEquals(listOf("длинноеслово", "да"), PunctuationPieces.split("длинноеслово да", { it.length }, 5))
    }

    @Test fun checkpointRestoresWindowsVoicesAndPunctuationWithoutReadingUncommittedFile() {
        val directory = Files.createTempDirectory("checkpoint-test").toFile()
        try {
            val state = PipelineCheckpoint(directory).apply {
                signature = "recording"; samples = 60_000 * 16L; recognizedMs = 12_000; diarizedSamples = 8000
                words += Word("привет", 100, 500); spans += SpeakerSpan(0, 600, 7)
                prototypes += floatArrayOf(.25f, .75f); edits[0] = "Привет!"
                stagesMs["ASR"] = 1200; elapsedMs = 1500; punctuationMode = "fast"
            }
            state.save()
            java.io.File(directory, "checkpoint.json.tmp").writeText("incomplete")
            val restored = PipelineCheckpoint.load(directory)
            assertEquals(12_000L, restored.recognizedMs)
            assertEquals(state.words, restored.words)
            assertEquals(state.spans, restored.spans)
            assertArrayEquals(state.prototypes.single(), restored.prototypes.single(), 0f)
            assertEquals("Привет!", restored.edits[0])
            assertEquals("fast", restored.punctuationMode)
            assertFalse(restored.finished)
            assertEquals("speaker_1", restored.raw().utterances.single().speakerId)
            restored.elapsedMs = 2200
            restored.edits[1] = "Да."
            restored.saveEdit(1, "Да.")
            val afterEdit = PipelineCheckpoint.load(directory)
            assertEquals("Да.", afterEdit.edits[1])
            assertEquals(2200L, afterEdit.elapsedMs)
            afterEdit.save()
            assertFalse(java.io.File(directory, "edit-1.json").exists())
            assertEquals("Да.", PipelineCheckpoint.load(directory).edits[1])
        } finally { directory.deleteRecursively() }
    }

    @Test fun estimateUsesOnlyWorkSinceResumeAndResetsAtNextStage() {
        val estimate = StageEstimate()
        estimate.update(EngineProgress("ASR", 0f, 50, 100), 1000)
        assertNull(estimate.remaining(2000))
        estimate.update(EngineProgress("ASR", 0f, 60, 100), 6000)
        assertEquals(20_000L, estimate.remaining(6000))
        estimate.update(EngineProgress("Speakers", 0f, 0, 100), 7000)
        assertNull(estimate.remaining(8000))
    }

    @Test fun fastAlignmentMatchesFullIntervalScanOnOverlapsAndNonMonotoneWordEnds() {
        val random = Random(42)
        val spans = List(150) {
            val from = random.nextLong(0, 120_000)
            SpeakerSpan(from, from + random.nextLong(100, 3000), random.nextInt(4))
        }
        val words = List(500) {
            val from = random.nextLong(0, 120_000)
            Word("слово$it", from, from + random.nextLong(20, 1000))
        }.sortedBy { it.startMs }
        val ids = spans.sortedBy { it.startMs }.map { it.id }.distinct().withIndex().associate { it.value to "speaker_${it.index + 1}" }
        val reference = words.map { word ->
            val overlaps = spans.groupBy { it.id }.mapValues { (_, group) ->
                group.sumOf { (minOf(word.endMs, it.endMs) - maxOf(word.startMs, it.startMs)).coerceAtLeast(0) }
            }.filterValues { it > 0 }.entries.sortedByDescending { it.value }
            if (overlaps.isEmpty() || (overlaps.size > 1 && overlaps[1].value * 2 >= overlaps[0].value)) "speaker_unknown"
            else ids.getValue(overlaps[0].key)
        }
        val actual = Alignment.assemble(words, spans, 130_000).flatMap { line -> line.text.split(' ').map { line.speakerId } }
        assertEquals(reference, actual)
    }

    @Test fun wordPieceAndLabelsPreserveHyphensNumbersAndUnknownWords() {
        val json = JSONObject("""{"model":{"max_input_chars_per_word":100,"vocab":{"[UNK]":1,"[CLS]":2,"[SEP]":3,"при":4,"##вет":5,"мир":6,"-":7,"12":8}}}""")
        val encoded = BertWordPiece(json).encode("привет мир-12 чужое")
        assertArrayEquals(longArrayOf(2, 4, 5, 6, 7, 8, 1, 3), encoded.ids)
        assertEquals(listOf(1, 3, 6), encoded.starts)
        assertEquals("Привет,", PunctuationLabels.apply("привет", "UPPER_COMMA"))
        assertEquals("12", TextGuard.accept("12", PunctuationLabels.apply("12", "UPPER_TOTAL_O")))
        assertEquals("Мир!", PunctuationLabels.apply("Мир!", "LOWER_PERIOD"))
    }
}
