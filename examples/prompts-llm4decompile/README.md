# Prompt profile for LLM4Decompile-ref (completion-style models)

LLM4Decompile-ref (`LLM4Binary/llm4decompile-6.7b-v2` and friends) was trained on a single
prompt shape and no chat template. This directory overrides the built-in prompts to match it.

Use it with the CLI:

```sh
java -jar poneglyph-cli.jar \
  --endpoint http://localhost:8000/v1 --model llm4decompile-6.7b-v2 --api-mode completion \
  --prompts examples/prompts-llm4decompile \
  --test-model qwen2.5-coder:7b \
  input.c
```

or in Ghidra: set **API mode** to `completion` and **Prompt override directory** to this folder.

Notes:

- `system.txt` is empty on purpose: the model has no system-prompt concept.
- `refine.txt` is identical to `initial.txt`. The model does not follow "fix these errors"
  instructions, so refinement turns simply re-sample. Raise the temperature (`--temperature 0.4`)
  if you want turns 2 and 3 to differ, or use an instruct model for the whole loop.
- Test generation needs an instruct model, so set `--test-model` (or the Ghidra setting) to a
  general coder model such as `qwen2.5-coder:7b`. Only the test model receives the chat-style
  test prompts; the main model only ever sees the prompt in `initial.txt`.
- Only the files present here override the defaults; every other template (test generation,
  feedback wording) stays built-in.
