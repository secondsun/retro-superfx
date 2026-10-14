# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`GLOSSARY-MAP.md`** at the repo root: points at one `GLOSSARY.md` per subproject context. Read each one relevant to the topic.
- **`docs/adr/`**: read root/umbrella ADRs that touch system-wide decisions.
- **`<subproject>/docs/adr/`**: check context-scoped decisions for each subproject (`retro-common`, `sfx-optimizer`, `snes-sfx-demo`, `retro-lsp`, `java-language-server`).

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and `/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

## File structure

Multi-context repo (presence of `GLOSSARY-MAP.md` at the root):

```
/
├── GLOSSARY-MAP.md
├── docs/adr/                          ← system-wide decisions
├── java-language-server/
│   ├── GLOSSARY.md
│   └── docs/adr/
├── retro-common/
│   ├── GLOSSARY.md
│   └── docs/adr/
├── sfx-optimizer/
│   ├── GLOSSARY.md
│   └── docs/adr/
├── retro-lsp/
│   ├── GLOSSARY.md
│   └── docs/adr/
└── snes-sfx-demo/
    ├── GLOSSARY.md
    └── docs/adr/
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `GLOSSARY.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal: either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (event-sourced orders), but worth reopening because…_

