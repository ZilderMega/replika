package team.replika.engine

import org.json.JSONObject
import java.io.File
import java.util.Locale

interface Punctuator : AutoCloseable {
    fun pieces(text: String): List<String>
    fun count(text: String): Int
    fun punctuate(text: String, active: () -> Boolean): String
}

/** BERT labels all words in one inference, rather than generating the transcript again. */
class LocalPunctuation(models: ModelFiles, threads: Int) : Punctuator {
    private val tokenizer = BertWordPiece(JSONObject(File(models.path("punctuation-tokenizer.json")).readText()))
    private val labels = JSONObject(File(models.path("punctuation-config.json")).readText()).getJSONObject("id2label")
    private var handle = open(models.path("punctuation.onnx"), threads)
    override fun count(text: String) = tokenizer.encode(text).ids.size - 2
    override fun pieces(text: String) = PunctuationPieces.split(text, ::count, 160)
    override fun punctuate(text: String, active: () -> Boolean): String {
        if (!active()) return text
        val encoded = tokenizer.encode(text)
        val predicted = classify(handle, encoded.ids)
        if (!active()) return text
        val candidate = encoded.words.mapIndexed { index, word ->
            PunctuationLabels.apply(word, labels.getString(predicted[encoded.starts[index]].toString()))
        }.joinToString(" ")
        return TextGuard.accept(text, candidate)
    }
    override fun close() { if (handle != 0L) { release(handle); handle = 0L } }
    private external fun open(path: String, threads: Int): Long
    private external fun classify(handle: Long, ids: LongArray): IntArray
    private external fun release(handle: Long)
    companion object { init { System.loadLibrary("replika-llm") } }
}

object PunctuationLabels {
    private val punctuation = mapOf("O" to "", "PERIOD" to ".", "COMMA" to ",", "QUESTION" to "?",
        "TIRE" to " —", "VOSKL" to "!", "DVOETOCHIE" to ":", "PERIODCOMMA" to ";",
        "DEFIS" to "-", "QUESTIONVOSKL" to "?!", "MNOGOTOCHIE" to "...")
    fun apply(word: String, label: String): String {
        val cased = when {
            label.startsWith("UPPER_TOTAL_") -> word.uppercase(Locale.ROOT)
            label.startsWith("UPPER_") -> word.replaceFirstChar { it.titlecase(Locale.ROOT) }
            else -> word
        }
        // An ASR token can already contain punctuation. Preserve it instead of doubling it.
        return cased + if (word.lastOrNull()?.isLetterOrDigit() == true) punctuation[label.substringAfterLast('_')].orEmpty() else ""
    }
}

data class EncodedWords(val words: List<String>, val ids: LongArray, val starts: List<Int>)

/** The bundled tokenizer uses case-sensitive BERT normalization and greedy WordPiece. */
class BertWordPiece(json: JSONObject) {
    private val punctuationTypes = setOf(Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt())
    private val model = json.getJSONObject("model")
    private val vocab = model.getJSONObject("vocab").let { values -> values.keys().asSequence().associateWith { values.getLong(it) } }
    private val limit = model.getInt("max_input_chars_per_word")
    private val unknown = vocab.getValue("[UNK]")
    fun encode(text: String): EncodedWords {
        val words = Regex("\\S+").findAll(text).map { it.value }.toList()
        val ids = mutableListOf(vocab.getValue("[CLS]"))
        val starts = mutableListOf<Int>()
        words.forEach { word ->
            starts += ids.size
            val basics = basicTokens(word)
            if (basics.isEmpty()) ids += unknown else basics.forEach { ids += wordPieces(it) }
        }
        ids += vocab.getValue("[SEP]")
        return EncodedWords(words, ids.toLongArray(), starts)
    }

    private fun wordPieces(token: String): List<Long> {
        val points = token.codePoints().toArray()
        if (points.size > limit) return listOf(unknown)
        val pieces = mutableListOf<Long>()
        var start = 0
        while (start < points.size) {
            var end = points.size
            var found: Long? = null
            while (end > start) {
                val part = (if (start == 0) "" else "##") + String(points, start, end - start)
                found = vocab[part]
                if (found != null) break
                end--
            }
            if (found == null) return listOf(unknown)
            pieces += found; start = end
        }
        return pieces
    }

    private fun basicTokens(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() { if (current.isNotEmpty()) { result += current.toString(); current.clear() } }
        text.codePoints().forEach { point ->
            val type = Character.getType(point)
            val chinese = point in 0x4E00..0x9FFF || point in 0x3400..0x4DBF || point in 0x20000..0x2A6DF ||
                point in 0x2A700..0x2B73F || point in 0x2B740..0x2B81F || point in 0x2B820..0x2CEAF ||
                point in 0xF900..0xFAFF || point in 0x2F800..0x2FA1F
            val punctuation = point in 33..47 || point in 58..64 || point in 91..96 || point in 123..126 ||
                type in punctuationTypes
            when {
                Character.isWhitespace(point) || Character.isSpaceChar(point) -> flush()
                point == 0 || point == 0xFFFD || type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() -> Unit
                punctuation || chinese -> { flush(); result += String(Character.toChars(point)) }
                else -> current.appendCodePoint(point)
            }
        }
        flush()
        return result
    }
}
