package com.example;

import java.nio.file.Path;

/** Caminhos da biblioteca ACSL em {@code src/main/resources/lib/B2ACSLLib}. */
public final class B2AcslLibraryPaths {

    public static final String RESOURCE_ROOT = "lib/B2ACSLLib";
    public static final String RESOURCE_ROOT_SLASH = "/" + RESOURCE_ROOT + "/";
    public static final String DEV_ROOT_RELATIVE = "src/main/resources/" + RESOURCE_ROOT;
    public static final String SYMBOL_DEPENDENCY_MAP_RESOURCE = "/b2acsl/symbol_dependency_map.json";

    private B2AcslLibraryPaths() {}

    public static Path devRoot() {
        return Path.of(System.getProperty("user.dir", "."))
                .resolve(DEV_ROOT_RELATIVE)
                .toAbsolutePath()
                .normalize();
    }

    public static String classpathResource(String relativePath) {
        return RESOURCE_ROOT_SLASH + relativePath.replace('\\', '/');
    }

    public static String classpathResourceStreamName(String relativePath) {
        return RESOURCE_ROOT + "/" + relativePath.replace('\\', '/');
    }
}
