"""Headless framebuffer rendering capture and image verification for SuperFX scenes."""

import hashlib
from pathlib import Path
from typing import Any, Dict, Optional

from PIL import Image

from mesen_mcp.session import MesenRunner, SfxTestSession


def _compute_image_metrics(png_path: Path) -> Dict[str, Any]:
    """Compute visual, hashing, and pixel metrics for a rendered frame."""
    with Image.open(png_path) as img:
        img_rgb = img.convert("RGB")
        width, height = img_rgb.size
        get_pixels = getattr(img_rgb, "get_flattened_data", img_rgb.getdata)
        pixels = list(get_pixels())

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

    bbox = [min_x, min_y, max_x, max_y] if non_black > 0 else None

    md5_hash = hashlib.md5(png_path.read_bytes()).hexdigest()

    with Image.open(png_path) as img:
        small = img.convert("L").resize((8, 8), Image.Resampling.BILINEAR)
        get_p_pixels = getattr(small, "get_flattened_data", small.getdata)
        p_pixels = list(get_p_pixels())
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


def _build_capture_lua(
    session: SfxTestSession, target_png: Path, settle_frames: int
) -> str:
    test_stop_addr = session.resolve_gsu_exec_addr("test_stop")
    escaped_png_path = (
        str(target_png).replace("\\", "\\\\").replace('"', '\\"')
    )

    test_stop_hook = ""
    if test_stop_addr is not None:
        cb = session.emit_gsu_exec_callback(
            "function(address, value) gsu_stopped = true end",
            test_stop_addr,
        )
        test_stop_hook = f"\n{cb}\n"

    has_test_stop_lua = "true" if test_stop_addr is not None else "false"
    return f"""-- Ephemeral Mesen Render Capture Script
local target_png = "{escaped_png_path}"
local frameCount = 0
local postStopFrames = 0
local captured = false
local gsu_stopped = false
local has_test_stop = {has_test_stop_lua}
local min_frames = {settle_frames}

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
    if gsu_stopped then
        postStopFrames = postStopFrames + 1
        if postStopFrames >= 8 and frameCount >= min_frames then
            doCapture()
        end
    elseif not has_test_stop and frameCount >= (min_frames > 0 and min_frames or 60) then
        doCapture()
    elseif frameCount >= (min_frames > 0 and min_frames or 120) then
        doCapture()
    end
end, emu.eventType.endFrame)
{test_stop_hook}"""


def capture_sfx_render(
    scene_test_name: str,
    output_png_path: Optional[str] = None,
    settle_frames: int = 0,
    timeout_seconds: int = 15,
    mesen_path: Optional[str] = None,
    runner: Optional[MesenRunner] = None,
) -> Dict[str, Any]:
    """Execute a SuperFX scene or test ROM headlessly and capture its framebuffer."""
    try:
        session = SfxTestSession.from_test_name(
            scene_test_name, runner=runner, mesen_path=mesen_path
        )
    except FileNotFoundError as e:
        return {
            "status": "FAIL",
            "error": str(e),
            "png_path": None,
            "metrics": {},
        }

    try:
        outcome = session.run_harness(
            lambda s, ctx: _build_capture_lua(
                s,
                target_png=ctx.output_png_path,  # type: ignore[arg-type]
                settle_frames=settle_frames,
            ),
            timeout_seconds=timeout_seconds,
            output_png_path=output_png_path,
            keep_temp_png_on_success=(output_png_path is None),
            compile_mode="if_stale",
        )
    except RuntimeError as e:
        return {
            "status": "FAIL",
            "error": "Compilation failed",
            "output": str(e),
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

    if outcome.timed_out:
        return {
            "status": "FAIL",
            "error": f"Execution timed out after {timeout_seconds} seconds",
            "output": outcome.output,
            "png_path": None,
            "metrics": {},
        }

    if outcome.png_path is None:
        return {
            "status": "FAIL",
            "error": "Mesen did not generate screenshot file",
            "output": outcome.output,
            "png_path": None,
            "metrics": {},
        }

    metrics = _compute_image_metrics(outcome.png_path)
    return {
        "status": "SUCCESS",
        "png_path": str(outcome.png_path),
        "metrics": metrics,
    }
