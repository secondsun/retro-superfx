from __future__ import annotations

from pathlib import Path
import sys


TOOLS_DIR = Path(__file__).resolve().parents[2]
MESEN_MCP_DIR = TOOLS_DIR / "mesen-mcp"

for p in (str(TOOLS_DIR), str(MESEN_MCP_DIR)):
    if p not in sys.path:
        sys.path.insert(0, p)
