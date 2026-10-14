import os
from pathlib import Path
import pytest
from mesen_mcp.render_capture import capture_sfx_render


def test_capture_sfx_render_spritescale():
    result = capture_sfx_render(
        scene_test_name="SpriteScale",
        timeout_seconds=15,
    )
    assert result["status"] == "SUCCESS"
    assert result["png_path"] is not None

    png_path = Path(result["png_path"])
    assert png_path.is_file()
    assert png_path.stat().st_size > 0

    metrics = result["metrics"]
    assert metrics["width"] == 256
    assert metrics["height"] == 224
    assert metrics["non_black_pixels"] > 0
    assert metrics["bounding_box"] is not None
    assert len(metrics["bounding_box"]) == 4
    assert len(metrics["md5"]) == 32
    assert len(metrics["perceptual_hash"]) == 16

    # Clean up temporary screenshot
    try:
        os.remove(png_path)
    except OSError:
        pass


def test_capture_sfx_render_custom_output(tmp_path):
    custom_png = tmp_path / "custom_capture.png"
    result = capture_sfx_render(
        scene_test_name="SpriteScale",
        output_png_path=str(custom_png),
        timeout_seconds=15,
    )
    assert result["status"] == "SUCCESS"
    assert result["png_path"] == str(custom_png)
    assert custom_png.is_file()
    assert custom_png.stat().st_size > 0

    metrics = result["metrics"]
    assert metrics["width"] == 256
    assert metrics["height"] == 224
    assert metrics["non_black_pixels"] > 0


def test_capture_sfx_render_sqrt_math_scene():
    result = capture_sfx_render(
        scene_test_name="sqrt",
        timeout_seconds=15,
    )
    assert result["status"] == "SUCCESS"
    assert result["png_path"] is not None

    png_path = Path(result["png_path"])
    assert png_path.is_file()

    metrics = result["metrics"]
    assert metrics["width"] == 256
    assert metrics["height"] == 224
    assert metrics["non_black_pixels"] == 0
    assert metrics["bounding_box"] is None

    try:
        os.remove(png_path)
    except OSError:
        pass


def test_capture_sfx_render_nonexistent():
    result = capture_sfx_render(
        scene_test_name="nonexistent_scene_xyz",
        timeout_seconds=15,
    )
    assert result["status"] == "FAIL"
    assert "not found" in result["error"].lower()
    assert result["png_path"] is None
