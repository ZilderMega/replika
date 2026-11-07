package team.replika

import org.junit.Assert.*
import org.junit.Test

class WorkflowTest {
    @Test fun processingRequiresEngine() {
        assertEquals(ProcessingState.Error("Движок распознавания ещё не подключён."), startProcessing(false))
    }

    @Test fun processingStartsWhenEngineAvailable() {
        assertEquals(ProcessingState.Running(null), startProcessing(true))
    }

    @Test fun progressIsBoundedAndCancellationIsExplicit() {
        assertEquals(ProcessingState.Running(1f), updateProgress(2f))
        assertEquals(ProcessingState.Running(0f), updateProgress(-1f))
        assertEquals(ProcessingState.Cancelled, cancelProcessing())
    }
}
