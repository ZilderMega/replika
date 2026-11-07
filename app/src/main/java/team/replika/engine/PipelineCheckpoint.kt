package team.replika.engine

import org.json.JSONArray
import org.json.JSONObject
import team.replika.Transcript
import team.replika.Utterance
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Only complete ASR windows / diarization blocks / punctuation pieces are committed. */
class PipelineCheckpoint(val directory: File) {
    var signature = ""
    var samples = 0L
    var recognizedMs = 0L
    var diarizedSamples = 0L
    var elapsedMs = 0L
    var finished = false
    var punctuated = false
    var punctuationMode = ""
    var denoised = false
    var speechReady = false
    val speechRegions = mutableListOf<LongRange>()
    var rejected = 0
    val stagesMs = linkedMapOf<String, Long>()
    val words = mutableListOf<Word>()
    val spans = mutableListOf<SpeakerSpan>()
    val prototypes = mutableListOf<FloatArray>()
    val voiceProfiles = mutableListOf<VoiceProfile>()
    var voiceMatcherVersion = 0
    var expectedSpeakers = 0

    fun resetSpeakers(expected: Int) {
        spans.clear(); prototypes.clear(); voiceProfiles.clear(); diarizedSamples = 0
        edits.clear(); rejected = 0; punctuated = false; finished = false
        voiceMatcherVersion = VoiceMatcher.VERSION; expectedSpeakers = expected
    }
    // Indices refer to deterministic token-budgeted pieces of the assembled transcript.
    val edits = linkedMapOf<Int, String>()
    val pcmFile get() = File(directory, "audio.pcm")
    val durationMs get() = samples * 1000 / PcmAudio.RATE

    fun raw() = Transcript(Alignment.assemble(words, spans, durationMs))

    fun save() {
        directory.mkdirs()
        val json = JSONObject().put("schema", 2).put("signature", signature).put("samples", samples)
            .put("recognized_ms", recognizedMs).put("diarized_samples", diarizedSamples)
            .put("elapsed_ms", elapsedMs).put("finished", finished).put("punctuated", punctuated)
            .put("punctuation_mode", punctuationMode)
            .put("denoised", denoised).put("speech_ready", speechReady)
            .put("speech_regions", JSONArray().apply { speechRegions.forEach { put(JSONArray(listOf(it.first, it.last))) } })
            .put("rejected", rejected).put("stages_ms", JSONObject(stagesMs))
            .put("words", JSONArray().apply { words.forEach { put(JSONArray(listOf(it.text, it.startMs, it.endMs))) } })
            .put("spans", JSONArray().apply { spans.forEach { put(JSONArray(listOf(it.startMs, it.endMs, it.id))) } })
            .put("prototypes", JSONArray().apply { prototypes.forEach { put(JSONArray(it.toList())) } })
            .put("voice_matcher_version", voiceMatcherVersion).put("expected_speakers", expectedSpeakers)
            .put("voice_profiles", JSONArray().apply { voiceProfiles.forEach { profile ->
                put(JSONObject().put("centroid", JSONArray(profile.centroid.toList())).put("support_ms", profile.supportMs)
                    .put("examples", JSONArray().apply { profile.examples.forEach { put(JSONArray(it.toList())) } }))
            } })
            .put("edits", JSONObject(edits.mapKeys { it.key.toString() }))
        writeAtomically(File(directory, "checkpoint.json"), json.toString())
        editFiles().forEach { it.delete() }
    }

    private fun editFiles() = directory.listFiles().orEmpty().filter { it.name.matches(Regex("edit-\\d+\\.json")) }

    fun saveEdit(index: Int, text: String) {
        // Small atomic records keep punctuation from rewriting the whole interview for every fragment.
        val record = JSONObject().put("signature", signature).put("mode", punctuationMode).put("index", index)
            .put("text", text).put("elapsed_ms", elapsedMs).put("rejected", rejected)
        writeAtomically(File(directory, "edit-$index.json"), record.toString())
    }

