# Mesen MCP Server

Model Context Protocol (MCP) server providing SuperFX test execution, headless Mesen emulation, `.dbg` file symbol parsing, parameterized math assertions, and visual render captures.

## Features & Tools

- **`run_sfx_test(test_name, timeout_seconds=15)`**: Compiles and headlessly executes unit tests in `snes-sfx-demo/X-GSU/tests/<test_name>`, returning structured pass/fail results.
- **`assert_sfx_math(test_name, input_overrides, expected_outputs, epsilon=0, timeout_seconds=15)`**: Uses `.dbg` symbol resolution to inject test vectors into GSURAM or registers (`r0`–`r15`) and asserts routine results within tolerance without modifying base test sources.
- **`capture_sfx_render(scene_test_name, output_png_path=None, timeout_seconds=15)`**: Runs render scenes headlessly in Mesen, captures framebuffers to PNG via Pillow, and returns quantitative metrics (width, height, non-black pixel count, bounding box, MD5, and 64-bit difference hash).

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

## Testing

```bash
source .venv/bin/activate
pytest tests/
```

