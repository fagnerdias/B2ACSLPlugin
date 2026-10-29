package com.example.comparator.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** JSON reading/writing of the report records (Jackson). */
public final class Json {

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private Json() {}

    public static void write(Path file, Object value) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        MAPPER.writeValue(file.toFile(), value);
    }

    public static <T> T read(Path file, Class<T> type) throws IOException {
        return MAPPER.readValue(file.toFile(), type);
    }
}
