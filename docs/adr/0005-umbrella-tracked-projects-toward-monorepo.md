# Track New Top-Level Projects Directly in the Umbrella Repo Toward a Monorepo

Status: accepted

`sfx-studio` (promoted from `snes-sfx-demo/build_tools/texture-packer`) and future top-level projects in `retro-superfx` are tracked directly inside the umbrella Git repository rather than mounted as standalone Git submodules. While the five original subprojects (`java-language-server`, `retro-common`, `sfx-optimizer`, `retro-lsp`, and `snes-sfx-demo`) remain submodules temporarily, the long-term architectural target is consolidating the workspace into a single monorepo. Tracking `sfx-studio` directly at the root avoids creating a sixth submodule and remote only to fold it back later, enables atomic commits across `sfx-studio` code, skills, and workspace evaluations, and avoids duplicate commit graphs when `snes-sfx-demo` is eventually merged into the monorepo.
