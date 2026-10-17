# 0002. Persistent Workspace Index, Sandbox Boundary, and Lazy Indexing in Retro-LSP MCP Server

Date: 2026-10-16

## Status

Accepted

## Context

Originally, the Model Context Protocol (MCP) tool implementations in `retro-lsp` (such as `SfxSymbolLookupTool` and `SfxMacroExpanderTool`) were stateless and ephemeral: each tool invocation instantiated a new `SymbolService` or `FileService`, tokenized only the passed code snippet in isolation, and immediately discarded all extracted symbols and file tokens upon completion. This caused three major issues:
1. Symbol queries across the project workspace were impossible unless the entire project code was manually re-passed.
2. Macro expansions could not resolve `.include` or `.incbin` directives referencing other files in the workspace.
3. Every call suffered from redundant re-scanning and parsing overhead.

Furthermore, under the **MCP 2026-07-28 specification revision (SEP-2577)**, protocol-level `roots` (`roots/list` and `notifications/roots/list_changed`) were formally deprecated due to low client adoption, stateful complexity, and replacement by explicit server launch configuration.

## Decisions

1. **Shared Persistent State via ProjectService**:
   - `RetroLspMcpServer` owns and persists a single `ProjectService` (holding shared instances of `FileService` and `SymbolService`) for the entire lifetime of the MCP server process.
   - All MCP tools (`SfxSymbolLookupTool`, `SfxMacroExpanderTool`, `SfxInvariantAuditor`, and `SfxRegisterAnalysisTool`) receive this shared `ProjectService` or its child services via constructor injection.

2. **Required Workspace Parameter & Removal of Deprecated Protocol Roots**:
   - The `--workspace <path>` CLI option is a **required** launch parameter when running `retro-lsp --mcp`. If missing, the process fails fast with an error message and non-zero exit code.
   - Protocol-level `listRoots` and `rootsChangeConsumer` are completely removed, following the modern MCP 2026-07-28 specification.

3. **Workspace Sandbox Boundary Guard**:
   - All tools validate that any passed file `uri` strictly resolves inside `workspaceRoot` or its subdirectories.
   - Any URI outside the boundary is blocked with an explicit security error (`Access denied: URI is outside the configured workspace boundary`), preventing path traversal.
   - Synthetic in-memory snippet URIs (e.g. `file:///snippet.s`) are permitted for raw evaluation.

4. **Lazy, On-Demand Indexing**:
   - Server startup is instant: no full-disk scan occurs when the server starts.
   - When a tool is called with a specific `uri`, its parent directory is indexed on demand (`projectService.includeDir(...)`) and watched.
   - When a global query (such as `sfx_lookup_symbol`) is invoked without a `uri` before indexing has occurred, it lazily triggers workspace root indexing at that point.

5. **Background File Watching with Java NIO WatchService**:
   - Live servers (e.g., started via `createStdio(workspaceRoot)`) spin up a background daemon virtual thread running `java.nio.file.WatchService`.
   - The watcher registers directories recursively (excluding `.git`, `node_modules`, `target`, `build`, `out`, `dist`) and listens for `ENTRY_CREATE`, `ENTRY_MODIFY`, and `ENTRY_DELETE` on source files (`*.s`, `*.i`, `*.inc`, `*.sgs`).
   - Newly created subdirectories are automatically registered with the watcher.
   - On file deletion, `ProjectService.removeFile(URI)` purges the cached file and delegates to `symbolService.removeFile(uri)`.

6. **Tool Invocations & Buffer Precedence**:
   - For all tools (`sfx_lookup_symbol`, `sfx_expand_macro`, `sfx_audit_invariants`, `sfx_analyze_registers`), `code` is optional when a valid workspace `uri` is provided; the tool reads directly from `projectService.getFileContents(uri)`.
   - When both `code` and `uri` are provided, the tool calls `projectService.updateFileContents(uri, code)`, updating the in-memory shared index immediately.

7. **Concurrency & Hermetic Testing**:
   - Mutations on `ProjectService` (`updateFileContents`, `refreshFileContents`, `removeFile`) and the underlying `SymbolService` are synchronized to prevent `ConcurrentModificationException` during concurrent tool calls and watcher events.
   - The default no-arg constructor `new RetroLspMcpServer()` operates in-memory without binding a background disk `WatchService`, keeping unit tests fast, hermetic, and deterministic.

## Consequences

- All MCP tools share a single authoritative source of truth that is indexed lazily and kept current.
- Strict security boundaries prevent unintended file disclosure or path traversal.
- Conforms fully to the modern MCP specification (SEP-2577) without deprecated roots dependencies.
- Macro expansion resolves project includes seamlessly.
- Agents can query symbols, expand macros, audit invariants, and analyze registers by file URI without transmitting entire file bodies over JSON-RPC.
- Test suites remain hermetic and fast.
