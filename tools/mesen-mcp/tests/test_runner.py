from pathlib import Path
import pytest
from mesen_mcp.runner import resolve_test_dir, run_sfx_test


def test_resolve_test_dir():
    sqrt_dir = resolve_test_dir("sqrt")
    assert sqrt_dir.is_dir()
    assert (sqrt_dir / "Test.s").exists()

    iso_dir = resolve_test_dir("IsoEngine")
    assert iso_dir.is_dir()
    assert (iso_dir / "SuperFX.s").exists()

    with pytest.raises(FileNotFoundError):
        resolve_test_dir("nonexistent_test_12345")


def test_run_sfx_test_sqrt():
    result = run_sfx_test("sqrt", timeout_seconds=15)
    assert result["status"] == "PASS"
    assert result["test"] == "sqrt"
    assert "TEST PASSED" in result["output"]
    assert "symbols" not in result


def test_run_sfx_test_transform():
    result = run_sfx_test("transform", timeout_seconds=15)
    assert result["status"] == "PASS"
    assert result["test"] == "transform"
    assert "TEST PASSED" in result["output"]


def test_run_sfx_test_skips_wip_and_template_headers():
    wip_res = run_sfx_test("buffer_rnc", timeout_seconds=5)
    assert wip_res["status"] == "SKIP"
    assert wip_res["test_status"] == "wip"
    assert "RNC" in wip_res["reason"]

    tpl_res = run_sfx_test("test_template", timeout_seconds=5)
    assert tpl_res["status"] == "SKIP"
    assert tpl_res["test_status"] == "template"


def test_run_sfx_test_all_suite():
    res = run_sfx_test("all", timeout_seconds=15, skip_compile=True)
    assert res["status"] == "PASS"
    assert res["test"] == "all"
    assert res["total"] >= 27
    assert res["passed"] >= 24
    assert res["failed"] == 0
    assert res["skipped"] >= 3
    assert "SuperFX Test Suite Summary" in res["output"]

