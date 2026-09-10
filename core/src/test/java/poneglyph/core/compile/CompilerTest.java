package poneglyph.core.compile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import poneglyph.core.CancelToken;
import poneglyph.core.testutil.Gcc;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilerTest {

    @Test
    void compilesValidFunction(@TempDir Path dir) throws Exception {
        Gcc.assumeAvailable();
        CompileResult r = Gcc.compiler().compileFunction(dir, "int add(int a, int b)\n{\n    return a + b;\n}\n", CancelToken.NONE);
        assertTrue(r.ok(), r.errors());
        assertTrue(dir.resolve("function.o").toFile().exists());
    }

    @Test
    void reportsSyntaxErrors(@TempDir Path dir) throws Exception {
        Gcc.assumeAvailable();
        CompileResult r = Gcc.compiler().compileFunction(dir, "int add(int a, int b)\n{\n    return a + b\n}\n", CancelToken.NONE);
        assertFalse(r.ok());
        assertTrue(r.errors().contains("error"), r.errors());
        assertTrue(r.errors().contains("function.c"), r.errors());
        assertTrue(r.commandLine().contains("-c"));
    }

    @Test
    void callingAnUndeclaredFunctionIsAnError(@TempDir Path dir) throws Exception {
        Gcc.assumeAvailable();
        CompileResult r = Gcc.compiler().compileFunction(dir, "int f(int a)\n{\n    return helper(a);\n}\n", CancelToken.NONE);
        assertFalse(r.ok());
        assertTrue(r.errors().contains("helper"), r.errors());
    }

    @Test
    void missingCompilerGivesActionableError() {
        Compiler c = new Compiler("/nonexistent/dir/gcc-xyz", Duration.ofSeconds(5), List.of());
        CompilerNotFoundException e = assertThrows(CompilerNotFoundException.class, c::checkAvailable);
        assertTrue(e.getMessage().contains("/nonexistent/dir/gcc-xyz"), e.getMessage());
        assertTrue(e.getMessage().contains("gcc path"), e.getMessage());
        assertFalse(c.isAvailable());
    }
}
