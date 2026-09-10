package poneglyph.core.testutil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Access to {@code tests/samples} at the repository root. */
public final class Samples {

    private Samples() {
    }

    public static Path dir() {
        String p = System.getProperty("poneglyph.samples");
        if (p != null && Files.isDirectory(Path.of(p))) {
            return Path.of(p);
        }
        // Fallback when run from an IDE with the module directory as working dir.
        Path guess = Path.of("..", "tests", "samples").toAbsolutePath().normalize();
        if (Files.isDirectory(guess)) {
            return guess;
        }
        return Path.of("tests", "samples").toAbsolutePath().normalize();
    }

    public static Path pseudo(String name) {
        return dir().resolve(name + ".ghidra.c");
    }

    public static Path expected(String name) {
        return dir().resolve(name + ".expected.c");
    }

    public static Path replay(String name) {
        return dir().resolve("replay").resolve(name + ".replay.txt");
    }

    public static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
