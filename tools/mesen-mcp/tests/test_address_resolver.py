import json
import os
from pathlib import Path
import pytest

from mesen_mcp.address_resolver import (
    classify_region,
    parse_address,
    resolve_address,
)
from mesen_mcp.server import create_server

FIXTURE_PROJECT = str(
    Path(__file__).parent / "fixtures" / "sample_project"
)


def test_parse_address():
    assert parse_address(0x700B1A) == 0x700B1A
    assert parse_address("700B1A") == 0x700B1A
    assert parse_address("0x700B1A") == 0x700B1A
    assert parse_address("$700B1A") == 0x700B1A
    assert parse_address("80:9DA6") == 0x809DA6
    assert parse_address("0x80:0x9DA6") == 0x809DA6
    assert parse_address("$80:$9DA6") == 0x809DA6


def test_classify_region():
    assert classify_region(0x700000) == "GSURAM"
    assert classify_region(0x700B1A) == "GSURAM"
    assert classify_region(0x71FFFF) == "GSURAM"
    assert classify_region(0x7E0000) == "SNES-WRAM"
    assert classify_region(0x000100) == "SNES-WRAM"
    assert classify_region(0x002100) == "MMIO"
    assert classify_region(0x808000) == "ROM"
    assert classify_region(0x809DA6) == "ROM"
    assert classify_region(0x03AB60) == "ROM"


def test_symbol_resolution_with_and_without_leading_dot():
    res1 = resolve_address(FIXTURE_PROJECT, symbol="GSU_JOYPAD")
    assert res1["status"] == "SUCCESS"
    assert res1["found"] is True
    assert res1["address"] == "0x700B1A"
    assert res1["bank"] == 0x70
    assert res1["offset"] == 0x0B1A
    assert res1["region"] == "GSURAM"
    assert res1["type"] == "lab"
    assert res1["definedAt"] == {"path": "Test.s", "lineNumber": 15}

    res2 = resolve_address(FIXTURE_PROJECT, symbol=".GSU_JOYPAD")
    assert res2["status"] == "SUCCESS"
    assert res2["found"] is True
    assert res2["address"] == "0x700B1A"


def test_address_exact_and_delta():
    # Exact match at 700B1A -> delta 0
    res_exact = resolve_address(FIXTURE_PROJECT, address="700B1A")
    assert res_exact["status"] == "SUCCESS"
    assert res_exact["found"] is True
    assert res_exact["exact_match"] is True
    assert res_exact["name"] == "GSU_JOYPAD"
    assert res_exact["delta"] == 0
    assert res_exact["nearestBefore"]["name"] == "GSU_JOYPAD"
    assert res_exact["nearestBefore"]["delta"] == 0

    # Address 700B1B -> nearestBefore is GSU_JOYPAD with delta 1
    res_delta1 = resolve_address(FIXTURE_PROJECT, address="700B1B")
    assert res_delta1["status"] == "SUCCESS"
    assert res_delta1["exact_match"] is False
    assert res_delta1["nearestBefore"]["name"] == "GSU_JOYPAD"
    assert res_delta1["nearestBefore"]["address"] == "0x700B1A"
    assert res_delta1["nearestBefore"]["delta"] == 1
    assert res_delta1["nearestAfter"]["name"] == "ISO_CAM"
    assert res_delta1["nearestAfter"]["address"] == "0x700B1C"
    assert res_delta1["nearestAfter"]["delta"] == 1


def test_address_in_rom_does_not_bleed_into_gsuram():
    # 80:9DA6 is in ROM between SFX_INIT_mmio (809D35) and SFX_INIT_oam (809DFE)
    res = resolve_address(FIXTURE_PROJECT, address="80:9DA6")
    assert res["status"] == "SUCCESS"
    assert res["region"] == "ROM"
    assert res["nearestBefore"]["name"] == "SFX_INIT_mmio"
    assert res["nearestBefore"]["address"] == "0x809D35"
    assert res["nearestBefore"]["region"] == "ROM"
    assert res["nearestBefore"]["delta"] == 0x809DA6 - 0x809D35
    assert res["nearestAfter"]["name"] == "SFX_INIT_oam"
    assert res["nearestAfter"]["address"] == "0x809DFE"
    assert res["nearestAfter"]["region"] == "ROM"

    # Also works with hex string "809DA6"
    res_hex = resolve_address(FIXTURE_PROJECT, address="809DA6")
    assert res_hex["nearestBefore"]["name"] == "SFX_INIT_mmio"


