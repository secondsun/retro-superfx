---
name: sfx-macro-language
description: >-
  Reference and guide for the X-GSU ca65 macro DSL, its pseudo-instructions, stack operations,
  control structures, and integration with sfx-optimizer and retro-common. Use when authoring,
  analyzing, refactoring, or compiling SuperFX assembly code using high-level macros.
---

# SuperFX X-GSU Macro Language Guide

This skill documents the high-level macro language implemented in `snes-sfx-demo/X-GSU/common/` and analyzed/compiled by `sfx-optimizer` and `retro-common`.

---

## 1. Language Overview

The X-GSU macro language bridges high-level programming semantics with the SuperFX RISC instruction set using the `ca65` assembler.

Core source files:
- `snes-sfx-demo/X-GSU/common/function.i`: Function declaration, scopes, calls, and returns.
- `snes-sfx-demo/X-GSU/common/stack.i`: Stack allocation, push, pop, and peek using `R10`.
- `snes-sfx-demo/X-GSU/common/control.i`: Conditionals (`if_lt`, `if_gt`, `if_eq`, `if_lte`).
- `snes-sfx-demo/X-GSU/common/structs.i`: Data structures (`vector3`, `vector4`, `matrix4`, `camera`, `polygon`).
- `snes-sfx-demo/X-GSU/common/util.i`: ROM buffer reading, bit shifts, and helper routines.

---

## 2. Macro Reference

### Subroutines & Functions

```assembly
function my_routine [param1, param2]
    ; Opens .scope my_routine
    ; Pushes return address (R11) to stack via R10
    ...
    return
endfunction
```
- `function <name>`: Generates `<name>_function:` label, enters `.scope <name>`, and pushes `R11` to stack (`gsu_stack_push r11`).
- `call <name>`: Emits `link #4`, loads return PC into `R15` targeting `<name>_function`.
- `return`: Pops return address into `R15` (`gsu_stack_pop r15; nop`), returning execution to caller.
- `endfunction`: Closes `.endscope`.

### Variable Declarations & `sfx-optimizer` Keywords

```assembly
register x, y, temp
register forced_reg = r4
```
- `register <name> [= <reg>]`: Declares pseudo-registers for graph-coloring register allocation.
- In `sfx-optimizer`, variables declared via `register` participate in Chaitin-Briggs graph coloring, live-range tracking, and swimlane visualization.

### Stack Operations (`R10` is `stackPointer`)

```assembly
init_stack                   ; r10 = gsu_stack_ram ($200 bytes in GSURAM)
gsu_stack_push r1            ; stw (r10); inc r10; inc r10
gsu_stack_pop r1             ; dec r10; dec r10; to r1; ldw (r10)
gsu_stack_peek r1            ; dec r10; dec r10; to r1; ldw (r10); inc r10; inc r10
gsu_stack_alloc_bytes 8      ; with r10; adds 8
gsu_stack_free_bytes 8       ; with r10; sub 8
gsu_stack_alloc vector3, r1  ; Allocates sizeof(vector3) on stack and returns pointer in r1
gsu_stack_free vector3, r1   ; Frees sizeof(vector3) from stack
```

### Hardware Loops

```assembly
for 10
    ; Loops 10 times
    ; Backs up r12/r13 to stack, sets r12 = 10, r13 = top of loop
endfor

forR countRegister
    ; Loops countRegister times
endfor
```
- **Rule**: Never push values onto the stack inside `for ... endfor` without popping them before `endfor`, because `endfor` executes `restoreloop` which pops `R13` and `R12` directly from the top of stack.

### Conditionals (`control.i`)

```assembly
if_lt R, V, instruction    ; If R < V (signed), execute instruction
if_gt R, V, instruction    ; If R > V (signed), execute instruction
if_eq R, V, instruction    ; If R == V, execute instruction
if_lte R, V, instruction   ; If R <= V (signed), execute instruction
```
Example:
```assembly
if_lt r1, #10, bra skip_render
nop
```

---

## 3. sfx-optimizer Integration & Future Pipeline

`sfx-optimizer` provides:
1. **Liveness Analysis**: Calculates intervals for pseudo-registers and hardware registers.
2. **Graph-Coloring Register Allocation**: Colors variables into `R1`-`R9`, `R11`-`R13`, spilling to stack slots when interference graph degree exceeds available colors.
3. **Macro Expansion & Move Resolution**:
   - `CA65MacroExpander`: Pre-expands stack and loop macros while maintaining line-mapping.
   - `StackDepthTracker`: Statically tracks stack depth across CFG branches.
   - `ParallelMoveResolver`: Eliminates scratch register bottlenecks using 3-instruction XOR swaps (`xor Rn; xor Rm; xor Rn`).
4. **Future Build Pipeline Hook**:
   - `sfx-optimizer` will sit in the build pipeline ahead of `ca65`:
     ```
     [Source .sgs with macros/registers]
                  │
                  ▼
          [sfx-optimizer]
                  │
                  ▼
          [Lowered .s assembly]
                  │
                  ▼
          [ca65 / ld65] ──> [ROM .sfc]
     ```

---

## 4. MCP Server Tools (`retro-superfx_lsp`)

Agents should actively utilize the `retro-superfx_lsp` MCP server tools via `call_mcp_tool` when inspecting, verifying, or refactoring SuperFX macros and routines:

### A. Audit Hardware Invariants (`sfx_audit_invariants`)
Statically audits SuperFX code or files for hardware constraints (8-NOP pipeline settling before `stop`, 16-bit word alignment on `ldw`/`stw`, flagging unsupported hardware `MERGE` instructions on FX3, and modal instruction lifetime scope):

```json
{
  "ServerName": "retro-superfx_lsp",
  "ToolName": "sfx_audit_invariants",
  "Arguments": {
    "code": "merge\nstop\n"
  }
}
```

### B. Expand Macros (`sfx_expand_macro`)
Expands X-GSU macro DSL structures (`function`, `for`, `if_lt`, stack macros) to concrete assembly:

```json
{
  "ServerName": "retro-superfx_lsp",
  "ToolName": "sfx_expand_macro",
  "Arguments": {
    "code": "for 4\nnop\nendfor\n"
  }
}
```

### C. Analyze Register Allocation (`sfx_analyze_registers`)
Computes live-range intervals, interference graphs, and register allocation pressure:

```json
{
  "ServerName": "retro-superfx_lsp",
  "ToolName": "sfx_analyze_registers",
  "Arguments": {
    "code": "register a, b\niwt a, #10\niwt b, #20\nadd a\n"
  }
}
```

### D. Look Up Symbols (`sfx_lookup_symbol`)
Resolves definitions, documentation, and scopes for symbols across the workspace index:

```json
{
  "ServerName": "retro-superfx_lsp",
  "ToolName": "sfx_lookup_symbol",
  "Arguments": {
    "symbol": "gsu_stack_push",
    "uri": "file:///path/to/source.sgs"
  }
}
```

