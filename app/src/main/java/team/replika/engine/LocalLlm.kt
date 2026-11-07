package team.replika.engine

/** One instance per pipeline, opened after ASR has released its native memory. */
class LocalLlm(path: String, threads: Int) : AutoCloseable {
    private var handle = open(path, threads)
    init { check(handle != 0L) { "Не удалось загрузить локальную LLM" } }
    fun punctuate(text: String, active: () -> Boolean): String {
        val prompt = "<|im_start|>system\nRestore punctuation and letter case in Russian text. Keep every word unchanged and in order. Return only the text.<|im_end|>\n<|im_start|>user\n$text<|im_end|>\n<|im_start|>assistant\n"
        return String(generate(handle, prompt.toByteArray(Charsets.UTF_8), 256, active), Charsets.UTF_8)
    }
    override fun close() { if (handle != 0L) { release(handle); handle = 0 } }
    private external fun open(path: String, threads: Int): Long
    private external fun generate(handle: Long, prompt: ByteArray, maxTokens: Int, active: () -> Boolean): ByteArray
    private external fun release(handle: Long)
    companion object { init { System.loadLibrary("replika-llm") } }
}
