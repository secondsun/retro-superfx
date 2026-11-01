# Coding Standards

Read during review, not implementation. Mechanical rules (formatting, tests) are enforced by the pre-commit hook (`tools/git-hooks/pre-commit`, installed by `tools/install-git-hooks.sh`); this file holds only judgement calls.

## Spec deviations

- Implementation that departs from its issue or spec carries an ADR in `<subproject>/docs/adr/` that names the deviation and the reason. A deviation mentioned only in a chat summary or commit message fails review.
- Example: `sfx-optimizer/docs/adr/0001-loop-caller-save-hoisting-is-write-and-read-free.md`.

## Vocabulary

- Name domain concepts as the subproject `GLOSSARY.md` defines them (see `GLOSSARY-MAP.md`) in code, tests, and docs. A concept missing from the glossary is added to it in the same change.

## Tests

- Compiler behavior is tested through the `SfxCompiler.compileSource` seam and asserts on lowered assembly, not on internal helpers.
- Changing default compiler behavior (for example auto-inlining) updates the existing tests it affects. Pinning an old test to a flag (`enableInlining = false`) is acceptable only when the test targets the non-default path on purpose.

## Optimizer passes

- Every transformation states the hardware condition that makes it safe (delay slots, modal prefix lifetime, sign extension, flag consumers). A rewrite that changes semantics, such as `mult #2` to `add`, is called out in the ADR or the pass's KDoc.

## Hardware loop invariants (`forR` / `loop`)

- `forR Rn` uses hardware counter `R12` and back-edge target `R13`.
- Subroutines that clobber `R12` or `R13` (such as `compile_face_traps`, complex rasterizers, or math kernels) must NEVER be called inside `forR` loops without `backuploop` / `restoreloop` shielding or caller-saved stack preservation.
- Calling a routine that mutates `R12`/`R13` inside a loop without preservation prematurely terminates or corrupts the outer loop.

