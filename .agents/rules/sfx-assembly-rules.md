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

1. **Modal Prefix Instruction Scope**:
   - `with Rn`, `to Rn`, `from Rn` modify `Sreg` or `Dreg` for the **single immediately following instruction only**.
   - Standard arithmetic, logical, and memory operations reset `Sreg` and `Dreg` back to `R0`.
   - To adjust a pointer in `R1`, you must write:
     ```assembly
     with r1
     add #2
     ```
     Writing `add #2` alone will modify `R0` or the previous destination!
2. **Branch Delay Slots**:
   - Every branch (`bra`, `beq`, `bne`, `bmi`, `bge`, `blt`) has a 1-instruction delay slot.
   - Insert `nop` in the delay slot unless deliberately placing an instruction to execute during the branch pipeline.

---

## 3. FX3 Hardware Constraints

1. **`MERGE` instruction not available**:
   - SuperFX 3 (FX3 / RP2350B coprocessor) does not implement `ALT2 + MERGE`.
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

---

## 4. Stack & Loop Macro Invariants

1. **Stack Convention**:
   - The stack pointer (`R10`) grows upward by default in X-GSU conventions (`stw (r10); inc r10; inc r10`).
   - Pop operations pre-decrement: `dec r10; dec r10; ldw (r10)`.
2. **Loop Preservation**:
   - `for` / `forR` macro automatically pushes `R12` and `R13` onto the stack (`backuploop`).
   - `endfor` executes `loop; nop; restoreloop` which pops `R13` and `R12`.
   - **Never push un-popped data to the stack inside a `for` ... `endfor` block**, or `restoreloop` will pop corrupted loop pointers.

