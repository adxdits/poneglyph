package poneglyph.cli;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import poneglyph.core.CancelToken;
import poneglyph.core.Stage;
import poneglyph.core.Status;
import poneglyph.core.Text;
import poneglyph.core.compile.Compiler;
import poneglyph.core.compile.CompilerNotFoundException;
import poneglyph.core.llm.HttpLlmClient;
import poneglyph.core.llm.LlmClient;
import poneglyph.core.llm.LlmConfig;
import poneglyph.core.llm.LlmException;
import poneglyph.core.llm.ReplayLlmClient;
import poneglyph.core.loop.LoopConfig;
import poneglyph.core.loop.ProgressListener;
import poneglyph.core.loop.RefinementLoop;
import poneglyph.core.loop.RefinementResult;
import poneglyph.core.loop.Turn;
import poneglyph.core.prompt.Prompts;
import poneglyph.core.verify.TestRunner;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line front end: {@code java -jar poneglyph-cli.jar [options] input.c}.
 *
 * <p>Exit codes: 0 = GREEN, 1 = YELLOW, 2 = RED, 3 = usage or infrastructure error.
 */
public final class Main {

    static final String VERSION = "0.1.0";

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.in, System.out, System.err));
    }

    /** Testable entry point reading any stdin input from {@link System#in}. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, System.in, out, err);
    }

    /** Testable entry point. {@code in} is only read when the input file is given as "-". */
    public static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        Options opts;
        try {
            opts = Options.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.println();
            printUsage(err);
            return 3;
        }
        if (opts.help) {
            printUsage(out);
            return 0;
        }
        if (opts.version) {
            out.println("Poneglyph CLI " + VERSION);
            return 0;
        }
        if (opts.exportPrompts != null) {
            try {
                Prompts.exportDefaults(opts.exportPrompts);
                out.println("Wrote default prompt templates to " + opts.exportPrompts.toAbsolutePath());
                return 0;
            } catch (IOException e) {
                err.println("error: could not export prompts: " + e.getMessage());
                return 3;
            }
        }
        if (opts.input == null && !opts.readStdin) {
            err.println("error: no input file given");
            err.println();
            printUsage(err);
            return 3;
        }

        String pseudo;
        try {
            pseudo = opts.readStdin
                    ? new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    : Files.readString(opts.input, StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("error: cannot read " + opts.inputLabel() + ": " + e.getMessage());
            return 3;
        }
        if (pseudo.isBlank()) {
            err.println("error: " + opts.inputLabel() + " is empty");
            return 3;
        }

        Prompts prompts;
        try {
            prompts = Prompts.load(opts.promptsDir);
        } catch (IOException e) {
            err.println("error: cannot load prompt templates from " + opts.promptsDir + ": " + e.getMessage());
            return 3;
        }

        LlmClient decompileModel;
        LlmClient testModel;
        if (opts.replay != null) {
            try {
                ReplayLlmClient replay = ReplayLlmClient.fromFile(opts.replay);
                decompileModel = replay;
                testModel = replay;
            } catch (IOException e) {
                err.println("error: cannot read replay file " + opts.replay + ": " + e.getMessage());
                return 3;
            }
        } else {
            LlmConfig cfg = new LlmConfig(opts.endpoint, opts.model, opts.apiKey, opts.apiMode,
                    Duration.ofSeconds(opts.timeoutSeconds), opts.temperature, opts.maxTokens);
            decompileModel = new HttpLlmClient(cfg);
            if (opts.testModel != null && !opts.testModel.equals(opts.model)) {
                // Test generation needs an instruct model; it always uses the chat route.
                testModel = new HttpLlmClient(cfg.withModel(opts.testModel).withApiMode(LlmConfig.ApiMode.CHAT));
            } else {
                testModel = decompileModel;
            }
        }

        Compiler compiler = new Compiler(opts.gcc, Duration.ofSeconds(opts.compileTimeoutSeconds), opts.gccFlags);
        TestRunner testRunner = new TestRunner(testModel, compiler, prompts, Duration.ofSeconds(opts.runTimeoutSeconds));
        LoopConfig loopConfig = new LoopConfig(opts.maxTurns, LoopConfig.DEFAULT_TEST_ATTEMPTS, opts.workDir,
                opts.keepWork, LoopConfig.DEFAULT_MAX_FEEDBACK_CHARS);
        RefinementLoop loop = new RefinementLoop(decompileModel, testRunner, compiler, prompts, loopConfig);

        if (!opts.quiet && !opts.json) {
            out.println("Poneglyph CLI " + VERSION);
            out.println("input:    " + opts.inputLabel());
            out.println("model:    " + decompileModel.describe());
            if (testModel != decompileModel) {
                out.println("tests:    " + testModel.describe());
            }
            out.println("compiler: " + opts.gcc + " (" + compiler.version() + ")");
            out.println("turns:    up to " + opts.maxTurns);
            out.println();
        }

        ProgressListener listener = opts.quiet || opts.json ? ProgressListener.NONE : new ConsoleListener(out, opts.verbose);
        RefinementResult result;
        try {
            result = loop.run(pseudo, opts.functionName, listener, CancelToken.NONE);
        } catch (CompilerNotFoundException e) {
            err.println("error: " + e.getMessage());
            err.println("hint: pass --gcc /path/to/gcc (or clang), or set it in the Poneglyph settings dialog.");
            return 3;
        } catch (LlmException e) {
            err.println("error: " + e.getMessage());
            return 3;
        } catch (RuntimeException e) {
            err.println("error: " + e);
            return 3;
        }

        if (opts.out != null && result.hasCode()) {
            try {
                Files.writeString(opts.out, result.bestCode(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                err.println("error: cannot write " + opts.out + ": " + e.getMessage());
                return 3;
            }
        }

        if (opts.json) {
            out.println(toJson(result, opts.verbose));
        } else if (!opts.quiet) {
            out.println();
            out.println("=== " + result.summary() + " ===");
            if (result.hasCode()) {
                out.println();
                out.print(result.bestCode());
                if (!result.bestCode().endsWith("\n")) {
                    out.println();
                }
            }
            if (opts.out != null && result.hasCode()) {
                out.println();
                out.println("wrote " + opts.out);
            }
        } else {
            out.println(result.summary());
        }
        return exitCode(result.status());
    }

    static int exitCode(Status status) {
        switch (status) {
            case GREEN:
                return 0;
            case YELLOW:
                return 1;
            default:
                return 2;
        }
    }

    static String toJson(RefinementResult result, boolean verbose) {
        JsonObject root = new JsonObject();
        root.addProperty("status", result.status().name());
        root.addProperty("stage", result.stage().name());
        root.addProperty("summary", result.summary());
        root.addProperty("bestTurn", result.bestTurnNumber());
        root.addProperty("code", result.bestCode());
        root.addProperty("cancelled", result.cancelled());
        root.addProperty("abortReason", result.abortReason());
        root.addProperty("testCode", result.testCode());
        JsonArray turns = new JsonArray();
        for (Turn t : result.turns()) {
            JsonObject o = new JsonObject();
            o.addProperty("number", t.number());
            o.addProperty("stage", t.stage().name());
            o.addProperty("status", t.status().name());
            o.addProperty("detail", t.detail());
            o.addProperty("durationMs", t.durationMs());
            o.addProperty("code", t.code());
            if (t.tests() != null) {
                o.addProperty("checks", t.tests().checks());
                o.addProperty("failures", t.tests().failures());
            }
            if (verbose) {
                o.addProperty("prompt", t.prompt());
                o.addProperty("rawResponse", t.rawResponse());
                o.addProperty("feedback", t.feedback());
            }
            turns.add(o);
        }
        root.add("turns", turns);
        return new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create().toJson(root);
    }

    static void printUsage(PrintStream out) {
        out.println("usage: java -jar poneglyph-cli.jar [options] <input.c|->");
        out.println();
        out.println("Reads Ghidra decompiler output for one function from a file, or from standard input");
        out.println("when the input is given as '-'.");
        out.println();
        out.println("Rewrites Ghidra decompiler pseudo-code as clean C using a local LLM, then compiles and");
        out.println("tests the result, feeding errors back to the model for up to --max-turns turns.");
        out.println();
        out.println("model options:");
        out.println("  --endpoint URL      OpenAI-compatible base URL (default " + LlmConfig.DEFAULT_BASE_URL + ")");
        out.println("  --model NAME        model for decompilation (default " + LlmConfig.DEFAULT_MODEL + ")");
        out.println("  --test-model NAME   instruct model used to write tests (default: same as --model)");
        out.println("  --api-mode MODE     chat | completion (default chat; use completion for LLM4Decompile-ref)");
        out.println("  --api-key KEY       bearer token if the server needs one (or env PONEGLYPH_API_KEY)");
        out.println("  --timeout SECONDS   per-request model timeout (default " + LlmConfig.DEFAULT_TIMEOUT.toSeconds() + ")");
        out.println("  --temperature T     sampling temperature (default " + LlmConfig.DEFAULT_TEMPERATURE + ")");
        out.println("  --max-tokens N      max tokens per response (default " + LlmConfig.DEFAULT_MAX_TOKENS + ")");
        out.println("  --replay FILE       replay canned responses from FILE instead of calling a model");
        out.println();
        out.println("verification options:");
        out.println("  --max-turns N       refinement turns (default " + LoopConfig.DEFAULT_MAX_TURNS + ")");
        out.println("  --gcc PATH          C compiler to use (default gcc; clang works too)");
        out.println("  --gcc-flag FLAG     extra compiler flag, repeatable (e.g. --gcc-flag -fsanitize=address)");
        out.println("  --compile-timeout S compiler timeout in seconds (default " + Compiler.DEFAULT_TIMEOUT.toSeconds() + ")");
        out.println("  --run-timeout S     test binary timeout in seconds (default " + TestRunner.DEFAULT_RUN_TIMEOUT.toSeconds() + ")");
        out.println("  --function NAME     name to use in prompts (default: parsed from the input)");
        out.println("  --prompts DIR       directory with prompt overrides (<key>.txt)");
        out.println("  --export-prompts DIR  write the default prompt templates to DIR and exit");
        out.println("  --work-dir DIR      create temp dirs under DIR (with --keep-work to inspect them)");
        out.println();
        out.println("output options:");
        out.println("  --out FILE          write the best refined C to FILE");
        out.println("  --json              print a JSON report instead of text");
        out.println("  --verbose           include prompts and raw model responses");
        out.println("  --quiet             print only the one-line verdict");
        out.println("  -h, --help          this help");
        out.println("  --version           print the version and exit");
        out.println();
        out.println("exit codes: 0 GREEN (compiles and passes tests), 1 YELLOW (compiles, tests fail/unknown),");
        out.println("            2 RED (does not compile), 3 usage or infrastructure error");
    }

    /** Parsed command line. Package-private for tests. */
    static final class Options {
        Path input;
        String endpoint = LlmConfig.DEFAULT_BASE_URL;
        String model = LlmConfig.DEFAULT_MODEL;
        String testModel;
        LlmConfig.ApiMode apiMode = LlmConfig.ApiMode.CHAT;
        String apiKey = System.getenv("PONEGLYPH_API_KEY");
        long timeoutSeconds = LlmConfig.DEFAULT_TIMEOUT.toSeconds();
        double temperature = LlmConfig.DEFAULT_TEMPERATURE;
        int maxTokens = LlmConfig.DEFAULT_MAX_TOKENS;
        Path replay;
        int maxTurns = LoopConfig.DEFAULT_MAX_TURNS;
        String gcc = Compiler.DEFAULT_GCC;
        List<String> gccFlags = new ArrayList<>();
        long compileTimeoutSeconds = Compiler.DEFAULT_TIMEOUT.toSeconds();
        long runTimeoutSeconds = TestRunner.DEFAULT_RUN_TIMEOUT.toSeconds();
        String functionName;
        Path promptsDir;
        Path exportPrompts;
        Path workDir;
        boolean keepWork;
        Path out;
        boolean json;
        boolean verbose;
        boolean quiet;
        boolean help;
        boolean version;
        boolean readStdin;

        /** How the input is named in messages. */
        String inputLabel() {
            return readStdin ? "standard input" : String.valueOf(input);
        }

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-h":
                    case "--help":
                        o.help = true;
                        break;
                    case "--version":
                        o.version = true;
                        break;
                    case "-":
                        if (o.input != null || o.readStdin) {
                            throw new IllegalArgumentException("only one input is supported");
                        }
                        o.readStdin = true;
                        break;
                    case "--endpoint":
                        o.endpoint = value(args, ++i, a);
                        break;
                    case "--model":
                        o.model = value(args, ++i, a);
                        break;
                    case "--test-model":
                        o.testModel = value(args, ++i, a);
                        break;
                    case "--api-mode":
                        o.apiMode = LlmConfig.ApiMode.parse(value(args, ++i, a));
                        break;
                    case "--api-key":
                        o.apiKey = value(args, ++i, a);
                        break;
                    case "--timeout":
                        o.timeoutSeconds = positiveLong(value(args, ++i, a), a);
                        break;
                    case "--temperature":
                        o.temperature = Double.parseDouble(value(args, ++i, a));
                        break;
                    case "--max-tokens":
                        o.maxTokens = (int) positiveLong(value(args, ++i, a), a);
                        break;
                    case "--replay":
                        o.replay = Path.of(value(args, ++i, a));
                        break;
                    case "--max-turns":
                        o.maxTurns = (int) positiveLong(value(args, ++i, a), a);
                        break;
                    case "--gcc":
                        o.gcc = value(args, ++i, a);
                        break;
                    case "--gcc-flag":
                        o.gccFlags.add(value(args, ++i, a));
                        break;
                    case "--compile-timeout":
                        o.compileTimeoutSeconds = positiveLong(value(args, ++i, a), a);
                        break;
                    case "--run-timeout":
                        o.runTimeoutSeconds = positiveLong(value(args, ++i, a), a);
                        break;
                    case "--function":
                        o.functionName = value(args, ++i, a);
                        break;
                    case "--prompts":
                        o.promptsDir = Path.of(value(args, ++i, a));
                        break;
                    case "--export-prompts":
                        o.exportPrompts = Path.of(value(args, ++i, a));
                        break;
                    case "--work-dir":
                        o.workDir = Path.of(value(args, ++i, a));
                        break;
                    case "--keep-work":
                        o.keepWork = true;
                        break;
                    case "--out":
                        o.out = Path.of(value(args, ++i, a));
                        break;
                    case "--json":
                        o.json = true;
                        break;
                    case "--verbose":
                        o.verbose = true;
                        break;
                    case "--quiet":
                        o.quiet = true;
                        break;
                    default:
                        if (a.startsWith("-")) {
                            throw new IllegalArgumentException("unknown option " + a);
                        }
                        if (o.input != null) {
                            throw new IllegalArgumentException("only one input file is supported (got "
                                    + o.input + " and " + a + ")");
                        }
                        if (o.readStdin) {
                            throw new IllegalArgumentException("cannot read both standard input and " + a);
                        }
                        o.input = Path.of(a);
                }
            }
            return o;
        }

        private static String value(String[] args, int i, String option) {
            if (i >= args.length) {
                throw new IllegalArgumentException(option + " needs a value");
            }
            return args[i];
        }

        private static long positiveLong(String s, String option) {
            try {
                long v = Long.parseLong(s.trim());
                if (v <= 0) {
                    throw new IllegalArgumentException(option + " must be positive");
                }
                return v;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " expects a number, got '" + s + "'");
            }
        }
    }

    /** Prints progress and a per-turn summary to the console. */
    private static final class ConsoleListener implements ProgressListener {
        private final PrintStream out;
        private final boolean verbose;

        ConsoleListener(PrintStream out, boolean verbose) {
            this.out = out;
            this.verbose = verbose;
        }

        @Override
        public void onMessage(String message) {
            if (verbose) {
                out.println("  .. " + message);
            }
        }

        @Override
        public void onTurnStart(int turn, int maxTurns) {
            out.println("--- turn " + turn + "/" + maxTurns + " ---");
        }

        @Override
        public void onTurnEnd(Turn turn) {
            out.println("  " + turn.stage() + " [" + turn.status() + "] " + Text.seconds(turn.durationMs())
                    + (turn.detail() == null ? "" : ": " + indent(Text.truncate(turn.detail(), verbose ? 100_000 : 1200))));
            if (verbose) {
                out.println("  prompt:");
                out.println(indent(turn.prompt()));
                out.println("  raw response:");
                out.println(indent(Text.nullToEmpty(turn.rawResponse())));
            }
            if (turn.stage() == Stage.OK && verbose && turn.tests() != null) {
                out.println(indent(turn.tests().stdout()));
            }
        }

        private static String indent(String s) {
            return Text.nullToEmpty(s).strip().replace("\n", "\n      ");
        }
    }
}
