package poneglyph.core.loop;

import org.junit.jupiter.api.Test;
import poneglyph.core.CancelToken;
import poneglyph.core.Stage;
import poneglyph.core.Status;
import poneglyph.core.compile.Compiler;
import poneglyph.core.compile.CompilerNotFoundException;
import poneglyph.core.llm.LlmException;
import poneglyph.core.llm.LlmRequest;
import poneglyph.core.prompt.Prompts;
import poneglyph.core.testutil.FakeLlmClient;
import poneglyph.core.testutil.Gcc;
import poneglyph.core.verify.TestResult;
import poneglyph.core.verify.TestRunner;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefinementLoopTest {

    static final String PSEUDO = "int FUN_00100000(int param_1,int param_2)\n\n{\n  return param_1 + param_2;\n}\n";
    static final String GOOD = "```c\nint add(int a, int b)\n{\n    return a + b;\n}\n```";
    static final String BROKEN = "```c\nint add(int a, int b)\n{\n    return a + b\n}\n```";
    static final String WRONG = "```c\nint add(int a, int b)\n{\n    return a - b;\n}\n```";
    static final String CRASHY = "```c\nint add(int a, int b)\n{\n    int *p = (int *) 0;\n    return *p + a + b;\n}\n```";
    static final String RENAMED = "```c\nint sum(int a, int b)\n{\n    return a + b;\n}\n```";
    static final String TESTS = "```c\n#include <assert.h>\nint main(void)\n{\n    assert(add(1, 2) == 3);\n"
            + "    assert(add(0, 0) == 0);\n    assert(add(-1, 1) == 0);\n    return 0;\n}\n```";
    static final String TESTS_FOR_SUM = "```c\n#include <assert.h>\nint main(void)\n{\n    assert(sum(1, 2) == 3);\n"
            + "    assert(sum(0, 0) == 0);\n    return 0;\n}\n```";

    private static RefinementLoop loop(FakeLlmClient fake, int maxTurns) {
        return loop(fake, maxTurns, Gcc.compiler());
    }

    private static RefinementLoop loop(FakeLlmClient fake, int maxTurns, Compiler compiler) {
        Prompts prompts = Prompts.defaults();
        TestRunner runner = new TestRunner(fake, compiler, prompts, Duration.ofSeconds(5));
        return new RefinementLoop(fake, runner, compiler, prompts, LoopConfig.defaults().withMaxTurns(maxTurns));
    }

    @Test
    void greenOnFirstTurnStopsEarly() throws Exception {
        Gcc.assumeAvailable();
        FakeLlmClient fake = new FakeLlmClient().decompile(GOOD).tests(TESTS);
        RefinementResult r = loop(fake, 3).run(PSEUDO, null, null, CancelToken.NONE);

        assertEquals(Status.GREEN, r.status());
        assertEquals(Stage.OK, r.stage());
        assertEquals(1, r.turns().size());
        assertEquals(1, r.bestTurnNumber());
        assertTrue(r.bestCode().contains("return a + b;"));
        assertNotNull(r.testCode());
        assertFalse(r.cancelled());
        assertNull(r.abortReason());
        assertEquals(1, fake.requestsOf(LlmRequest.Kind.DECOMPILE).size());
        assertEquals(1, fake.requestsOf(LlmRequest.Kind.TEST_GEN).size());
        String prompt = fake.requestsOf(LlmRequest.Kind.DECOMPILE).get(0).userPrompt();
        assertTrue(prompt.contains("FUN_00100000"));
        assertTrue(prompt.contains("return param_1 + param_2;"));
        assertEquals(3, r.best().tests().checks());
    }

    @Test
    void compileErrorIsFedBackAndFixed() throws Exception {
        Gcc.assumeAvailable();
        FakeLlmClient fake = new FakeLlmClient().decompile(BROKEN, GOOD).tests(TESTS);
        RefinementResult r = loop(fake, 3).run(PSEUDO, "FUN_00100000", null, CancelToken.NONE);

        assertEquals(2, r.turns().size());
        Turn first = r.turns().get(0);
        assertEquals(Stage.COMPILE_ERROR, first.stage());
        assertEquals(Status.RED, first.status());
        assertNotNull(first.compile());
        assertTrue(first.detail().contains("error"), first.detail());
        assertEquals(Stage.OK, r.turns().get(1).stage());
        assertSame(r.turns().get(1), r.best());
        assertEquals(Status.GREEN, r.status());

        String second = fake.requestsOf(LlmRequest.Kind.DECOMPILE).get(1).userPrompt();
        assertTrue(second.contains("The generated code contains compilation errors"), second);
        assertTrue(second.contains("Please analyze the errors and regenerate the code."), second);
        assertTrue(second.contains("return a + b\n"), second);
        assertTrue(second.contains("error"), second);
    }

    @Test
    void invalidCodeIsRetried() throws Exception {
        Gcc.assumeAvailable();
        FakeLlmClient fake = new FakeLlmClient().decompile("I cannot help with that request.", GOOD).tests(TESTS);
        RefinementResult r = loop(fake, 3).run(PSEUDO, null, null, CancelToken.NONE);

        assertEquals(Stage.INVALID_CODE, r.turns().get(0).stage());
        assertNull(r.turns().get(0).code());
        String second = fake.requestsOf(LlmRequest.Kind.DECOMPILE).get(1).userPrompt();
        assertTrue(second.contains("did not contain a complete C function"), second);
        // There was no usable previous attempt, so the retry must not refer to one.
        assertFalse(second.contains("Your previous attempt"), second);
        assertFalse(second.contains("```c\n```"), second);
        assertTrue(second.contains("Rewrite the following Ghidra decompiler output"), second);
        assertEquals(Status.GREEN, r.status());
    }

    @Test
    void refinePromptIsUsedOnceSomeCodeExists() throws Exception {
        Gcc.assumeAvailable();
        // Turn 1 produces no code, turn 2 produces code that does not compile, so turn 3 has a
        // previous attempt to refine and must say so.
        FakeLlmClient fake = new FakeLlmClient().decompile("Sorry, I can't.", BROKEN, GOOD).tests(TESTS);
        RefinementResult r = loop(fake, 3).run(PSEUDO, null, null, CancelToken.NONE);

        String third = fake.requestsOf(LlmRequest.Kind.DECOMPILE).get(2).userPrompt();
        assertTrue(third.contains("Your previous attempt"), third);
        assertTrue(third.contains("return a + b\n"), third);
        assertEquals(Status.GREEN, r.status());
    }

    @Test
    void testMismatchIsFedBackAndFixedWithTheSameTests() throws Exception {
        Gcc.assumeAvailable();
        FakeLlmClient fake = new FakeLlmClient().decompile(WRONG, GOOD).tests(TESTS);
        RefinementResult r = loop(fake, 3).run(PSEUDO, null, null, CancelToken.NONE);

        Turn first = r.turns().get(0);
        assertEquals(Stage.TEST_MISMATCH, first.stage());
        assertEquals(Status.YELLOW, first.status());
        assertEquals(2, first.tests().failures());
        assertEquals(3, first.tests().checks());
        String second = fake.requestsOf(LlmRequest.Kind.DECOMPILE).get(1).userPrompt();
        assertTrue(second.contains("There are incorrect outputs when performing unit testing"), second);
        assertTrue(second.contains("add(1, 2) == 3"), second);
        assertEquals(Status.GREEN, r.status());
        assertEquals(1, fake.requestsOf(LlmRequest.Kind.TEST_GEN).size(), "tests are generated once and reused");
    }

    @Test
    void runtimeErrorIsFedBack() throws Exception {
        Gcc.assumeAvailable();
        FakeLlmClient fake = new FakeLlmClient().decompile(CRASHY, GOOD).tests(TESTS);
        RefinementResult r = loop(fake, 3).run(PSEUDO, null, null, CancelToken.NONE);

        assertEquals(Stage.RUNTIME_ERROR, r.turns().get(0).stage());
        String second = fake.requestsOf(LlmRequest.Kind.DECOMPILE).get(1).userPrompt();
        assertTrue(second.contains("runtime errors"), second);
        assertTrue(second.contains("crashed"), second);
        assertEquals(Status.GREEN, r.status());
    }

    @Test
    void bestTurnIsReturnedWhenNothingGoesGreen() throws Exception {
        Gcc.assumeAvailable();
        FakeLlmClient fake = new FakeLlmClient().decompile(BROKEN, WRONG, BROKEN).tests(TESTS);
        RefinementResult r = loop(fake, 3).run(PSEUDO, null, null, CancelToken.NONE);

        assertEquals(3, r.turns().size());
        assertEquals(2, r.bestTurnNumber());
        assertEquals(Stage.TEST_MISMATCH, r.stage());
        assertEquals(Status.YELLOW, r.status());
        assertTrue(r.bestCode().contains("a - b"));
        assertTrue(r.summary().startsWith("YELLOW"));
    }

    @Test
    void testGenerationFailureYieldsYellowAndStops() throws Exception {
        Gcc.assumeAvailable();
        FakeLlmClient fake = new FakeLlmClient().decompile(GOOD).tests("no code here", "still nothing");
        RefinementResult r = loop(fake, 3).run(PSEUDO, null, null, CancelToken.NONE);

        assertEquals(1, r.turns().size());
        assertEquals(Stage.TESTS_UNKNOWN, r.stage());
        assertEquals(Status.YELLOW, r.status());
        assertNull(r.testCode());
        assertTrue(r.turns().get(0).detail().contains("no C code"), r.turns().get(0).detail());
        assertEquals(2, fake.requestsOf(LlmRequest.Kind.TEST_GEN).size(), "two generation attempts");
        assertEquals(1, fake.requestsOf(LlmRequest.Kind.DECOMPILE).size(), "no feedback possible, so no more turns");
    }

    @Test
    void testModelOutageYieldsYellowNotACrash() throws Exception {
        Gcc.assumeAvailable();
        FakeLlmClient fake = new FakeLlmClient().decompile(GOOD).testFailure("connection reset").testFailure("connection reset");
        RefinementResult r = loop(fake, 3).run(PSEUDO, null, null, CancelToken.NONE);
        assertEquals(Stage.TESTS_UNKNOWN, r.stage());
        assertTrue(r.turns().get(0).detail().contains("connection reset"));
    }

    @Test
    void testsAreRegeneratedOnceWhenTheFunctionIsRenamed() throws Exception {
        Gcc.assumeAvailable();
        FakeLlmClient fake = new FakeLlmClient().decompile(WRONG, RENAMED).tests(TESTS, TESTS_FOR_SUM);
        RefinementResult r = loop(fake, 3).run(PSEUDO, null, null, CancelToken.NONE);

        assertEquals(Status.GREEN, r.status());
        assertEquals(2, r.turns().size());
        assertEquals(2, fake.requestsOf(LlmRequest.Kind.TEST_GEN).size());
        assertTrue(r.testCode().contains("sum(1, 2)"));
    }

    @Test
    void missingCompilerFailsBeforeCallingTheModel() {
        FakeLlmClient fake = new FakeLlmClient().decompile(GOOD).tests(TESTS);
        Compiler none = new Compiler("/nonexistent/dir/gcc-xyz", Duration.ofSeconds(5), List.of());
        assertThrows(CompilerNotFoundException.class, () -> loop(fake, 3, none).run(PSEUDO, null, null, CancelToken.NONE));
        assertTrue(fake.requests.isEmpty());
    }

    @Test
    void modelFailureOnFirstTurnPropagates() {
        Gcc.assumeAvailable();
        FakeLlmClient fake = new FakeLlmClient().decompileFailure("server down");
        LlmException e = assertThrows(LlmException.class, () -> loop(fake, 3).run(PSEUDO, null, null, CancelToken.NONE));
        assertEquals("server down", e.getMessage());
    }

    @Test
    void modelFailureOnLaterTurnKeepsTheBestSoFar() throws Exception {
        Gcc.assumeAvailable();
        FakeLlmClient fake = new FakeLlmClient().decompile(BROKEN).decompileFailure("server down");
        RefinementResult r = loop(fake, 3).run(PSEUDO, null, null, CancelToken.NONE);

        assertEquals(1, r.turns().size());
        assertEquals(Status.RED, r.status());
        assertNotNull(r.abortReason());
        assertTrue(r.abortReason().contains("server down"));
        assertTrue(r.summary().contains("stopped early"));
    }

    @Test
    void cancellationReturnsPartialResult() throws Exception {
        Gcc.assumeAvailable();
        FakeLlmClient fake = new FakeLlmClient().decompile(BROKEN, GOOD).tests(TESTS);
        AtomicBoolean cancel = new AtomicBoolean(false);
        ProgressListener cancelAfterFirstTurn = new ProgressListener() {
            @Override
            public void onTurnEnd(Turn turn) {
                cancel.set(true);
            }
        };
        RefinementResult r = loop(fake, 3).run(PSEUDO, null, cancelAfterFirstTurn, cancel::get);

        assertTrue(r.cancelled());
        assertEquals(1, r.turns().size());
        assertEquals(Stage.COMPILE_ERROR, r.stage());
        assertTrue(r.summary().contains("cancelled"));
        assertEquals(1, fake.requestsOf(LlmRequest.Kind.DECOMPILE).size());
    }

    @Test
    void emptyInputIsRejected() {
        FakeLlmClient fake = new FakeLlmClient();
        assertThrows(IllegalArgumentException.class, () -> loop(fake, 3).run("   ", null, null, CancelToken.NONE));
    }

    @Test
    void bestTurnRankingPrefersMoreInformation() {
        Turn compileError = turn(1, Stage.COMPILE_ERROR, null);
        Turn twoFailures = turn(2, Stage.TEST_MISMATCH, tests(2));
        Turn unknown = turn(3, Stage.TESTS_UNKNOWN, null);
        Turn oneFailure = turn(4, Stage.TEST_MISMATCH, tests(1));
        Turn oneFailureLater = turn(5, Stage.TEST_MISMATCH, tests(1));
        Turn runtime = turn(6, Stage.RUNTIME_ERROR, null);

        assertSame(oneFailure, RefinementLoop.pickBest(List.of(compileError, twoFailures, unknown, oneFailure, oneFailureLater, runtime)));
        assertSame(unknown, RefinementLoop.pickBest(List.of(compileError, runtime, unknown)));
        Turn ok = turn(7, Stage.OK, tests(0));
        assertSame(ok, RefinementLoop.pickBest(List.of(oneFailure, ok, unknown)));
        assertNull(RefinementLoop.pickBest(List.of()));
    }

    private static Turn turn(int n, Stage stage, TestResult tests) {
        return new Turn(n, stage, "p", "r", "int f(void){return 0;}", "", null, null, tests, 1);
    }

    private static TestResult tests(int failures) {
        return new TestResult(failures == 0 ? TestResult.TestStatus.PASSED : TestResult.TestStatus.FAILED,
                3, failures, List.of(), failures == 0 ? 0 : 1, "", "", "", 1);
    }
}
