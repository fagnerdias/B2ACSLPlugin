package com.example.gui.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.gui.model.Generator;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvExporterTest {

    @Test
    void oneRowPerFunctionAndGenerator() {
        var row = ResultComparator.compare(
                "AddRunner__Add",
                ResultComparatorTest.r("AddRunner__Add", Generator.PLUGIN, 15, 15, 0, 0, 2, 2),
                ResultComparatorTest.r("AddRunner__Add", Generator.ATL, 18, 16, 1, 1, -1, -1));
        assertEquals(
                CsvExporter.HEADER + "\r\n"
                        + "AddRunner__Add,Plugin,15,15,0,0,2/2,Different\r\n"
                        + "AddRunner__Add,ATL Rules,18,16,1,1,,Different\r\n",
                CsvExporter.toCsv(List.of(row)));
        assertEquals("\"a,b\"", CsvExporter.cell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvExporter.cell("say \"hi\""));
    }
}
