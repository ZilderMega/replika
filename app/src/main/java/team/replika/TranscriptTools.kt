package team.replika

object TranscriptTools {
    fun applySpeakerSuggestions(original: Transcript, suggestions: List<team.replika.engine.SpeakerSuggestion>): Transcript {
        require(suggestions.all { suggestion -> original.utterances.any { it == suggestion.original } }) { "Стенограмма изменилась. Повторите поиск похожих голосов." }
        val replacements = suggestions.associate { it.original to it.speakerId }
        return Transcript(original.utterances.map { line -> replacements[line]?.let { line.copy(speakerId = it) } ?: line })
    }
    fun split(line: Utterance, boundaryMs: Long, characterOffset: Int): List<Utterance> {
        require(boundaryMs > line.startMs && boundaryMs < line.endMs) { "Время смены голоса должно быть внутри реплики" }
        require(characterOffset in 1 until line.text.length) { "Поставьте курсор между двумя частями текста" }
        require(!line.text[characterOffset - 1].isLetterOrDigit() || !line.text[characterOffset].isLetterOrDigit()) {
            "Разделяйте текст между словами"
        }
        val first = line.text.substring(0, characterOffset).trim()
        val second = line.text.substring(characterOffset).trim()
        require(first.isNotEmpty() && second.isNotEmpty()) { "Обе части должны содержать текст" }
        return listOf(line.copy(endMs = boundaryMs, text = first),
            line.copy(speakerId = "speaker_unknown", startMs = boundaryMs, text = second))
    }
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
