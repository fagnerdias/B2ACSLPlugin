package com.example.gui.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.gui.model.FunctionResult;
import com.example.gui.model.FunctionStatus;
import com.example.gui.model.Generator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The six statuses of the pipeline's progress dialog (FramaCRunner.markCompletedFromSummary). */
class FunctionStatusTest {

    private static FunctionStatus status(int total, int proved, int failures, int timeouts, int exit, boolean timedOut) {
        return new FunctionResult("f", Generator.PLUGIN, total, proved, failures, timeouts, -1, -1, exit, timedOut,
                List.of(), 0).status();
    }

    @Test
    void sameClassificationAsThePipeline() {
        assertEquals(FunctionStatus.PROVED, status(13, 13, 0, 0, 0, false));
        assertEquals(FunctionStatus.PROVED, status(0, 0, 0, 0, 0, false));
        assertEquals(FunctionStatus.TIMEOUT, status(30, 27, 0, 3, 0, false));
        assertEquals(FunctionStatus.PARTIAL, status(18, 16, 1, 1, 0, false));
        assertEquals(FunctionStatus.FAILED, status(5, 0, 5, 0, 0, false));
        assertEquals(FunctionStatus.FAILED, status(10, 10, 0, 0, 1, false));
        assertEquals(FunctionStatus.TIMEOUT, status(0, 0, 0, 0, -1, true));
        assertEquals("Running…", FunctionStatus.RUNNING.label());
    }
}
