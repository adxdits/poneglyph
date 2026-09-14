# Poneglyph

[![CI](https://github.com/adxdits/poneglyph/actions/workflows/ci.yml/badge.svg)](https://github.com/adxdits/poneglyph/actions/workflows/ci.yml)

**AI Decompile & Verify for Ghidra.** Right-click a function in the Decompiler, and a *local* LLM
rewrites it into clean C, then proves the rewrite by compiling it and running model-generated
tests, feeding errors back for up to three refinement turns. You get the refined C next to the
original pseudo-code with a RED / YELLOW / GREEN verdict.

Nothing leaves your machine except requests to the model endpoint you configure. No telemetry.
MIT licensed.

![A poneglyph, the indestructible stone tablets from One Piece that only a rare few can read](docs/poneglyph.png)
*Ghidra's raw decompiler output is technically "readable" but carved in a dialect only the
decompiler understands. Poneglyph translates it into plain, trustworthy C — verified before you
believe it.*

## 30-second install

Requirements: Ghidra 12.1.x (JDK 21), `gcc` or `clang` on your PATH, and an OpenAI-compatible
model server ([Ollama](https://ollama.com), vLLM, LM Studio).

1. Download `ghidra_12.1.3_PUBLIC_<date>_Poneglyph.zip` from Releases, or build it (below).
2. Ghidra: **File ▸ Install Extensions ▸ +**, pick the zip, restart, enable **Poneglyph**.
3. **Tools ▸ Poneglyph Settings...**: set the endpoint URL and model, press **Test model** /
   **Test gcc**, OK.
4. In the Decompiler, right-click a function ▸ **AI Decompile & Verify**.

The **Poneglyph** panel opens and fills in as the loop runs (cancel with the ✕). Everything runs
off the Swing thread.

## What you get in Ghidra

- **Popup action** in the Decompiler and Listing windows.
- **Results panel**: status badge, pseudo-code beside refined C, a collapsible log per turn
  (stage, compiler/test output, feedback sent, prompt, raw response), and the generated tests.
- **Buttons**: *Run again*, *Copy C*, *Write as comment*, *Settings...*.
- **Settings**: endpoint, model, API mode (chat/completion), optional test-generation model, API
  key, max turns (default 3), gcc path + flags, timeouts, prompt override directory, and a
  "write result as function comment" toggle.

| Badge  | Meaning |
|--------|---------|
| GREEN  | compiles and passes all generated tests |
| YELLOW | compiles, but tests fail, crash, time out, or couldn't be generated |
| RED    | doesn't compile, or the model never produced a C function |

## Command line

```sh
./gradlew :core:cliJar
java -jar core/build/libs/poneglyph-cli.jar --endpoint http://localhost:11434/v1 --model qwen2.5-coder:7b input.c
```

Pass `-` instead of a file to read the pseudo-code from standard input, which is handy from a
Ghidra script or a `for` loop over dumped functions:

```sh
cat FUN_00101149.c | java -jar core/build/libs/poneglyph-cli.jar --quiet -
```

Key flags: `--max-turns N`, `--gcc PATH`, `--api-mode chat|completion`, `--test-model NAME`,
`--out FILE`, `--json`, `--verbose`, `--quiet`, `--version`, `--replay FILE` (demo with no model),
`--export-prompts DIR` / `--prompts DIR` (edit prompt templates). Exit codes: 0 GREEN, 1 YELLOW,
2 RED, 3 usage/infrastructure error.

Try it without a model:

```sh
java -jar core/build/libs/poneglyph-cli.jar \
    --replay tests/samples/replay/count_bits.replay.txt tests/samples/count_bits.ghidra.c
```

`tests/samples/` has three sample functions with pseudo-code, reference C and replay scripts
(a compile error, a failing test, a first-try success).

## Supported models

Any OpenAI-compatible chat or completions server works:

| Model | Serve with | API mode | Notes |
|-------|-----------|----------|-------|
| **LLM4Decompile-ref 6.7B v2** | `vllm serve LLM4Binary/llm4decompile-6.7b-v2` (or a GGUF via Ollama) | `completion` | Trained for this task. Use `examples/prompts-llm4decompile` and a separate test model. |
| **AutoDecompiler 6.7B pscode** | `vllm serve AutoDecompiler/AutoDecompiler-6.7B-pscode` | `completion` | Same workflow as LLM4Decompile. |
| **Qwen2.5-Coder 7B/14B**, DeepSeek-Coder, Codestral | `ollama pull qwen2.5-coder:7b` | `chat` (default) | Weaker first draft, but follows error feedback and writes tests well. Default choice for the test-generation slot. |

Completion-style models (LLM4Decompile, AutoDecompiler) know one prompt shape: they rewrite
pseudo-code well but can't write tests or act on "fix these errors". Point the optional
**test-generation model** at an instruct model in that case; leave it blank to use one model for
everything. These picks follow the LLM4Decompile/AutoDecompiler/DecLLM papers and haven't been
benchmarked through this tool yet.

## How the verification loop works

Each turn: extract C from the model's reply → compile it → generate/run tests (once, cached
across turns) → classify the result → stop or feed targeted feedback back for another turn.

| Stage | Badge | Feedback sent back |
|-------|-------|---------------------|
| `INVALID_CODE` | RED | asks for a complete C function definition |
| `COMPILE_ERROR` | RED | "the specific error messages are: … regenerate the code" |
| `RUNTIME_ERROR` | YELLOW | same pattern with the crash/timeout output |
| `TEST_MISMATCH` | YELLOW | "incorrect outputs … Specifically: … regenerate the code" |
| `TESTS_UNKNOWN` | YELLOW | none — nothing actionable, loop stops |
| `OK` | GREEN | none — loop stops |

Notes: code extraction strips `<think>` blocks, fences and prose, preferring the longest fenced
block with a function definition. Compilation treats implicit function declarations as errors so
missing helpers are caught. Tests are generated once and reused across turns (regenerated once if
a later turn breaks the build, e.g. a renamed function); a failing `assert()` is recorded rather
than aborting, so every failing case is reported in one run. The **best turn** (by stage, then
fewest test failures) is always returned, not just the last one. Prompts live as editable files in
`core/src/main/resources/prompts/*.txt` — export with `--export-prompts DIR`.

## Building from source

```sh
export GHIDRA_INSTALL_DIR=/path/to/ghidra_12.1.3_PUBLIC   # only needed for the extension
./gradlew :core:test :core:cliJar     # core library, tests, CLI jar (JDK 17+)
./gradlew :Poneglyph:buildExtension   # extension zip -> ghidra-ext/dist/ (JDK 21)
```

Without `GHIDRA_INSTALL_DIR` the extension module is skipped and the core still builds. Compile
tests use real `gcc` (override with `PONEGLYPH_GCC=clang`) and skip themselves if none is found;
the model is always mocked, including an in-process fake OpenAI-compatible server for end-to-end
CLI tests.

```
core/        plain Java 17 library + CLI: LlmClient, CodeExtractor, Compiler, TestRunner, RefinementLoop
ghidra-ext/  Ghidra extension (extension.properties, Module.manifest, plugin, panel, settings)
tests/       sample pseudo-code, reference C and replay scripts
examples/    prompt profile for LLM4Decompile-style completion models
```

## Limitations

- **Tests are only as good as the model that wrote them.** GREEN means "passes a handful of
  model-written cases", not "semantically equivalent to the binary" — read the generated tests.
- Refined code is compiled for the host. Functions relying on target-specific layout, inline
  assembly, or state Ghidra excluded from the function body may need hand editing.
- Calls to other program functions get a plausible model-declared prototype but aren't executed
  by the tests unless the model stubs them.
- Completion-style decompiler models ignore error feedback; the loop still returns the best
  sampled turn, but the real gains come from instruct models.
- The API key is stored in Ghidra's tool options in plain text.
- The interactive Ghidra flow (popup action ▸ panel ▸ comment) hasn't been exercised in a live
  Ghidra session yet, though it compiles cleanly against 12.1.3 and its helpers are unit tested.
- Only one run at a time per tool.

## License

MIT. See `LICENSE`.