    companion object {
        fun resultJson(result: EngineResult): JSONObject = JSONObject().put("raw", JSONObject(result.raw.toJson()))
            .put("formatted", JSONObject(result.formatted.toJson())).put("duration_ms", result.durationMs)
            .put("elapsed_ms", result.elapsedMs).put("stages_ms", JSONObject(result.stagesMs)).put("rejected_llm_edits", result.rejectedEdits)

        fun load(directory: File): PipelineCheckpoint {
            val state = PipelineCheckpoint(directory)
            val file = File(directory, "checkpoint.json")
            if (!file.isFile) return state
            val json = JSONObject(file.readText())
            require(json.getInt("schema") == 2) { "Несовместимое сохранённое задание" }
            state.signature = json.getString("signature")
            state.samples = json.getLong("samples")
            state.recognizedMs = json.getLong("recognized_ms")
            state.diarizedSamples = json.getLong("diarized_samples")
            state.elapsedMs = json.getLong("elapsed_ms")
            state.finished = json.getBoolean("finished")
            state.punctuated = json.getBoolean("punctuated")
            state.punctuationMode = json.optString("punctuation_mode")
            state.voiceMatcherVersion = json.optInt("voice_matcher_version")
            state.expectedSpeakers = json.optInt("expected_speakers")
            json.optJSONArray("voice_profiles")?.let { rows -> repeat(rows.length()) {
                val row = rows.getJSONObject(it)
                fun vector(array: JSONArray) = FloatArray(array.length()) { i -> array.getDouble(i).toFloat() }
                val examples = row.getJSONArray("examples")
                state.voiceProfiles += VoiceProfile(vector(row.getJSONArray("centroid")), row.getLong("support_ms"),
                    MutableList(examples.length()) { i -> vector(examples.getJSONArray(i)) })
            } }
            state.denoised = json.optBoolean("denoised")
            state.speechReady = json.optBoolean("speech_ready")
            json.optJSONArray("speech_regions")?.let { rows -> repeat(rows.length()) {
                val row = rows.getJSONArray(it); state.speechRegions += row.getLong(0)..row.getLong(1)
            } }
            state.rejected = json.getInt("rejected")
            json.getJSONObject("stages_ms").let { stages -> stages.keys().forEach { state.stagesMs[it] = stages.getLong(it) } }
            json.getJSONArray("words").let { rows -> repeat(rows.length()) {
                val row = rows.getJSONArray(it); state.words += Word(row.getString(0), row.getLong(1), row.getLong(2))
            } }
            json.getJSONArray("spans").let { rows -> repeat(rows.length()) {
                val row = rows.getJSONArray(it); state.spans += SpeakerSpan(row.getLong(0), row.getLong(1), row.getInt(2))
            } }
            json.getJSONArray("prototypes").let { rows -> repeat(rows.length()) {
                val row = rows.getJSONArray(it); state.prototypes += FloatArray(row.length()) { index -> row.getDouble(index).toFloat() }
            } }
            json.getJSONObject("edits").let { edits -> edits.keys().forEach { state.edits[it.toInt()] = edits.getString(it) } }
            state.editFiles().forEach { edit ->
                val record = JSONObject(edit.readText())
                if (record.getString("signature") == state.signature && record.getString("mode") == state.punctuationMode) {
                    state.edits[record.getInt("index")] = record.getString("text")
                    state.elapsedMs = maxOf(state.elapsedMs, record.getLong("elapsed_ms"))
                    state.rejected = maxOf(state.rejected, record.getInt("rejected"))
                }
            }
            require(state.samples >= 0 && state.recognizedMs in 0..state.durationMs && state.diarizedSamples in 0..state.samples)
            return state
        }

        fun writeAtomically(file: File, text: String) {
            val pending = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(pending).use { output -> output.write(text.toByteArray(Charsets.UTF_8)); output.fd.sync() }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        fun transcript(json: JSONObject): Transcript = json.getJSONArray("transcript").let { rows ->
            Transcript(List(rows.length()) { i -> rows.getJSONObject(i).let {
                Utterance(it.getString("speaker_id"), it.getLong("start_ms"), it.getLong("end_ms"), it.getString("text"))
            } })
        }
    }
}
