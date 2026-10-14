from pathlib import Path
import pytest
from mesen_mcp.runner import resolve_test_dir, run_sfx_test


def test_resolve_test_dir():
    sqrt_dir = resolve_test_dir("sqrt")
    assert sqrt_dir.is_dir()
    assert (sqrt_dir / "Test.s").exists()

    with pytest.raises(FileNotFoundError):
        resolve_test_dir("nonexistent_test_12345")


def test_run_sfx_test_sqrt():
    result = run_sfx_test("sqrt", timeout_seconds=15)
    assert result["status"] == "PASS"
    assert result["test"] == "sqrt"
    assert "TEST PASSED" in result["output"]
    assert result["symbols"]["test_stop"] == 0x0020


def test_run_sfx_test_transform():
    result = run_sfx_test("transform", timeout_seconds=15)
    assert result["status"] == "PASS"
    assert result["test"] == "transform"
    assert "TEST PASSED" in result["output"]

