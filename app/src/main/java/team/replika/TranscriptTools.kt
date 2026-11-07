package team.replika

object TranscriptTools {
    /** A review/display layer. The recognizer's original text is never overwritten. */
    private val terms = listOf("ю туб" to "YouTube", "ютуб" to "YouTube", "ютюб" to "YouTube", "джава" to "Java",
        "джаве" to "Java", "джаву" to "Java", "котлин" to "Kotlin", "гитхаб" to "GitHub", "гитхабе" to "GitHub",
        "андроид" to "Android", "джетпак компоуз" to "Jetpack Compose")
        .map { (alias, canonical) -> Regex("(?iu)(?<![\\p{L}\\p{N}])${Regex.escape(alias)}(?![\\p{L}\\p{N}])") to canonical }
    fun terms(text: String): String = terms.fold(text) { value, (pattern, canonical) -> pattern.replace(value, canonical) }
    fun withTerms(transcript: Transcript) = Transcript(transcript.utterances.map { it.copy(text = terms(it.text)) })

    fun reassignSpeaker(original: Transcript, from: String, to: String): Transcript {
        require(from.isNotBlank() && from != "speaker_unknown" && !from.startsWith("speaker_review_") && to.isNotBlank())
        return Transcript(original.utterances.map { if (it.speakerId == from) it.copy(speakerId = to) else it })
    }

    fun replace(original: Transcript, index: Int, replacement: List<Utterance>, durationMs: Long): Transcript {
        require(index in original.utterances.indices && replacement.isNotEmpty()) { "Реплика больше не доступна" }
        replacement.forEach {
            require(it.text.isNotBlank() && it.speakerId.isNotBlank()) { "Укажите участника и текст" }
            require(it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= durationMs) { "Проверьте границы реплики" }
        }
        return Transcript(original.utterances.take(index) + replacement + original.utterances.drop(index + 1))
    }
}
