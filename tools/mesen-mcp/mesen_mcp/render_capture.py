"""Headless framebuffer rendering capture and image verification for SuperFX scenes."""

import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
from typing import Any, Dict, Optional, Tuple

from PIL import Image

from mesen_mcp.session import MesenRunner, SfxTestSession
from mesen_mcp.utils import resolve_test_dir


def _compute_image_metrics(png_path: Path) -> Dict[str, Any]:
    """Compute visual, hashing, and pixel metrics for a rendered frame."""
    with Image.open(png_path) as img:
        img_rgb = img.convert("RGB")
        width, height = img_rgb.size
        pixels = list(img_rgb.getdata())

    # Count non-black pixels and compute bounding box
    non_black = 0
    min_x, min_y = width, height
    max_x, max_y = -1, -1

    for idx, (r, g, b) in enumerate(pixels):
        if r > 0 or g > 0 or b > 0:
            non_black += 1
            x = idx % width
            y = idx // width
            if x < min_x:
                min_x = x
            if x > max_x:
                max_x = x
            if y < min_y:
                min_y = y
            if y > max_y:
                max_y = y

    bbox = None
    if non_black > 0:
        bbox = [min_x, min_y, max_x, max_y]

    # File hash
    with open(png_path, "rb") as f:
        md5_hash = hashlib.md5(f.read()).hexdigest()

    # Perceptual average hash (8x8 grayscale)
    with Image.open(png_path) as img:
        small = img.convert("L").resize((8, 8), Image.Resampling.BILINEAR)
        p_pixels = list(small.getdata())
        avg = sum(p_pixels) / len(p_pixels)
        bits = "".join(["1" if p > avg else "0" for p in p_pixels])
        p_hash = f"{int(bits, 2):016x}"

    return {
        "width": width,
        "height": height,
        "non_black_pixels": non_black,
        "bounding_box": bbox,
        "md5": md5_hash,
        "perceptual_hash": p_hash,
    }


def capture_sfx_render(
    scene_test_name: str,
    output_png_path: Optional[str] = None,
    timeout_seconds: int = 15,
    mesen_path: Optional[str] = None,
    runner: Optional[MesenRunner] = None,
) -> Dict[str, Any]:
    """Execute a SuperFX scene or test ROM headlessly and capture its framebuffer."""
    try:
        session = SfxTestSession.from_test_name(
            scene_test_name, runner=runner, mesen_path=mesen_path
        )
        if (session.test_dir / "Makefile").is_file():
            session.compile()
    except FileNotFoundError as e:
        return {
            "status": "FAIL",
            "error": str(e),
            "png_path": None,
            "metrics": {},
        }
    except RuntimeError as e:
        return {
            "status": "FAIL",
            "error": "Compilation failed",
            "output": str(e),
            "png_path": None,
            "metrics": {},
        }

    # Locate symbols for test_stop if available
    test_stop_addr = session.symbols.get_offset("test_stop")

    # Resolve output PNG path
    if output_png_path:
        target_png = Path(output_png_path).resolve()
        target_png.parent.mkdir(parents=True, exist_ok=True)
    else:
        fd, tmp_file = tempfile.mkstemp(prefix="sfx_capture_", suffix=".png")
        os.close(fd)
        target_png = Path(tmp_file).resolve()

    # Generate ephemeral Lua capture script
    escaped_png_path = str(target_png).replace("\\", "\\\\").replace('"', '\\"')

    test_stop_hook = ""
    if test_stop_addr is not None:
        test_stop_hook = f"""
emu.addMemoryCallback(function(address, value)
    gsu_stopped = true
end, emu.callbackType.exec, 0x{test_stop_addr:04X}, 0x{test_stop_addr:04X}, 4, emu.memType.gsuWorkRam)
"""

    lua_code = f"""-- Ephemeral Mesen Render Capture Script
local target_png = "{escaped_png_path}"
local frameCount = 0
local captured = false
local gsu_stopped = false

function doCapture()
    if captured then return end
    captured = true
    local png = emu.takeScreenshot()
    local f = io.open(target_png, "wb")
    if f then
        f:write(png)
        f:close()
        print("__CAPTURE_SAVED__")
    else
        print("__CAPTURE_FAILED__")
    end
    emu.stop(0)
end

emu.addEventCallback(function()
    frameCount = frameCount + 1
    if gsu_stopped or frameCount >= 15 then
        doCapture()
    end
end, emu.eventType.endFrame)

{test_stop_hook}
"""

    with tempfile.NamedTemporaryFile(
        suffix=".lua", mode="w", encoding="utf-8", delete=False
    ) as lua_tmp:
        lua_script_path = lua_tmp.name
        lua_tmp.write(lua_code)

    try:
        proc = session.run_lua(
            Path(lua_script_path), timeout_seconds=timeout_seconds
        )
        combined_output = proc.stdout + (("\n" + proc.stderr) if proc.stderr else "")

        if not target_png.is_file() or target_png.stat().st_size == 0:
            return {
                "status": "FAIL",
                "error": "Mesen did not generate screenshot file",
                "output": combined_output.strip(),
                "png_path": None,
                "metrics": {},
            }

        metrics = _compute_image_metrics(target_png)
        return {
            "status": "SUCCESS",
            "png_path": str(target_png),
            "metrics": metrics,
        }

    except subprocess.TimeoutExpired as te:
        stdout = te.stdout or ""
        stderr = te.stderr or ""
        return {
            "status": "FAIL",
            "error": f"Execution timed out after {timeout_seconds} seconds",
            "output": (stdout + "\n" + stderr).strip(),
            "png_path": None,
            "metrics": {},
        }
    except Exception as e:
        return {
            "status": "FAIL",
            "error": str(e),
            "output": "",
            "png_path": None,
            "metrics": {},
        }
    finally:
        try:
            if os.path.isfile(lua_script_path):
                os.remove(lua_script_path)
        except OSError:
            pass
