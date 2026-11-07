package team.replika.engine

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Disk-backed mono PCM16. No compressed recording or full float waveform in the Java heap. */
class PcmAudio(val file: File, val samples: Long) : AutoCloseable {
    val durationMs get() = samples * 1000 / RATE
    fun read(start: Long, count: Int): FloatArray {
        require(start >= 0 && count >= 0 && start + count <= samples)
        val bytes = ByteArray(count * 2)
        RandomAccessFile(file, "r").use { it.seek(start * 2); it.readFully(bytes) }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count) { buffer.short / 32768f }
    }
    override fun close() { file.delete() }
    companion object { const val RATE = 16000 }
}

/** Stateful low-pass + linear resampler; phase persists across codec buffers. */
class MonoResampler(private val inputRate: Int, private val emit: (Float) -> Unit) {
    private var index = 0L
    private var next = 0.0
    private var previous = 0f
    private var filtered = 0f
    private val alpha = (1 - kotlin.math.exp(-2 * Math.PI * 7000 / inputRate)).toFloat()
    fun push(value: Float) {
        filtered += alpha * (value - filtered)
        val current = if (inputRate > PcmAudio.RATE) filtered else value
        while (next <= index) {
            val fraction = (next - (index - 1)).toFloat().coerceIn(0f, 1f)
            emit(if (index == 0L) current else previous + (current - previous) * fraction)
            next += inputRate.toDouble() / PcmAudio.RATE
        }
        previous = current
        index++
    }
}

class AudioDecoder(private val cacheDir: File) {
    suspend fun decode(input: File): PcmAudio {
        val output = File.createTempFile("replika-pcm-", ".pcm", cacheDir)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var success = false
        var total = 0L
        try {
            extractor.setDataSource(input.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("В файле не найдена аудиодорожка")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec.configure(format, null, null, 0)
            codec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var inputEnded = false
            var outputEnded = false
            val info = MediaCodec.BufferInfo()
            output.outputStream().buffered().use { sink ->
                fun resampler() = MonoResampler(rate) { value ->
                    val sample = (value.coerceIn(-1f, 1f) * 32767).toInt()
                    sink.write(sample and 255); sink.write((sample shr 8) and 255)
                    total++
                }
                var converter = resampler()
                var idleSince = System.nanoTime()
                while (!outputEnded) {
                    currentCoroutineContext().ensureActive()
                    if (!inputEnded) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buffer = requireNotNull(codec.getInputBuffer(i))
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(i, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val i = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            require(total == 0L || newRate == rate) { "Изменение частоты внутри записи не поддерживается" }
                            rate = newRate
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            require(rate > 0 && channels in 1..8) { "Неподдерживаемый аудиоформат" }
                            encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "Неподдерживаемая разрядность PCM" }
                            if (total == 0L) converter = resampler()
                        }
                        else -> if (i >= 0) {
                            try {
                                val b = requireNotNull(codec.getOutputBuffer(i)).order(ByteOrder.LITTLE_ENDIAN)
                                b.position(info.offset); b.limit(info.offset + info.size)
                                val width = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                require(b.remaining() % (channels * width) == 0)
                                while (b.remaining() >= channels * width) {
                                    var mono = 0f
                                    repeat(channels) { mono += if (width == 4) b.float else b.short / 32768f }
                                    require(mono.isFinite()) { "Повреждённые аудиоданные" }
                                    converter.push(mono / channels)
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                idleSince = System.nanoTime()
                            } finally { codec.releaseOutputBuffer(i, false) }
                        }
                    }
                    check(System.nanoTime() - idleSince < 30_000_000_000L) { "Декодер перестал отвечать" }
                }
            }
            require(total > 0) { "Аудиофайл пуст" }
            success = true
            return PcmAudio(output, total)
        } finally {
            try { codec?.release() } finally { extractor.release(); if (!success) output.delete() }
        }
    }
}
