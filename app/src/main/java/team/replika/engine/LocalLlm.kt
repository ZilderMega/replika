package team.replika.engine

/** One instance per pipeline, opened after ASR has released its native memory. */
class LocalLlm(path: String, threads: Int) : Punctuator {
    private var handle = open(path, threads)
    init { check(handle != 0L) { "Не удалось загрузить локальную LLM" } }
    override fun punctuate(text: String, active: () -> Boolean): String {
        val prompt = "<|im_start|>system\nRestore punctuation and letter case in Russian text. Keep every word unchanged and in order. Return only the text.<|im_end|>\n<|im_start|>user\n$text<|im_end|>\n<|im_start|>assistant\n"
        val budget = (count(text) * 2 + 32).coerceIn(32, 384)
        return String(generate(handle, prompt.toByteArray(Charsets.UTF_8), budget, active), Charsets.UTF_8)
    }
    override fun count(text: String) = tokenCount(handle, text.toByteArray(Charsets.UTF_8))
    override fun pieces(text: String): List<String> = PunctuationPieces.split(text, ::count)
    override fun close() { if (handle != 0L) { release(handle); handle = 0 } }
    private external fun open(path: String, threads: Int): Long
    private external fun tokenCount(handle: Long, text: ByteArray): Int
    private external fun generate(handle: Long, prompt: ByteArray, maxTokens: Int, active: () -> Boolean): ByteArray
    private external fun release(handle: Long)
    companion object { init { System.loadLibrary("replika-llm") } }
}

object PunctuationPieces {
    /** Bound both prompt and output by actual model tokens; never cut through a word. */
    fun split(text: String, count: (String) -> Int, targetTokens: Int = 160): List<String> {
        val words = Regex("\\S+").findAll(text).map { it.value }.toList()
        val pieces = mutableListOf<String>()
        var from = 0
        while (from < words.size) {
            var low = from + 1
            var high = minOf(words.size, from + targetTokens)
            var end = low
            while (low <= high) {
                val mid = (low + high) / 2
                if (count(words.subList(from, mid).joinToString(" ")) <= targetTokens) {
                    end = mid; low = mid + 1
                } else high = mid - 1
            }
            pieces += words.subList(from, end).joinToString(" ")
            from = end
        }
        return pieces
    }
}