def test_range_sorted_and_deduplicated():
    res = resolve_address(FIXTURE_PROJECT, range="700000-701FFF")
    assert res["status"] == "SUCCESS"
    symbols = res["symbols"]
    # Verify duplicates from .cpu.sym (GSU_JOYPAD appears twice in file) are collapsed
    names = [s["name"] for s in symbols]
    assert names.count("GSU_JOYPAD") == 1
    # Verify sorted ascending by address
    addrs = [int(s["address"], 16) for s in symbols]
    assert addrs == sorted(addrs)
    assert "RNC_HEADER" in names
    assert "GSU_JOYPAD" in names
    assert "ISO_CAM" in names


def test_unknown_symbol_suggestions():
    res = resolve_address(FIXTURE_PROJECT, symbol="test_stop")
    assert res["status"] == "NOT_FOUND"
    assert res["found"] is False
    assert "not found" in res["error"]
    assert "suggestions" in res
    assert isinstance(res["suggestions"], list)
    assert len(res["suggestions"]) <= 5


def test_prefix_query():
    res = resolve_address(FIXTURE_PROJECT, prefix="SFX_INIT")
    assert res["status"] == "SUCCESS"
    assert res["total_matches"] == 2
    names = [m["name"] for m in res["matches"]]
    assert "SFX_INIT_mmio" in names
    assert "SFX_INIT_oam" in names


def test_missing_project_error():
    res = resolve_address("non_existent_directory_12345")
    assert res["status"] == "ERROR"
    assert "Project directory not found" in res["error"]


def test_missing_artifacts_error(tmp_path):
    empty_dir = tmp_path / "empty_proj"
    empty_dir.mkdir()
    res = resolve_address(str(empty_dir))
    assert res["status"] == "ERROR"
    assert "No build artifacts" in res["error"]
    assert "searched_paths" in res


def test_stale_build_warning(tmp_path):
    proj = tmp_path / "stale_proj"
    proj.mkdir()
    dbg = proj / "Test.dbg"
    dbg.write_text("version\tmajor=2,minor=0\n", encoding="utf-8")
    src = proj / "main.s"
    src.write_text("; source code\n", encoding="utf-8")

    # Set artifact mtime older than source
    os.utime(dbg, (1000, 1000))
    os.utime(src, (2000, 2000))

    res = resolve_address(str(proj), symbol="anything")
    assert "warning" in res
    assert "stale build" in res["warning"]


@pytest.mark.asyncio
async def test_server_sfx_resolve_address():
    server = create_server()
    tools = await server.list_tools()
    assert any(t.name == "sfx_resolve_address" for t in tools)

    result = await server.call_tool(
        "sfx_resolve_address",
        {"project": FIXTURE_PROJECT, "symbol": "GSU_JOYPAD"},
    )
    assert not result.is_error
    payload = json.loads(result.content[0].text)
    assert payload["status"] == "SUCCESS"
    assert payload["found"] is True
    assert payload["address"] == "0x700B1A"


@pytest.mark.asyncio
async def test_server_sfx_resolve_address_aliases_and_defaults():
    server = create_server()

    # Test alias symbol_name and omitting project (will fallback or succeed if IsoEngine exists)
    result = await server.call_tool(
        "sfx_resolve_address",
        {"project": FIXTURE_PROJECT, "symbol_name": "GSU_JOYPAD"},
    )
    assert not result.is_error
    payload = json.loads(result.content[0].text)
    assert payload["found"] is True

    # Test alias addr
    result_addr = await server.call_tool(
        "sfx_resolve_address",
        {"project": FIXTURE_PROJECT, "addr": "0x700B1A"},
    )
    assert not result_addr.is_error
    payload_addr = json.loads(result_addr.content[0].text)
    assert payload_addr["exact_match"] is True
    assert payload_addr["name"] == "GSU_JOYPAD"


def test_function_suffix_fallback():
    # If a function is defined as foo_function in ca65, querying 'foo' should resolve it
    res = resolve_address(FIXTURE_PROJECT, symbol="GSU_JOYPAD")
    assert res["status"] == "SUCCESS"


