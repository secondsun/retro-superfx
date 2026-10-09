---
name: build-workspace
description: >-
  Build, test, verify, and locally install projects across the retro-superfx multi-repository workspace.
  Use when asked to compile all projects, run test suites across the repository, synchronize local
  Maven dependencies, or inspect workspace health.
---

# Workspace Build & Dependency Synchronization

This skill orchestrates building, testing, and installing subprojects across the `retro-superfx` workspace in strict topological dependency order.

---

## 1. Dependency Topology

The projects must be built in this sequence:
1. `java-language-server` (`dev.secondsun:languageserver`)
2. `retro-common` (`dev.secondsun:retro-common`)
3. `sfx-optimizer` (`dev.secondsun:sfx-optimizer`)
4. `retro-lsp` (`dev.secondsun:retro-lsp`)
5. `snes-sfx-demo` (SNES ROM compilation via `libSFX` and CC65)

---

## 2. Common Workflows

### A. Quick Workspace Status
Check git branches, dirty trees, and Maven versions across all 5 repositories:
```bash
./tools/status.sh
```

### B. Full Build & Test (All JVM Projects)
Build and run unit tests for all Java/Kotlin projects:
```bash
./tools/build-all.sh
```

### C. Local Dependency Installation (Fast Dev Loop)
When modifying `retro-common` or `sfx-optimizer`, install updated artifacts into `~/.m2` without publishing to Maven Central:
```bash
./tools/install-local.sh
```

### D. Build Everything Including SNES ROMs
```bash
./tools/build-all.sh --install --roms
```

---

## 3. Individual Project Commands

| Project | Build & Test | Local Install | Spotless Format Check |
| :--- | :--- | :--- | :--- |
| **`java-language-server`** | `./mvnw clean test` | `./mvnw clean install -DskipGpg=true` | N/A |
| **`retro-common`** | `./mvnw clean test` | `./mvnw clean install -DskipGpg=true` | `./mvnw spotless:check` |
| **`sfx-optimizer`** | `./mvnw clean test` | `./mvnw clean install -DskipGpg=true` | `./mvnw spotless:check` |
| **`retro-lsp`** | `./mvnw clean test` | N/A (End consumer) | `./mvnw spotless:check` |
| **`snes-sfx-demo`** | `make -C X-GSU/tests/<name> clean all` | N/A | N/A |

---

## 4. Troubleshooting

1. **Symbol Not Found in downstream project (`retro-lsp` or `sfx-optimizer`)**:
   - Cause: Upstream changes in `retro-common` or `sfx-optimizer` have not been installed to `~/.m2`.
   - Fix: Run `./tools/install-local.sh`.
2. **Spotless Lint Failure**:
   - Run `./mvnw spotless:apply` inside the failing repository.

