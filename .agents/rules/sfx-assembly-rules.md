# SuperFX (GSU / FX3) Assembly & Macro Rules

Rules and technical constraints when writing, parsing, analyzing, or compiling SuperFX assembly code across `retro-superfx`.

---

## 1. Register Architecture & Hardware Roles

- **`R0`**: Primary Accumulator & default source/dest register (`Sreg`/`Dreg`). Primary input parameter / pointer. Any non-prefix instruction resets `Sreg` and `Dreg` back to `R0`.
- **`R1` - `R3`**: General parameters, secondary pointers, and scratch registers. `R3` is standard return value or output pointer.
- **`R4`**: Low 16 bits of 32-bit product during hardware `LMULT`. Never use `R4` as destination register in `condensed_lmult`.
- **`R6`**: Multiplicand register for hardware `LMULT` / `MULT`.
- **`R7` - `R9`**: General scratch registers.
- **`R10`**: Dedicated call stack pointer (`stackPointer`). Must never be allocated as a general-purpose variable.
- **`R11`**: Link register holding return address for subroutines (`link #4`, `call`, `return`).
- **`R12`**: Hardware loop counter (`loop`).
- **`R13`**: Hardware loop target branch address (`loop`).
- **`R14`**: ROM pointer with hardware prefetch buffer (`ROMB`, `GETB`, `GETC`).
- **`R15`**: Program Counter (`PC`). Writing to `R15` executes an immediate jump. Never allocated as a variable.

---

## 2. Instruction Semantics & Prefix Nuances

1. **Modal Prefix Instruction Scope (`with`, `to`, `from`)**:
   - `Sreg` (source register) and `Dreg` (destination register) for operations default to `R0`.
   - `with Rn`, `to Rn`, and `from Rn` modify `Sreg` and/or `Dreg` for the **single immediately following instruction only**.
   - Immediately after that configured instruction executes, both `Sreg` and `Dreg` automatically reset back to `R0`.
   - **Prefix register mapping and side-effects**:
     | Prefix | Effect on `Sreg` | Effect on `Dreg` | Use case | Clwarning / Side effect |
     |---|---|---|---|---|
     | `with Rn` | `Rn` | `Rn` | In-place modification (`with r1; add #2`) | **Destructive to `Rn`**: modifies `Rn` directly. Do NOT use for non-destructive comparisons! |
     | `from Rn` | `Rn` | `R0` | Read operand, test/compare against immediate or register (`from r8; sub #1; beq ...`) | Leaves `Rn` intact; writes result into accumulator `R0` and updates flags. |
     | `to Rn` | `R0` | `Rn` | Compute into destination register (`to r3; add #5`) | Reads operand from `R0` and stores output into `Rn`. |
   - **Critical Warning (`sub` vs `cmp`)**:
     - Never use `with Rn; sub #k` or `with Rn; sub Rm` for non-destructive comparisons: because `with` sets `Dreg = Rn`, `sub` overwrites `Rn` in-place!
     - In contrast, `cmp` does **NOT** modify the destination register (`Dreg`)—it only computes `Sreg - Rm` and updates processor flags (`Z`, `CY`, `S`, `OV`). Therefore, `cmp` is non-destructive and safe to use.
     - Remember that `sub` always writes to `Dreg`: under `with Rn` it modifies `Rn`, and under `from Rn` it writes to `R0`. In sequential comparisons, `from r0; sub r1` will overwrite `R0`, corrupting subsequent checks against `R0` unless `R0` is preserved or `cmp` is used.
2. **Branch Delay Slots**:
   - Every branch (`bra`, `beq`, `bne`, `bmi`, `bge`, `blt`) has a 1-instruction delay slot.
   - Insert `nop` in the delay slot unless deliberately placing an instruction to execute during the branch pipeline.

---

## 3. FX3 Hardware Constraints

1. **`MERGE` instruction not available**:
   - SuperFX 3 (FX3 / RP2350B coprocessor) does not implement `MERGE`.
   - Workaround: Use byte manipulations (`hib`, `lob`, `swap`, `or`). Refer to `condensed_lmult` in `X-GSU/gsu_maths/gsu_vector.i`.
2. **8 NOP Pipeline Settling**:
   - SuperFX Work RAM writes are pipelined asynchronously.
   - Before hitting `stop` or evaluating RAM in Lua emulation scripts, write 8 consecutive `nop` instructions:
     ```assembly
     nop
     nop
     nop
     nop
     nop
     nop
     nop
     nop
     stop
     ```
3. **Word Alignment**:
   - All 16-bit word loads (`ldw`) and stores (`stw`) must be 16-bit word aligned (even addresses).
4. **Dual `rpix` Pixel Cache Flush**:
   - SuperFX `plot` buffers pixels in dual 8-pixel caches (primary and secondary).
   - Execute **two `rpix` instructions** (`rpix; rpix`) before the 8 settling `nop`s and `stop` to flush both pixel caches to Work RAM.
