import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile
from typing import Any, Dict, Optional

from PIL import Image

from mesen_mcp.dbg_parser import parse_dbg_file
from mesen_mcp.mesen import find_mesen
from mesen_mcp.utils import get_repo_root, resolve_test_dir


def _compute_image_metrics(png_path: Path) -> Dict[str, Any]:
    """Calculate quantitative visual metrics for a rendered frame."""
    with open(png_path, "rb") as f:
        png_bytes = f.read()

    md5_hash = hashlib.md5(png_bytes).hexdigest()

    im = Image.open(png_path).convert("RGB")
    width, height = im.size

    raw = im.tobytes()
    non_black_pixels = sum(1 for i in range(0, len(raw), 3) if raw[i] > 0 or raw[i + 1] > 0 or raw[i + 2] > 0)

    bbox = im.getbbox()
    bounding_box = list(bbox) if bbox is not None else None

    # Calculate difference hash (dHash) for perceptual comparison
    gray = im.convert("L").resize((9, 8), Image.Resampling.LANCZOS)
    pixels = list(gray.tobytes())
    diff = []
    for row in range(8):
        for col in range(8):
            diff.append(pixels[row * 9 + col + 1] > pixels[row * 9 + col])
    phash_bits = "".join("1" if b else "0" for b in diff)
    perceptual_hash = f"{int(phash_bits, 2):016x}"

    return {
        "width": width,
        "height": height,
        "non_black_pixels": non_black_pixels,
        "bounding_box": bounding_box,
        "md5": md5_hash,
        "perceptual_hash": perceptual_hash,
    }


def capture_sfx_render(
    scene_test_name: str,
    output_png_path: Optional[str] = None,
    timeout_seconds: int = 15,
    mesen_path: Optional[str] = None,
) -> Dict[str, Any]:
    """Execute a SuperFX scene or test ROM headlessly and capture its framebuffer."""
    try:
        scene_dir = resolve_test_dir(scene_test_name)
    except FileNotFoundError as e:
        return {
            "status": "FAIL",
            "error": str(e),
            "png_path": None,
            "metrics": {},
        }

    # 1. Compile test ROM via make clean all if Makefile present
    if (scene_dir / "Makefile").is_file():
        make_res = subprocess.run(
            ["make", "-C", str(scene_dir), "clean", "all"],
            capture_output=True,
            text=True,
        )
        if make_res.returncode != 0:
            return {
                "status": "FAIL",
                "error": "Compilation failed",
                "output": make_res.stdout + "\n" + make_res.stderr,
                "png_path": None,
                "metrics": {},
            }

    # 2. Locate ROM file
    sfc_candidates = list(scene_dir.glob("*.sfc"))
    if not sfc_candidates:
        return {
            "status": "FAIL",
            "error": f"No .sfc ROM found in {scene_dir}",
            "png_path": None,
            "metrics": {},
        }
    sfc_file = sfc_candidates[0]
    # Prefer Test.sfc or SuperFX.sfc if present
    for preferred in ("Test.sfc", "SuperFX.sfc"):
        cand = scene_dir / preferred
        if cand.is_file():
            sfc_file = cand
            break

    # 3. Locate symbols for test_stop if available
    test_stop_addr = None
    dbg_file = scene_dir / "Test.dbg"
    if not dbg_file.is_file():
        dbg_candidates = list(scene_dir.glob("*.dbg"))
        if dbg_candidates:
            dbg_file = dbg_candidates[0]

    if dbg_file.is_file():
        try:
            dbg_data = parse_dbg_file(dbg_file)
            if "test_stop" in dbg_data.symbols:
                test_stop_addr = dbg_data.symbols["test_stop"].offset
        except Exception:
            pass

    # 4. Resolve output PNG path
    if output_png_path:
        target_png = Path(output_png_path).resolve()
        target_png.parent.mkdir(parents=True, exist_ok=True)
    else:
        fd, tmp_file = tempfile.mkstemp(prefix="sfx_capture_", suffix=".png")
        os.close(fd)
        target_png = Path(tmp_file).resolve()

    # 5. Resolve Mesen binary
    try:
        mesen_bin = find_mesen(override_path=mesen_path)
    except FileNotFoundError as e:
        return {
            "status": "FAIL",
            "error": str(e),
            "png_path": None,
            "metrics": {},
        }

    # 6. Generate ephemeral Lua capture script
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

    with tempfile.NamedTemporaryFile(suffix=".lua", mode="w", encoding="utf-8", delete=False) as lua_tmp:
        lua_script_path = lua_tmp.name
        lua_tmp.write(lua_code)

    # 7. Execute Mesen
    cmd = [
        str(mesen_bin),
        "--testrunner",
        f"--timeout={timeout_seconds}",
        str(lua_script_path),
        str(sfc_file),
    ]

    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout_seconds + 5,
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
    finally:
        try:
            if os.path.isfile(lua_script_path):
                os.remove(lua_script_path)
        except OSError:
            pass
