package com.example.gui.model;

import java.nio.file.Path;
import java.util.List;

/**
 * The project chosen in step 1.
 *
 * @param folder project root (contains {@code bdp/} and {@code lang/c/})
 * @param name display name (editable, defaults to the folder name)
 * @param functions the selected functions, in detection order
 */
public record ProjectInfo(Path folder, String name, List<String> functions) {

    public ProjectInfo {
        functions = List.copyOf(functions);
    }
}
