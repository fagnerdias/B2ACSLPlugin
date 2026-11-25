package com.example.gui.generation;

import com.example.B2AcslLibraryPaths;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Locates the ATL Rules jar and the ACSL library the rules side needs on disk. The user never
 * selects them; each one is resolved in this order:
 *
 * <ol>
 *   <li>an explicit override: {@code -Db2acsl.gui.rulesJar} / {@code -Db2acsl.gui.libDir};
 *   <li>the copy bundled in this application's jar, extracted once to {@code
 *       ~/.cache/b2acsl-gui/};
 *   <li>the source tree ({@code rules/dist/b2acsl.jar}, {@code
 *       translate/src/main/resources/lib/B2ACSLLib}) when running from the repository.
 * </ol>
 */
public final class BundledTools {

    static final String RULES_JAR_RESOURCE = "/bundled/b2acsl.jar";
    private static final String LIB_PROBE = "function.acsl";

    private BundledTools() {}

    public static Path rulesJar() throws IOException {
        String override = System.getProperty("b2acsl.gui.rulesJar");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }
        URL url = BundledTools.class.getResource(RULES_JAR_RESOURCE);
        if (url != null) {
            if ("file".equals(url.getProtocol())) {
                return toPath(url);
            }
            return extractRulesJar(url);
        }
        return fromSourceTree(Path.of("rules", "dist", "b2acsl.jar"));
    }

    public static Path acslLibDir() throws IOException {
        String override = System.getProperty("b2acsl.gui.libDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }
        String root = "/" + B2AcslLibraryPaths.RESOURCE_ROOT;
        URL probe = BundledTools.class.getResource(root + "/" + LIB_PROBE);
        if (probe != null) {
            if ("file".equals(probe.getProtocol())) {
                return toPath(probe).getParent();
            }
            if ("jar".equals(probe.getProtocol())) {
                return extractLib(probe, root);
            }
        }
        return fromSourceTree(Path.of("translate", "src", "main", "resources", "lib", "B2ACSLLib"));
    }

    private static Path extractRulesJar(URL url) throws IOException {
        Path tmp = Files.createTempFile("b2acsl-rules", ".jar");
        String sha;
        try (InputStream in = new DigestInputStream(url.openStream(), sha256())) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            sha = HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest()).substring(0, 16);
        }
        Path target = cacheDir().resolve("rules-" + sha + ".jar");
        if (Files.isRegularFile(target)) {
            Files.delete(tmp);
        } else {
            Files.createDirectories(target.getParent());
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static Path extractLib(URL probe, String root) throws IOException {
        String spec = probe.toString();
        URI jarUri = URI.create(spec.substring(0, spec.indexOf("!/")));
        Path jarFile = Path.of(URI.create(jarUri.getSchemeSpecificPart()));
        String key = Long.toHexString(Files.size(jarFile)) + "-" + Long.toHexString(Files.getLastModifiedTime(jarFile).toMillis());
        Path target = cacheDir().resolve("lib-" + key).resolve("B2ACSLLib");
        if (Files.isRegularFile(target.resolve(LIB_PROBE))) {
            return target;
        }
        FileSystem fs;
        boolean close = false;
        try {
            fs = FileSystems.newFileSystem(jarUri, Map.of());
            close = true;
        } catch (FileSystemAlreadyExistsException e) {
            fs = FileSystems.getFileSystem(jarUri);
        }
        try {
            Path source = fs.getPath(root);
            Path staging = target.resolveSibling("B2ACSLLib.tmp-" + ProcessHandle.current().pid());
            try (Stream<Path> walk = Files.walk(source)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    Path dest = staging.resolve(source.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            if (!Files.exists(target)) {
                Files.move(staging, target);
            }
            return target;
        } finally {
            if (close) {
                fs.close();
            }
        }
    }

    private static Path cacheDir() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("b2acsl-gui");
    }

    private static Path fromSourceTree(Path relative) {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return cwd.resolve(relative);
    }

    private static Path toPath(URL url) throws IOException {
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
