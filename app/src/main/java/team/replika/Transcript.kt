package team.replika

import org.json.JSONArray
import org.json.JSONObject

data class Utterance(val speakerId: String, val startMs: Long, val endMs: Long, val text: String) {
    init { require(startMs >= 0 && endMs >= startMs); require(speakerId.isNotBlank()) }
}

data class Transcript(val utterances: List<Utterance>) {
    fun toJson(): String = JSONObject().put("transcript", JSONArray().apply {
        utterances.forEach { line -> put(JSONObject().apply {
            put("speaker_id", line.speakerId)
            put("start_ms", line.startMs)
            put("end_ms", line.endMs)
            put("text", line.text)
        }) }
    }).toString(2)
}

/** Implementations must perform all inference on the Android device. */
interface TranscriptionEngine {
    suspend fun transcribe(audio: java.io.File, onProgress: (Float) -> Unit): Transcript
}
