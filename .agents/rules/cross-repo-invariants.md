# Cross-Repository Invariants & Standards

This document specifies invariants and rules for AI coding agents and human developers operating across the `retro-superfx` multi-repository workspace.

---

## 1. Multi-Repository Workspace Isolation

1. **Independent Git Repositories**:
   - Every subproject (`java-language-server`, `retro-common`, `sfx-optimizer`, `retro-lsp`, `snes-sfx-demo`) is a standalone Git repository with its own remote, commit history, and branches.
   - Do **NOT** commit changes from one repository into another.
   - The root directory (`retro-superfx`) acts as an agentic umbrella workspace for cross-cutting tasks.
2. **Strict Acyclic Dependency Topology**:
   - The dependency flow is strictly directed and acyclic:
     ```
     [java-language-server]
            │
            ├────────────────────────┐
            ▼                        ▼
     [retro-common] ──> [sfx-optimizer] ──> [retro-lsp]
            │                  │
            │                  ▼ (future compiler pass)
            └───────────> [snes-sfx-demo / X-GSU]
     ```
   - Never introduce reverse or circular dependencies between projects.

---

## 2. Java / Kotlin Toolchain Rules

1. **JDK Target**:
   - All JVM projects target **Java 26** (`--release 26`). Use modern Java language features (switch expressions, pattern matching, records, modules).
2. **Maven Wrapper**:
   - Always invoke `./mvnw` from the respective project directory (or use `./tools/build-all.sh`). Do not rely on unversioned system `mvn`.
3. **Local Artifact Synchronization**:
   - When updating upstream libraries (`java-language-server`, `retro-common`, or `sfx-optimizer`), always install them to the local `~/.m2` cache using:
     ```bash
     ./mvnw clean install -DskipGpg=true
     # or use workspace script:
     ./tools/install-local.sh
     ```
   - Downstream consumers (`sfx-optimizer` and `retro-lsp`) will otherwise fail to resolve modified API symbols.
4. **Code Formatting**:
   - Java code enforces Google Java Format via Spotless (`./mvnw spotless:check` / `./mvnw spotless:apply`).
   - Run formatting checks before committing changes.

---

## 3. SuperFX / X-GSU Assembly Rules

1. **Target Hardware**:
   - Target is **SuperFX 3 (FX3)** coprocessor running at 21.47 MHz (or RP2350B emulation in Mesen CE).
2. **Critical Hardware Constraints**:
   - **No `MERGE` Instruction**: In FX3, `MERGE` is not implemented in hardware. Always use bit-manipulation workarounds (`hib`, `lob`, `swap`, `or`).
   - **8 NOP Pipeline Settling**: SuperFX Work RAM writes are pipelined asynchronously. Insert **8 NOPs** before halting (`stop`) or testing memory state in emulator Lua scripts.
   - **Word Alignment**: All 16-bit word loads (`ldw`) and stores (`stw`) must be aligned to even addresses (`$2` boundary).
   - **Stack Integrity**: The software stack pointer is `R10`. Never push variables onto the stack inside `for ... endfor` loops without popping them before `endfor` (as `endfor` expects `R13`/`R12` on the stack).
   - **Prefix Instructions**: `with`, `to`, and `from` modify registers for the immediately following instruction only. Non-prefix arithmetic resets `Sreg` and `Dreg` back to `R0`.

---

## 4. X-GSU and libSFX Integration

1. **libSFX Subsystem**:
   - `snes-sfx-demo/libSFX` contains a patched copy of libSFX with bundled precompiled cc65 binaries in `libSFX/tools/cc65/bin`.
   - All ROM builds depend on `libSFX/libSFX.make`.
2. **X-GSU Macro Language**:
   - X-GSU defines high-level macros (`function`, `call`, `return`, `stack`, `control`).
   - When writing GSU routines, adhere to the macro conventions documented in `AGENTS.md` and `.agents/skills/sfx-macro-language`.

