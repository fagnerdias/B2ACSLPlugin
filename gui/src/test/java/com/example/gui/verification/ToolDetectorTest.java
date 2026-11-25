package com.example.gui.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolDetectorTest {

    @Test
    void parsesWhy3ListProvers() {
        String out = """
                Alt-Ergo 2.6.2
                Alt-Ergo 2.6.2 (BV)
                Alt-Ergo 2.6.2 (counterexamples)
                CVC4 1.8
                CVC5 1.1.2 (counterexamples)
                CVC5 1.1.2
                Z3 4.13.3 (noBV)
                """;
        assertEquals(Map.of("Alt-Ergo", "2.6.2", "CVC5", "1.1.2", "Z3", "4.13.3"), ToolDetector.parseWhy3Provers(out));
    }
}
