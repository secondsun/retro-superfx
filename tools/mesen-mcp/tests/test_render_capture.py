import os
from pathlib import Path
import re
import subprocess

from PIL import Image

from mesen_mcp import FakeMesenRunner, capture_sfx_render


def _extract_target_png(lua_code: str) -> Path:
    m = re.search(r'local target_png = "([^"]+)"', lua_code)
    assert m is not None
    return Path(m.group(1).replace("\\\\", "\\"))


def _write_synthetic_snes_frame(path: Path, draw_box: bool = True) -> None:
    img = Image.new("RGB", (256, 224), (0, 0, 0))
    if draw_box:
        for y in range(10, 30):
            for x in range(20, 60):
                img.putpixel((x, y), (240, 120, 40))
    img.save(path, format="PNG")


def test_capture_sfx_render_metrics_and_24bit_gsu_memory_stop_hook(tmp_path):
    scene_dir = tmp_path / "scene_rom"
    scene_dir.mkdir()
    (scene_dir / "SuperFX.sfc").write_bytes(b"\x00" * 64)
    (scene_dir / "SuperFX.cpu.sym").write_text(
        "al 038020 .test_stop\n", encoding="utf-8"
    )

    def populate_png(_cmd, lua_code):
        assert lua_code is not None
        _write_synthetic_snes_frame(_extract_target_png(lua_code), draw_box=True)
        return None

    fake = FakeMesenRunner(stdout="__CAPTURE_SAVED__\n", on_run=populate_png)
    result = capture_sfx_render(
        scene_test_name=str(scene_dir),
        settle_frames=12,
        runner=fake,
    )

    assert result["status"] == "SUCCESS"
    assert result["png_path"] is not None
    png_path = Path(result["png_path"])
    assert png_path.is_file()

    metrics = result["metrics"]
    assert metrics["width"] == 256
    assert metrics["height"] == 224
    assert metrics["non_black_pixels"] == (30 - 10) * (60 - 20)
    assert metrics["bounding_box"] == [20, 10, 59, 29]
    assert len(metrics["md5"]) == 32
    assert len(metrics["perceptual_hash"]) == 16

    lua = fake.last_lua_code
    assert lua is not None
    assert "0x038020, 0x038020, emu.cpuType.gsu, emu.memType.gsuMemory" in lua

    try:
        os.remove(png_path)
    except OSError:
        pass


def test_capture_sfx_render_custom_output_and_black_frame(tmp_path):
    scene_dir = tmp_path / "continuous_scene"
    scene_dir.mkdir()
    (scene_dir / "SuperFX.sfc").write_bytes(b"\x00" * 64)

    custom_png = tmp_path / "out" / "custom_capture.png"

    def populate_black_png(_cmd, lua_code):
        assert lua_code is not None
        _write_synthetic_snes_frame(_extract_target_png(lua_code), draw_box=False)
        return None

    fake = FakeMesenRunner(stdout="__CAPTURE_SAVED__\n", on_run=populate_black_png)
    result = capture_sfx_render(
        scene_test_name=str(scene_dir),
        output_png_path=str(custom_png),
        runner=fake,
    )
    assert result["status"] == "SUCCESS"
    assert result["png_path"] == str(custom_png.resolve())
    assert result["metrics"]["non_black_pixels"] == 0
    assert result["metrics"]["bounding_box"] is None
    assert "local has_test_stop = false" in (fake.last_lua_code or "")


def test_capture_sfx_render_missing_file_cleanup_bytes_timeout_and_nonexistent(tmp_path):
    scene_dir = tmp_path / "fail_scene"
    scene_dir.mkdir()
    (scene_dir / "Test.sfc").write_bytes(b"\x00" * 32)

    captured_target = {}

    def no_screenshot_written(_cmd, lua_code):
        assert lua_code is not None
        captured_target["png"] = _extract_target_png(lua_code)
        return None

    res_no_png = capture_sfx_render(
        scene_test_name=str(scene_dir),
        runner=FakeMesenRunner(stdout="no save\n", on_run=no_screenshot_written),
    )
    assert res_no_png["status"] == "FAIL"
    assert res_no_png["png_path"] is None
    # Empty temp PNG must be cleaned up on failure
    assert not captured_target["png"].exists()

    def raise_bytes_timeout(cmd, _lua):
        raise subprocess.TimeoutExpired(
            cmd=cmd, timeout=4, output=b"hang stdout", stderr=b"hang stderr"
        )

    res_timeout = capture_sfx_render(
        scene_test_name=str(scene_dir),
        timeout_seconds=4,
        runner=FakeMesenRunner(on_run=raise_bytes_timeout),
    )
    assert res_timeout["status"] == "FAIL"
    assert "timed out" in res_timeout["error"].lower()
    assert "hang stdout" in res_timeout["output"]

    res_missing = capture_sfx_render(scene_test_name="nonexistent_scene_xyz")
    assert res_missing["status"] == "FAIL"
    assert res_missing["png_path"] is None
