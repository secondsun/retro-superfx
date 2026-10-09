# retro-superfx

An umbrella workspace coordinating retro gaming libraries, compilers, language tooling, and homebrew demos targeting the **Super Nintendo Entertainment System (SNES)** and the **SuperFX (GSU / FX3)** RISC coprocessor.

---

## Workspace Architecture

This repository links 5 specialized Git submodules:

```
                                [java-language-server]
                             (LSP protocol models & RPC)
                                          │
                                          ├────────────────────────┐
                                          ▼                        ▼
                                    [retro-common] ──> [sfx-optimizer] ──> [retro-lsp]
                              (Lexer, parser, AST)     (Register allocator    (VS Code extension
                                          │             & compiler pass)      & server backend)
                                          │                        │
                                          │                        ▼ (future compiler hook)
                                          └───────────────> [snes-sfx-demo]
                                                      ├── libSFX (SDK & cc65 tools)
                                                      ├── X-GSU  (Macro DSL & 3D math)
                                                      └── SpriteScale / edge-table (Demos)
```

| Subproject | Description | Tech Stack |
| :--- | :--- | :--- |
| **[`java-language-server`](./java-language-server)** | Zero-dependency pure-Java LSP data models and JSON-RPC message pump. | Java 26, Maven |
| **[`retro-common`](./retro-common)** | Lexer, parser, tokenization, symbol indexing, and doc extraction for CA65 / SuperFX / X-GSU. | Java 26, Maven |
| **[`sfx-optimizer`](./sfx-optimizer)** | Compiler pass, register allocator (Chaitin-Briggs), stack depth tracker, and web visualizer. | Kotlin, Java 26, Maven |
| **[`retro-lsp`](./retro-lsp)** | SNES/SuperFX Language Server Protocol backend and VS Code extension client. | Java 26, JLink, TypeScript, Node.js |
| **[`snes-sfx-demo`](./snes-sfx-demo)** | SuperFX homebrew codebase: `libSFX` SDK, `X-GSU` macro language, 3D math, and test suite. | SuperFX Assembly, ca65, C, Lua |

---

## Prerequisites

Ensure the following tools are installed on your host system:

1. **Java Development Kit (JDK) 26+**:
   ```bash
   java -version
   ```
2. **Python 3.10+**: Used for symbol map synchronization and test harness scripts.
   ```bash
   python3 --version
   ```
3. **Node.js 20+ & npm**: Required for building the `retro-lsp` VS Code extension.
   ```bash
   node --version && npm --version
   ```
4. **GNU Make**: Required for building SNES ROMs and running unit tests.
   ```bash
   make --version
   ```
5. **Mesen CE (SNES Emulator)**: Required for running headless automated tests.
   - Recommended location: `/home/summers/Programs/Mesen` or available on your system `PATH` as `Mesen` / `mesen`.

> **Note on Assembler Toolchain**: Precompiled `cc65` binaries (`ca65`, `ld65`, `superfamicheck`) are bundled directly inside `snes-sfx-demo/libSFX/tools/cc65/bin/` and used automatically by the build system.

---

## Installation & Setup

### 1. Clone the Umbrella Repository

Clone with all submodules initialized and populated:

```bash
git clone --recurse-submodules git@github.com:secondsun/retro-superfx.git
cd retro-superfx
```

If you already cloned the repository without `--recurse-submodules`:

```bash
git submodule update --init --recursive
```

---

### 2. Check Workspace Status

Run the workspace status script to verify all submodules, active branches, and working tree states:

```bash
./tools/status.sh
```

---

### 3. Install Upstream JVM Artifacts to Local Maven Cache

Because downstream projects (`sfx-optimizer` and `retro-lsp`) depend on `retro-common` and `languageserver`, build and install them to your local `~/.m2` repository:

```bash
./tools/install-local.sh
```

This builds and installs in strict topological order:
1. `java-language-server` (`dev.secondsun:languageserver`)
2. `retro-common` (`dev.secondsun:retro-common`)
3. `sfx-optimizer` (`dev.secondsun:sfx-optimizer`)

---

### 4. Build and Verify the Entire Stack

To compile and execute unit tests across all JVM projects:

```bash
./tools/build-all.sh
```

#### Build Options:
- **Build and install locally**:
  ```bash
  ./tools/build-all.sh --install
  ```
- **Build everything and compile SNES sample ROMs**:
  ```bash
  ./tools/build-all.sh --install --roms
  ```
- **Fast compile (skip test execution)**:
  ```bash
  ./tools/build-all.sh --skip-tests
  ```

---

## Working with SuperFX Assembly & Tests

### Running Automated SuperFX Unit Tests

Automated unit tests live in `snes-sfx-demo/X-GSU/tests/` and execute via Mesen CE in headless mode with Lua breakpoints:

```bash
./tools/run-sfx-test.sh <test_name> [timeout_in_seconds]
```

Examples:
```bash
# Run 3D vector addition test
./tools/run-sfx-test.sh vector3_add 10

# Run 3D vector subtraction test
./tools/run-sfx-test.sh vector3_subtract 10

# Run vector normalization test
./tools/run-sfx-test.sh normalize 10
```

`run-sfx-test.sh` automatically:
1. Compiles the test ROM using `ca65`/`ld65` and `libSFX`.
2. Synchronizes RAM symbol addresses from `Test.cpu.sym` into `test.lua`.
3. Launches Mesen CE headlessly (`--testrunner`) and validates test assertions.

---

### Launching the SFX Optimizer Code Viewer

To visually inspect SuperFX variable liveness swimlanes and register allocations:

```bash
cd sfx-optimizer
./mvnw test -Dtest=ViewerServerTest#launchViewerManually
```
Then open your browser to `http://localhost:8080`.

---

## Submodule Management

- **Pull latest commits across all submodules**:
  ```bash
  git submodule update --remote --merge
  ```
- **Run a Git command across all submodules**:
  ```bash
  git submodule foreach 'git status -s'
  ```
- **Record updated submodule pointers in the root repository**:
  ```bash
  git add <submodule-dir>
  git commit -m "chore: bump <submodule-dir> commit pointer"
  ```

---

## Agentic Infrastructure

This root repository includes agent configuration and automation guides:
- **[`AGENTS.md`](./AGENTS.md)**: Architectural reference, calling conventions, and cross-repo invariants.
- **[`.agents/rules/`](./.agents/rules/)**:
  - `cross-repo-invariants.md`: Guidelines for dependency hygiene and Maven synchronization.
  - `sfx-assembly-rules.md`: Hardware constraints for the SuperFX 3 (FX3) coprocessor.
- **[`.agents/skills/`](./.agents/skills/)**:
  - `build-workspace`: Multi-repo build and install procedures.
  - `sfx-macro-language`: X-GSU ca65 macro language reference (`function`, `call`, `stack`, `control`).
  - `sfx-test-runner`: Automated Mesen CE test execution runbook.
  - `update-submodules`: Stage, commit, and push updated Git submodules to origin.

