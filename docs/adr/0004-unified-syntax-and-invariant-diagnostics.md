# 0004. Unified Syntax, Invariant, and Stack Diagnostics Architecture

Date: 2026-10-18

## Status

Accepted

## Context

Prior to this decision, `retro-common` and `retro-lsp` lacked formal syntax diagnostic reporting:
1. `retro-common` only attached unstructured string attributes (`TokenAttribute.ERROR` / `TokenType.TOK_ERROR`) to individual tokens in memory.
2. Missing block delimiters (such as `endfunction` or `.endif`) were silently tolerated: `SymbolService` clamped unclosed scopes to EOF without warnings, and conditional `.if`/`.endif` nesting was untracked.
3. Instruction argument count and operand matching was never verified during indexing.
4. `retro-lsp` did not implement or publish `textDocument/publishDiagnostics` to language clients, leaving VS Code's Problems view empty and lacking squiggly error indications.
5. `sfx_audit_invariants` in the MCP server only checked 4 specific hardware invariants, leaving general syntax errors and stack imbalance uninspected.

## Decisions

1. **First-Class Diagnostics Model in `retro-common`**:
   - Introduce `SyntaxDiagnostic` (URI, 1-based line, character range, error code, `DiagnosticSeverity`, message) and `DiagnosticSeverity` (`ERROR`, `WARNING`, `INFO`, `HINT`).
   - Store diagnostic collections on `TokenizedFile`.
   - Implement `BlockStructureValidator` to enforce matching block pairs (`function`/`endfunction`, `.if`/`.endif`, `.proc`/`.endproc`, `.scope`/`.endscope`, `.struct`/`.endstruct`, `.macro`/`.endmacro`, `for`/`endfor`), flagging unclosed openers at EOF, orphan closers, and mismatched nesting.
   - Implement `InstructionSyntaxValidator` to evaluate GSU instruction operand arity using `Instructions` and validate X-GSU macro signatures (`function`, `call`, `return`).
   - Implement duplicate symbol detection within lexical scopes.

2. **Stack Cleanup Verification in `retro-lsp` / `sfx-optimizer`**:
   - Utilize `sfx-optimizer`'s `StackDepthTracker` to verify the **Stack Displacement Invariant**:
     - All exit edges terminating at `return` or `endfunction` must resolve to $\Delta\text{SP} = 0$.
     - Control-flow join points must have consistent stack displacement across all incoming branches.

3. **LSP Diagnostic Coordinator & Native VS Code Publishing**:
   - Pass `LanguageClient` to `CA65LanguageServer` upon initialization in `Main.java`.
   - Implement a `DiagnosticCoordinator` that aggregates syntax diagnostics from `retro-common`, hardware invariants, and stack analysis, mapping them to LSP `Diagnostic` structures.
   - Publish diagnostics via `LanguageClient.publishDiagnostics` on `didOpen`, `didChange` (debounced), and `didSave`, and publish an empty array on `didClose`.

4. **Unified MCP Tool (`sfx_audit_diagnostics`)**:
   - Expand `sfx_audit_invariants` into a unified `sfx_audit_diagnostics` tool on `RetroLspMcpServer`.
   - Categorize findings into `SYNTAX`, `ARITY`, `HARDWARE_INVARIANT`, and `STACK`.
   - Retain `sfx_audit_invariants` as an alias for backward compatibility.
   - Update agent skills (`sfx-macro-language`, `build-workspace`, `sfx-test-runner`) and `AGENTS.md` to require calling `sfx_audit_diagnostics` after authoring or refactoring SuperFX code.

## Consequences

- Real-time syntax errors and block mismatch errors appear immediately as native red squigglies and in the VS Code Problems pane.
- Agents receive structured diagnostic feedback through the MCP tool suite before running emulator tests.
- Clear separation of concerns: `retro-common` stays pure Java and lightweight; `sfx-optimizer` provides CFG and stack analysis; `retro-lsp` coordinates and publishes diagnostics.

