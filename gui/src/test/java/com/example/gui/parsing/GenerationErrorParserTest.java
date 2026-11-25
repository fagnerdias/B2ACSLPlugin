package com.example.gui.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class GenerationErrorParserTest {

    @Test
    void findsFunctionAndRule() {
        String out = """
                [ATL] Applying the transformation rules...
                ERRO: Rule 'OperationToContract' failed on AddRunner__count: feature 'postcondition' not found
                """;
        assertEquals("AddRunner__count", GenerationErrorParser.function(null, out));
        assertEquals("OperationToContract", GenerationErrorParser.rule(null, out));
    }

    @Test
    void nothingToFind() {
        String msg = "ERRO: ghost_operations.ci seria gerado por Customer e por Price; use pastas de saida diferentes";
        assertNull(GenerationErrorParser.function(msg, ""));
        assertNull(GenerationErrorParser.rule(msg, ""));
    }
}
