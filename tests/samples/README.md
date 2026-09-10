# Sample functions

Three small functions in the shape Ghidra's decompiler produces, with hand-written clean versions
and scripted model responses for demonstrating the loop without a model.

| Name          | `*.ghidra.c` (input)               | `*.expected.c` (reference)   | `replay/*.replay.txt` scenario                    |
|---------------|------------------------------------|------------------------------|---------------------------------------------------|
| `sum_array`   | sums `count` 32-bit ints           | `int64_t sum_array(...)`     | turn 1 has a compile error, turn 2 is fixed       |
| `count_bits`  | population count                   | `uint32_t count_bits(...)`   | turn 1 fails the generated tests, turn 2 is fixed |
| `str_reverse` | in-place string reverse            | `void str_reverse(char *s)`  | correct on turn 1                                 |

Run one without a model:

```sh
java -jar core/build/libs/poneglyph-cli.jar --replay tests/samples/replay/count_bits.replay.txt tests/samples/count_bits.ghidra.c
```

Replay files are plain text. `=== decompile ===` starts a canned answer to a decompile/refine prompt,
`=== tests ===` starts a canned answer to a test-generation prompt; each kind is consumed in order.
