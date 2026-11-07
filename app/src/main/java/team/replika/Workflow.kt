package team.replika

sealed interface ProcessingState {
    data class Running(val progress: Float?) : ProcessingState
    data class Error(val message: String) : ProcessingState
    data object Cancelled : ProcessingState
}

fun startProcessing(engineAvailable: Boolean): ProcessingState =
    if (engineAvailable) ProcessingState.Running(null)
    else ProcessingState.Error("Движок распознавания ещё не подключён.")

fun updateProgress(progress: Float): ProcessingState = ProcessingState.Running(progress.coerceIn(0f, 1f))
fun cancelProcessing(): ProcessingState = ProcessingState.Cancelled
