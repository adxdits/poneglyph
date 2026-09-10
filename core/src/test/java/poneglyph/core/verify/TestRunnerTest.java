package poneglyph.core.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import poneglyph.core.CancelToken;
import poneglyph.core.compile.ProcessRunner;
import poneglyph.core.prompt.Prompts;
import poneglyph.core.testutil.FakeLlmClient;
import poneglyph.core.testutil.Gcc;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRunnerTest {

    private static final String COUNT_BITS = String.join("\n",
            "#include <stdint.h>",
            "uint32_t count_bits(uint32_t v)",
            "{",
            "    uint32_t n = 0;",
            "    while (v) { n += v & 1u; v >>= 1; }",
            "    return n;",
            "}",
            "");

    private static final String PASSING_TESTS = String.join("\n",
            "#include <assert.h>",
            "int main(void)",
            "{",
            "    assert(count_bits(0) == 0);",
            "    assert(count_bits(7) == 3);",
            "    assert(count_bits(0xFFFFFFFFu) == 32);",
            "    return 0;",
            "}",
            "");

    private static final String FAILING_TESTS = String.join("\n",
            "#include <assert.h>",
            "int main(void)",
            "{",
            "    assert(count_bits(0) == 0);",
            "    assert(count_bits(3) == 5);",
            "    assert(count_bits(8) == 1);",
            "    return 0;",
            "}",
            "");

    private static TestRunner runner(FakeLlmClient fake, Duration timeout) {
        return new TestRunner(fake, Gcc.compiler(), Prompts.defaults(), timeout);
    }

    @Test
    void generateTestsExtractsFencedCode() {
        FakeLlmClient fake = new FakeLlmClient().tests("Here are tests:\n```c\n" + PASSING_TESTS + "```\n");
        TestRunner.GeneratedTests g = runner(fake, null).generateTests(COUNT_BITS, "pseudo", "count_bits", CancelToken.NONE);
        assertTrue(g.ok(), g.problem());
        assertTrue(g.code().contains("int main(void)"));
        assertEquals(1, fake.requestsOf(poneglyph.core.llm.LlmRequest.Kind.TEST_GEN).size());
    }

    @Test
    void generateTestsRejectsUnusableResponses() {
        FakeLlmClient fake = new FakeLlmClient()
                .tests("I cannot write tests for this.")
                .tests("```c\nint helper(void) { return 1; }\n```")
                .tests("```c\nint main(void) { return 0; }\n```")
                .testFailure("connection reset");
        TestRunner r = runner(fake, null);
        assertEquals("model response contained no C code", r.generateTests(COUNT_BITS, "", "f", CancelToken.NONE).problem());
        assertEquals("generated tests have no main() function", r.generateTests(COUNT_BITS, "", "f", CancelToken.NONE).problem());
        assertEquals("generated tests contain no assert() calls", r.generateTests(COUNT_BITS, "", "f", CancelToken.NONE).problem());
        String problem = r.generateTests(COUNT_BITS, "", "f", CancelToken.NONE).problem();
        assertNotNull(problem);
        assertTrue(problem.contains("connection reset"), problem);
    }

    @Test
    void duplicateDefinitionOfFunctionUnderTestIsRemoved() {
        String tests = COUNT_BITS + "\n#include <assert.h>\nint main(void)\n{\n    assert(count_bits(1) == 1);\n    return 0;\n}\n";
        String stripped = TestRunner.stripDuplicateDefinitions(tests, COUNT_BITS);
        assertFalse(stripped.contains("uint32_t count_bits(uint32_t v)"), stripped);
        assertTrue(stripped.contains("int main(void)"));
        assertTrue(stripped.contains("duplicate definition of count_bits removed"));
    }

    @Test
    void passingTests(@TempDir Path dir) throws Exception {
        Gcc.assumeAvailable();
        TestResult r = runner(new FakeLlmClient(), null).run(dir, COUNT_BITS, PASSING_TESTS, CancelToken.NONE);
        assertEquals(TestResult.TestStatus.PASSED, r.status(), r.combinedOutput());
        assertEquals(3, r.checks());
        assertEquals(0, r.failures());
        assertEquals(0, r.exitCode());
    }

    @Test
    void failingTestsReportEachCaseWithLineNumbers(@TempDir Path dir) throws Exception {
        Gcc.assumeAvailable();
        TestResult r = runner(new FakeLlmClient(), null).run(dir, COUNT_BITS, FAILING_TESTS, CancelToken.NONE);
        assertEquals(TestResult.TestStatus.FAILED, r.status(), r.combinedOutput());
        assertEquals(3, r.checks());
        assertEquals(1, r.failures());
        assertEquals(1, r.failedCases().size());
        assertTrue(r.failedCases().get(0).contains("count_bits(3) == 5"), r.failedCases().get(0));
        assertTrue(r.failedCases().get(0).contains("(test line 5)"), r.failedCases().get(0));
        assertEquals("1 of 3 checks failed", r.summary());
    }

    @Test
    void crashIsARuntimeError(@TempDir Path dir) throws Exception {
        Gcc.assumeAvailable();
        String crash = "int deref(int *p)\n{\n    return *p;\n}\n";
        String tests = "#include <assert.h>\nint main(void)\n{\n    int *p = (int *) 0;\n    assert(deref(p) == 0);\n    return 0;\n}\n";
        TestResult r = runner(new FakeLlmClient(), null).run(dir, crash, tests, CancelToken.NONE);
        assertEquals(TestResult.TestStatus.RUNTIME_ERROR, r.status(), r.combinedOutput());
        assertTrue(r.stderr().contains("crashed with"), r.stderr());
    }

    @Test
    void infiniteLoopTimesOut(@TempDir Path dir) throws Exception {
        Gcc.assumeAvailable();
        String spin = "int spin(void)\n{\n    volatile int keep = 1;\n    while (keep) { }\n    return 0;\n}\n";
        String tests = "#include <assert.h>\nint main(void)\n{\n    assert(spin() == 0);\n    return 0;\n}\n";
        TestResult r = runner(new FakeLlmClient(), Duration.ofSeconds(1)).run(dir, spin, tests, CancelToken.NONE);
        assertEquals(TestResult.TestStatus.TIMEOUT, r.status(), r.combinedOutput());
    }

    @Test
    void zeroChecksIsNotAPass(@TempDir Path dir) throws Exception {
        Gcc.assumeAvailable();
        String tests = "int main(void)\n{\n    return 0;\n}\n";
        TestResult r = runner(new FakeLlmClient(), null).run(dir, COUNT_BITS, tests, CancelToken.NONE);
        assertEquals(TestResult.TestStatus.NO_CHECKS, r.status(), r.combinedOutput());
    }

    @Test
    void testCompileErrorIsReportedSeparately(@TempDir Path dir) throws Exception {
        Gcc.assumeAvailable();
        String tests = "#include <assert.h>\nint main(void)\n{\n    assert(no_such_function(1) == 1);\n    return 0;\n}\n";
        TestResult r = runner(new FakeLlmClient(), null).run(dir, COUNT_BITS, tests, CancelToken.NONE);
        assertEquals(TestResult.TestStatus.TEST_COMPILE_ERROR, r.status());
        assertTrue(r.compileErrors().contains("no_such_function"), r.compileErrors());
    }

    @Test
    void classificationOfRawProcessOutput() {
        assertEquals(TestResult.TestStatus.PASSED,
                TestRunner.classify(new ProcessRunner.Output(0, "\nPONEGLYPH_RESULT: all 3 checks passed\n", "", false, 1), 1).status());
        assertEquals(TestResult.TestStatus.NO_CHECKS,
                TestRunner.classify(new ProcessRunner.Output(0, "", "", false, 1), 1).status());
        TestResult failed = TestRunner.classify(new ProcessRunner.Output(1,
                "FAILED: f(1) == 2 (test line 4)\n\nPONEGLYPH_RESULT: 1 of 2 checks failed\n", "", false, 1), 1);
        assertEquals(TestResult.TestStatus.FAILED, failed.status());
        assertEquals(2, failed.checks());
        assertEquals(1, failed.failures());
        assertEquals(TestResult.TestStatus.FAILED,
                TestRunner.classify(new ProcessRunner.Output(1, "test 2 failed\n", "", false, 1), 1).status());
        assertEquals(TestResult.TestStatus.RUNTIME_ERROR,
                TestRunner.classify(new ProcessRunner.Output(139, "", "", false, 1), 1).status());
        assertEquals(TestResult.TestStatus.RUNTIME_ERROR,
                TestRunner.classify(new ProcessRunner.Output(2, "", "", false, 1), 1).status());
        assertEquals(TestResult.TestStatus.TIMEOUT,
                TestRunner.classify(new ProcessRunner.Output(137, "", "", true, 1), 1).status());
        TestResult aborted = TestRunner.classify(new ProcessRunner.Output(134, "",
                "Assertion failed: (x == 1), function main, file test.c, line 3.", false, 1), 1);
        assertEquals(TestResult.TestStatus.FAILED, aborted.status());
        assertNull(aborted.compileErrors().isEmpty() ? null : aborted.compileErrors());
    }
}
