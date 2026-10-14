import pytest
from mesen_mcp.math_verifier import assert_sfx_math


def test_assert_sfx_math_sqrt_pass():
    # 100 -> sqrt is 10
    result = assert_sfx_math(
        test_name="sqrt",
        input_overrides={"INPUT": 100},
        expected_outputs={"OUTPUT": 10},
        epsilon=0,
        timeout_seconds=15,
    )
    assert result["status"] == "PASS"
    assert result["test"] == "sqrt"
    assert result["results"]["OUTPUT"] == 10
    assert result["diffs"]["OUTPUT"] == 0


def test_assert_sfx_math_sqrt_32bit():
    # 65536 (0x00010000) -> sqrt is 256
    result = assert_sfx_math(
        test_name="sqrt",
        input_overrides={"INPUT": 65536},
        expected_outputs={"OUTPUT": 256},
        epsilon=0,
        timeout_seconds=15,
    )
    assert result["status"] == "PASS"
    assert result["test"] == "sqrt"
    assert result["results"]["OUTPUT"] == 256
    assert result["diffs"]["OUTPUT"] == 0


def test_assert_sfx_math_sqrt_fail():
    # Expected output is wrong: 100 -> expected 999
    result = assert_sfx_math(
        test_name="sqrt",
        input_overrides={"INPUT": 100},
        expected_outputs={"OUTPUT": 999},
        epsilon=0,
        timeout_seconds=15,
    )
    assert result["status"] == "FAIL"
    assert result["test"] == "sqrt"
    assert result["results"]["OUTPUT"] == 10
    assert result["diffs"]["OUTPUT"] == 989


def test_assert_sfx_math_epsilon():
    # 100 -> actual 10, expected 12, within epsilon 2
    result = assert_sfx_math(
        test_name="sqrt",
        input_overrides={"INPUT": 100},
        expected_outputs={"OUTPUT": 12},
        epsilon=2,
        timeout_seconds=15,
    )
    assert result["status"] == "PASS"
    assert result["results"]["OUTPUT"] == 10
    assert result["diffs"]["OUTPUT"] == 2


def test_assert_sfx_math_register_override():
    # Write to r5 during test_setup, verify it persists at test_stop
    result = assert_sfx_math(
        test_name="sqrt",
        input_overrides={"r5": 0x1234},
        expected_outputs={"r5": 0x1234},
        epsilon=0,
        timeout_seconds=15,
    )
    assert result["status"] == "PASS"
    assert result["results"]["r5"] == 0x1234
    assert result["diffs"]["r5"] == 0


def test_assert_sfx_math_nonexistent_test():
    result = assert_sfx_math(
        test_name="nonexistent_test_xyz",
        input_overrides={"INPUT": 1},
        expected_outputs={"OUTPUT": 1},
    )
    assert result["status"] == "FAIL"
    assert "not found" in result["error"].lower()
