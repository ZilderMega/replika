package team.replika.engine

/** ETA is for the current stage only; no fabricated weights for the whole pipeline. */
class StageEstimate {
    private var stage = ""
    private var baseline = 0L
    private var started = 0L
    private var done = 0L
    private var total = 0L
    fun update(progress: EngineProgress, now: Long) {
        if (stage != progress.stage || progress.completed < done) {
            stage = progress.stage; started = now; baseline = progress.completed
        }
        done = progress.completed; total = progress.total
    }
    fun remaining(now: Long): Long? {
        val completed = done - baseline
        val elapsed = now - started
        if (total <= 0 || completed <= 0 || elapsed < 2000) return null
        return ((total - done).coerceAtLeast(0).toDouble() * elapsed / completed).toLong()
    }
}
