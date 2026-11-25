package com.example.gui.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class CFunctionScannerTest {

    @Test
    void keepsDefinitionsAndIgnoresPrototypesAndCalls() {
        String c = """
                #include "iter_services.h"
                void iter_services__INITIALISATION(void);
                void iter_services__init_iter(_Bool *flag);

                void iter_services__INITIALISATION(void)
                {
                  void iter_services__initialisation(void);
                    iter_services__init_iter(&f);
                }

                void iter_services__init_iter(bool *flag)
                {
                }
                void iter_services__next_iter(bool *flag, int32_t *elt) {
                }
                static int32_t helper(int32_t x)
                {
                }
                """;
        assertEquals(
                List.of("iter_services__INITIALISATION", "iter_services__init_iter", "iter_services__next_iter"),
                CFunctionScanner.scanText(c));
    }

    @Test
    void addRunnerFunctionsInFileOrder() {
        String c = """
                void AddRunner__INITIALISATION(void)
                {
                }
                void AddRunner__Add(int32_t xx)
                {
                }
                void AddRunner__count(int32_t *rr)
                {
                }
                """;
        assertEquals(
                List.of("AddRunner__INITIALISATION", "AddRunner__Add", "AddRunner__count"), CFunctionScanner.scanText(c));
    }
}
