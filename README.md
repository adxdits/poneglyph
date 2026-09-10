# Poneglyph

**AI Decompile & Verify for Ghidra.** A right-click action that rewrites Ghidra's decompiler
output into clean, readable C with a *local* LLM, then proves the rewrite by compiling it and
running model-generated tests, feeding every error back to the model for up to three refinement
turns. The result is shown next to the original pseudo-code with a RED / YELLOW / GREEN verdict.

Nothing leaves your machine except requests to the model endpoint you configure. No telemetry.
MIT licensed.

![A poneglyph, the indestructible stone tablets from One Piece that only a rare few can read](docs/poneglyph.png)
*Like the poneglyphs of One Piece, Ghidra's raw decompiler output is technically "readable" but
makes little sense to most people. This tool is the Poneglyph translator: it turns that carved,
cryptic pseudo-code into plain, trustworthy history — verified line by line before you believe it.*

## 30-second install

Requirements: Ghidra 12.1.x (JDK 21), a C compiler on your PATH (`gcc` or `clang`), and an
OpenAI-compatible model server such as [Ollama](https://ollama.com), vLLM or LM Studio.

1. Download `ghidra_12.1.3_PUBLIC_<date>_Poneglyph.zip` from the Releases page, or build it
   (see below).
2. In Ghidra: **File ▸ Install Extensions ▸ +**, pick the zip, restart Ghidra.
3. Open a program. When asked about new plugins, enable **Poneglyph** (or use
   **File ▸ Configure ▸ Poneglyph**).
4. **Tools ▸ Poneglyph Settings...**: set the endpoint URL and model name, press **Test model**
   and **Test gcc**, OK.
5. In the Decompiler window, right-click inside any function ▸ **AI Decompile & Verify**.

The **Poneglyph** panel opens and fills in as the loop runs. Cancel with the ✕ next to the
progress bar. Everything runs off the Swing thread.

## What you get in Ghidra

- **Popup action** "AI Decompile & Verify" in the Decompiler window (also in the Listing).
- **Results panel** (dockable): status badge, Ghidra pseudo-code beside the refined C, and a
  collapsible log with one section per turn showing the stage, compiler or test output, the
  feedback that was sent back, the full prompt and the raw model response. The generated tests
  are shown too.
- **Buttons**: *Run again*, *Copy C*, *Write as comment* (stores the verdict and code as the
  function's comment, replacing any earlier Poneglyph block), *Settings...*.
- **Settings dialog** (also under Edit ▸ Tool Options ▸ Poneglyph): endpoint URL, model name,
  API mode (chat/completion), optional test-generation model, API key, max turns (default 3),
  gcc path, extra gcc flags, model/compile/test-run/decompile timeouts, prompt override directory,
  and the "write result as function comment" toggle.

Badge meaning:

| Badge  | Meaning |
|--------|---------|
| GREEN  | compiles and passes all generated tests |
| YELLOW | compiles, but tests fail, crash, time out, or could not be generated |
| RED    | does not compile, or the model never produced a C function |

## Command line

The core library has no Ghidra dependency and ships as a standalone jar:

```sh
./gradlew :core:cliJar
java -jar core/build/libs/poneglyph-cli.jar --endpoint http://localhost:11434/v1 --model qwen2.5-coder:7b input.c
```

`input.c` is a file containing Ghidra's decompiler output for one function. Useful flags:

```
--max-turns N        refinement turns (default 3)
--gcc PATH           compiler to use (clang works too)
--api-mode MODE      chat (default) or completion
--test-model NAME    instruct model for writing tests (default: same as --model)
--out FILE           write the best C to FILE        --json  machine-readable report
--verbose            show prompts and raw responses  --quiet one-line verdict only
--replay FILE        canned model responses; demo the loop with no model at all
--export-prompts DIR write the default prompt templates for editing; use with --prompts DIR
```

Exit codes: 0 GREEN, 1 YELLOW, 2 RED, 3 usage or infrastructure error (no gcc, endpoint down).

Try it without a model:

```sh
java -jar core/build/libs/poneglyph-cli.jar \
    --replay tests/samples/replay/count_bits.replay.txt tests/samples/count_bits.ghidra.c
```

```
--- turn 1/3 ---
  TEST_MISMATCH [YELLOW] 0.9s: 2 of 5 checks failed
      FAILED: count_bits(0xF0) == 4 (test line 8)
      FAILED: count_bits(0x80000000u) == 1 (test line 10)
--- turn 2/3 ---
  OK [GREEN] 0.8s: all 5 checks passed

=== GREEN: compiles and passes tests (best turn 2 of 2) ===
```

`tests/samples/` has three functions with Ghidra-style pseudo-code, clean reference versions and
replay scripts covering a compile error, a failing test and a first-try success.

## Supported models

Any server that speaks the OpenAI chat or completions API works. Recommended starting points:

| Model | Serve with | API mode | Notes |
|-------|-----------|----------|-------|
| **LLM4Decompile-ref 6.7B v2** (`LLM4Binary/llm4decompile-6.7b-v2`) | `vllm serve LLM4Binary/llm4decompile-6.7b-v2`, or a community GGUF in Ollama, e.g. `ollama pull hf.co/Th3S/llm4decompile-6.7b-v2-Q4_K_M-GGUF` | `completion` | Trained specifically to refine Ghidra output. Use `examples/prompts-llm4decompile` as the prompt override directory and set a separate test model. |
| **AutoDecompiler 6.7B pscode** (`AutoDecompiler/AutoDecompiler-6.7B-pscode`) | `vllm serve AutoDecompiler/AutoDecompiler-6.7B-pscode` | `completion` | Same workflow as LLM4Decompile. |
| **Qwen2.5-Coder 7B / 14B**, DeepSeek-Coder, Codestral | `ollama pull qwen2.5-coder:7b` | `chat` (default) | General instruct models. Weaker first draft, but they follow the error feedback and write the tests, so the loop helps more. Good default, and the right choice for the *test-generation model* slot. |

Why two model slots: LLM4Decompile and AutoDecompiler are completion-style models that only know
one prompt shape. They rewrite pseudo-code well but do not write unit tests or act on "here are
the compiler errors, fix them". Poneglyph therefore lets you point test generation at an
instruct model while the decompiler model does the rewriting. Leave the test model blank to use a
single instruct model for everything.

These recommendations follow the LLM4Decompile, AutoDecompiler and DecLLM papers; they have not
been benchmarked through this tool yet. The default model name is `qwen2.5-coder:7b` because it
works out of the box with Ollama.

## How the verification loop works

```
pseudo-code ──► prompt ──► model ──► extract C ──► gcc -c ──► generate tests ──► gcc + run
                  ▲                     │              │           (once, cached)      │
                  │            no C found │     errors  │                     crash /   │ all pass
                  │                       ▼             ▼                     failures  ▼
                  └───── feedback ◄── INVALID_CODE   COMPILE_ERROR      RUNTIME_ERROR /  OK ─► stop
                                                                       TEST_MISMATCH
```

Each turn is classified into exactly one stage:

| Stage | Badge | Feedback sent to the model on the next turn |
|-------|-------|---------------------------------------------|
| `INVALID_CODE` | RED | "The previous response did not contain a complete C function definition..." |
| `COMPILE_ERROR` | RED | "The generated code contains compilation errors, and the specific error messages are: … Please analyze the errors and regenerate the code." |
| `RUNTIME_ERROR` | YELLOW | same pattern with the crash / timeout output |
| `TEST_MISMATCH` | YELLOW | "There are incorrect outputs when performing unit testing on the generated code. Specifically: … Please analyze the errors and regenerate the code." |
| `TESTS_UNKNOWN` | YELLOW | none; the loop stops because there is nothing actionable |
| `OK` | GREEN | none; the loop stops |

Details that matter in practice:

- **Code extraction** strips `<think>` blocks, Markdown fences and prose. If several fenced blocks
  exist, the longest one containing a function definition wins and earlier declaration-only blocks
  (typedefs, includes) are kept in front of it. An unterminated fence is closed automatically.
- **Compilation** uses `gcc -std=gnu11 -w -c` with a prelude of standard headers, and treats
  implicit function declarations as errors so calls to helpers the model forgot to define are
  caught. Local `#include "x.h"` lines are dropped because no such file exists.
- **Tests** are requested once, from the first version that compiles, and reused for every later
  turn so all turns are judged against the same cases. If a later turn breaks the test build
  (typically because the model renamed the function) they are regenerated once. The harness
  redefines `assert()` so a failing check is recorded and printed instead of aborting, which lets
  one run report every failing case; crashes (signals) and timeouts are classified separately.
  Zero executed checks count as "unknown", not as a pass.
- **Best turn** wins by stage (`OK` > `TEST_MISMATCH` > `TESTS_UNKNOWN` > `RUNTIME_ERROR` >
  `COMPILE_ERROR` > `INVALID_CODE`); among test mismatches, fewer failures win. You always get the
  best turn seen, never just the last.
- **Prompts** live in `core/src/main/resources/prompts/*.txt`. Export them with
  `--export-prompts DIR`, edit, and point `--prompts DIR` (or the Ghidra setting) at the folder.
  Placeholders: `{pseudo_code}`, `{code}`, `{errors}`, `{failed_cases}`, `{feedback}`,
  `{function_name}`.

## Building from source

```sh
export GHIDRA_INSTALL_DIR=/path/to/ghidra_12.1.3_PUBLIC   # only needed for the extension
./gradlew :core:test :core:cliJar          # core library, tests and CLI jar (JDK 17+)
./gradlew :Poneglyph:buildExtension      # extension zip -> ghidra-ext/dist/ (JDK 21)
```

Without `GHIDRA_INSTALL_DIR` the extension module is skipped and the core still builds. Tests
that need a compiler use the real `gcc` (override with `PONEGLYPH_GCC=clang`) and skip
themselves if none is found; the model is always mocked, with an in-process fake
OpenAI-compatible server for the end-to-end CLI tests.

Layout:

```
core/        plain Java 17 library + CLI: LlmClient, CodeExtractor, Compiler, TestRunner, RefinementLoop
ghidra-ext/  Ghidra extension (extension.properties, Module.manifest, plugin, panel, settings)
tests/       sample pseudo-code, reference C and replay scripts
examples/    prompt profile for LLM4Decompile-style completion models
```

## Limitations

- **Tests are only as good as the model that wrote them.** GREEN means "passes 3–5 model-written
  cases", not "semantically equivalent to the binary". Read the generated tests in the log.
- The refined code is compiled on the host, for the host. Functions that depend on target-specific
  layout, inline assembly, or global state that Ghidra did not include in the function body will
  need hand editing before they compile.
- Functions that call other program functions are compiled with a plausible prototype declared by
  the model; those calls are not executed by the tests unless the model stubs them.
- Completion-style decompiler models ignore the error feedback (see *Supported models*). The loop
  still picks the best of the sampled turns, but the big gains come from instruct models.
- The API key is stored in Ghidra's tool options in plain text.
- The Ghidra side has been compiled against Ghidra 12.1.3 and its pure helpers are unit tested;
  the interactive flow (popup action ▸ panel ▸ comment) was not exercised in a running Ghidra
  during initial development. Please open an issue with the Ghidra log if something misbehaves.
- Only one run at a time per tool.

## License

MIT. See `LICENSE`.
