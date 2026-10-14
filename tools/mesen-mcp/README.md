# Mesen MCP Server

Model Context Protocol (MCP) server providing SuperFX test execution, headless Mesen emulation, `.dbg` file symbol parsing, and runtime assertions.

## Setup

```bash
# Set up venv and dependencies
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
pip install -e .
```

## Running the Server

```bash
# Run standalone stdio server
./run-server.sh
# Or directly with python
python3 -m mesen_mcp.server
```