5. **GSURAM-Only `ldw` / `ldb` Addressing**:
   - `ldw (Rn)` and `ldb (Rn)` always read from `GSURAM` (`$700000`, masked to 8 KB `$1FFF`).
   - Tables or constants defined in `.segment "GSUCODE"`, `.segment "RODATA"`, or `.segment "ROM*"` reside in ROM (`>= $8000`) when executing from ROM banks; read them via `romb` + `r14` + `getb`/`getbh` or copy them into `.segment "GSURAM"` before using `ldw`/`ldb`.
6. **`R1..R15`-Only Register Operands (`and`, `bic`, `or`, `xor`, `mult`, `umult`)**:
   - The register forms of `and`, `bic`, `or`, `xor`, `mult`, and `umult` only encode `R1`–`R15` (since opcode `$70` is `merge` rather than `and r0`). Keep operand registers in `R1`–`R15` or place `R0` as `Sreg`/`Dreg` via `from r0` / `with r0`.
7. **512-Byte Instruction Cache vs Dual 8-Pixel Plot Cache**:
   - The GSU has a 512-byte on-chip instruction cache (32 lines of 16 bytes each), distinct from the dual 8-pixel plot cache flushed by `rpix; rpix`.
   - Instructions running inside the cache execute in 1 clock cycle (21.47 MHz on FX3), freeing external buses for concurrent RAM writes or ROM prefetching.
   - **`CACHE` Opcode**: When `CACHE` executes and `CBR != (R15 & $FFF0)`, it updates `CBR = R15 & $FFF0` and clears all 32 cache line valid flags. Subsequent misses fetch 16 bytes across the bus (~48–96 cycles).
   - **Subroutine Residency**: `call`, `jmp`, and `bra` **do not invalidate or flush the cache**. If a target subroutine is outside `[CBR, CBR + 511]`, it executes at normal bus speeds, and returning to the caller immediately resumes 1-cycle cache hits.
   - **Anti-Pattern (Over-Invalidation / Cache Spraying)**: Never place `cache` inside a loop or inner helper function. Changing `CBR` forces a cold cache reload on every iteration. Use `cache` only before compute-heavy leaf loops with high iteration counts (e.g. rasterization, large memory clears, vertex transform batches):
     ```assembly
     cache
     move r13, r15   ; Entry point for loop
     ```

---

## 4. Stack & Loop Macro Invariants

1. **Stack Convention**:
   - The stack pointer (`R10`) grows upward by default in X-GSU conventions (`stw (r10); inc r10; inc r10`).
   - Pop operations pre-decrement: `dec r10; dec r10; ldw (r10)`.
2. **Loop Preservation**:
   - `for` / `forR` macro automatically pushes `R12` and `R13` onto the stack (`backuploop`).
   - `endfor` executes `loop; nop; restoreloop` which pops `R13` and `R12`.
   - **Never push un-popped data to the stack inside a `for` ... `endfor` block**, or `restoreloop` will pop corrupted loop pointers.
3. **Hardware Loop Invariants (`forR` / `loop`)**:
   - `forR Rn` uses hardware counter `R12` and back-edge target `R13`.
   - Subroutines that clobber `R12` or `R13` (such as `compile_face_traps`, complex rasterizers, or math kernels) must NEVER be called inside `forR` loops without `backuploop` / `restoreloop` shielding or caller-saved stack preservation.
   - Calling a routine that mutates `R12`/`R13` inside a loop without preservation prematurely terminates or corrupts the outer loop.

---

## 5. Mandatory Framebuffer Capture Gate for Scene Optimizations

- **Full Scene Verification Gate**: Any optimization, refactoring, or pipeline change affecting full-scene demos (`IsoEngine`, `SpriteScale`) MUST execute the `capture_sfx_render` MCP tool on `retro-superfx_mesen` (or verify via headless screenshot) to confirm `non_black_pixels > 0` before declaring the task complete.
- **Subroutine Isolation vs Integration**: Passing isolated subroutine unit tests (`draw_textured_triangle`, `test_cached_trap`) is necessary but NOT sufficient to verify full scene integration.

---

## Symbol Lookup vs. References vs. grep

- To find where an assembly symbol (label, X-GSU `function`, macro, struct, field, constant) is **defined**, or to read its In/Out/Clobbers doc, call `sfx_lookup_symbol` on `retro-superfx_lsp` before reaching for `grep`. Pass `path` to scope to a folder.
- To find where an assembly symbol is **used** (references, calls, reads, writes), call `sfx_find_references` on `retro-superfx_lsp`. It understands `call` to `function`, macro invocations, and struct field scoping while excluding comments and string literals.
- To resolve linker addresses of symbols or find what symbol/function lives at a given PC/memory address from build artifacts (`.dbg` / `.cpu.sym`), call `sfx_resolve_address` on `retro-superfx_mesen`.
- Use `grep` for regex/plain-text search, non-assembly files, and `.build/` output.
- Trust `location.path` / `references[].path` + `lineNumber` (1-based). Ignore hits under `src/test/resources/` unless you are working on those fixtures.

