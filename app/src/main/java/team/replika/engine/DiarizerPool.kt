package team.replika.engine

import com.k2fsa.sherpa.onnx.*

/** Each concurrent native call owns its model; the requested CPU budget is divided, not multiplied. */
class DiarizerPool(models: ModelFiles, threads: Int, provider: String, workers: Int) : AutoCloseable {
    val engines = mutableListOf<OfflineSpeakerDiarization>()
    init {
        require(workers in 1..2 && threads >= workers)
        try {
            repeat(workers) {
                engines += OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
                    segmentation = OfflineSpeakerSegmentationModelConfig(pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                        model = models.path("segmentation.onnx")), numThreads = threads/workers, provider = provider),
                    embedding = SpeakerEmbeddingExtractorConfig(model = models.path("embedding.onnx"), numThreads = threads/workers, provider = provider),
                    clustering = FastClusteringConfig(threshold = .5f), minDurationOn = .15f, minDurationOff = .3f))
            }
        } catch (e: Throwable) { close(); throw e }
    }
    override fun close() { engines.forEach { it.release() }; engines.clear() }
}
