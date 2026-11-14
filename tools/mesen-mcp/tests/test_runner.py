import subprocess
import pytest

from mesen_mcp import FakeMesenRunner, assert_sfx_math, profile_sfx_execution, run_sfx_test
from mesen_mcp.mesen import find_mesen
from mesen_mcp.runner import resolve_test_dir, run_sfx_suite


def test_resolve_test_dir():
    sqrt_dir = resolve_test_dir("sqrt")
    assert sqrt_dir.is_dir()
    assert (sqrt_dir / "Test.s").exists()

    iso_dir = resolve_test_dir("IsoEngine")
    assert iso_dir.is_dir()
    assert (iso_dir / "SuperFX.s").exists()

    with pytest.raises(FileNotFoundError):
        resolve_test_dir("nonexistent_test_12345")


def test_run_sfx_test_ephemeral_sync_never_mutates_tracked_test_lua(tmp_path):
    test_dir = tmp_path / "unit_vec"
    test_dir.mkdir()
    (test_dir / "Test.sfc").write_bytes(b"\x00" * 64)
    (test_dir / "Test.cpu.sym").write_text(
        "al 700003 .test_setup\n"
        "al 70002C .test_stop\n"
        "al 700EBC .INPUT\n"
        "al 700EDE .VECTOR_ADD_OUT\n"
        "al 718000 .STAGED_BUF\n",
        encoding="utf-8",
    )

    original_lua = (
        "local test_setup = 0x0000\n"
        "local test_stop  = 0x0000\n"
        "local INPUT      = 0x0000\n"
        "local OUTPUT     = 0x0000\n"
        "local STAGED_BUF = 0x0000\n"
        "local index      = 1\n"
        "local fail_count = 0\n"
        'print("TEST PASSED")\n'
    )
    lua_file = test_dir / "test.lua"
    lua_file.write_text(original_lua, encoding="utf-8")

    fake = FakeMesenRunner(stdout="TEST PASSED: all vectors ok\n", returncode=0)
    result = run_sfx_test(str(test_dir), runner=fake)

    assert result["status"] == "PASS"
    assert "TEST PASSED" in result["output"]
    # Contract: "symbols" must NOT be present in run_sfx_test output
    assert "symbols" not in result

    # Tracked test.lua on disk MUST remain completely untouched!
    assert lua_file.read_text(encoding="utf-8") == original_lua

    # Ephemeral Lua passed to Mesen MUST contain synchronized Bank $70 and Bank $71 offsets
    synced = fake.last_lua_code
    assert synced is not None
    assert "local test_setup = 0x0003" in synced
    assert "local test_stop  = 0x002C" in synced
    assert "local INPUT      = 0x0EBC" in synced
    assert "local OUTPUT     = 0x0EDE" in synced
    assert "local STAGED_BUF = 0x18000" in synced
    assert "local index      = 1" in synced
    assert "local fail_count = 0" in synced


def test_run_sfx_test_explicit_fail_bytes_timeout_and_suite_headers(tmp_path):
    suite_root = tmp_path / "suite"
    suite_root.mkdir()

    # 1. Active passing test
    t_pass = suite_root / "alpha_pass"
    t_pass.mkdir()
    (t_pass / "Test.sfc").write_bytes(b"\x00" * 16)
    (t_pass / "test.lua").write_text('print("ok")\n', encoding="utf-8")

    # 2. WIP test -> SKIP
    t_wip = suite_root / "beta_wip"
    t_wip.mkdir()
    (t_wip / "test.lua").write_text(
        '-- @sfx-test status=wip reason="RNC pending"\n', encoding="utf-8"
    )

    # 3. Template -> omitted from suite total
    t_tpl = suite_root / "test_template"
    t_tpl.mkdir()
    (t_tpl / "test.lua").write_text(
        "-- @sfx-test status=template\n", encoding="utf-8"
    )

    assert run_sfx_test(str(t_wip))["status"] == "SKIP"
    assert run_sfx_test(str(t_tpl))["status"] == "SKIP"

    suite_res = run_sfx_suite(
        runner=FakeMesenRunner(stdout="TEST PASSED\n", returncode=0),
        tests_root=suite_root,
    )
    assert suite_res["status"] == "PASS"
    assert suite_res["total"] == 2
    assert suite_res["passed"] == 1
    assert suite_res["failed"] == 0
    assert suite_res["skipped"] == 1
    assert "SuperFX Test Suite Summary" in suite_res["output"]

    # Explicit "TEST FAILED" in stdout fails even if returncode == 0
    fail_res = run_sfx_test(
        str(t_pass),
        runner=FakeMesenRunner(stdout="TEST FAILED: mismatch\n", returncode=0),
    )
    assert fail_res["status"] == "FAIL"

    # TimeoutExpired with bytes stdout/stderr
    def raise_bytes_timeout(cmd, _lua):
        raise subprocess.TimeoutExpired(
            cmd=cmd, timeout=2, output=b"stalled in loop", stderr=b"err"
        )

    timeout_res = run_sfx_test(
        str(t_pass),
        timeout_seconds=2,
        runner=FakeMesenRunner(on_run=raise_bytes_timeout),
    )
    assert timeout_res["status"] == "FAIL"
    assert timeout_res["exit_code"] == -1
    assert "stalled in loop" in timeout_res["output"]


@pytest.mark.emulator
def test_real_mesen_sqrt_smoke():
    """Optional real-Mesen smoke test (gated behind `-m emulator`; never calls `make clean`)."""
    try:
        find_mesen()
    except FileNotFoundError:
        pytest.skip("Mesen executable not installed")

    math_res = assert_sfx_math(
        test_name="sqrt",
        input_overrides={"INPUT": 100},
        expected_outputs={"OUTPUT": 10},
        epsilon=0,
        timeout_seconds=15,
    )
    assert math_res["status"] == "PASS"
    assert math_res["results"]["OUTPUT"] == 10

    prof_res = profile_sfx_execution(
        target_name="sqrt",
        inputs={"INPUT": 100},
        max_cycles=5000,
        timeout_seconds=15,
    )
    assert prof_res["status"] == "PASS"
    assert prof_res["summary"]["total_cycles"] > 0
