import json
import os
from pathlib import Path
import pytest

from mesen_mcp import BuildArtifactIndex
from mesen_mcp.address_resolver import (
    classify_region,
    parse_address,
    resolve_address,
)
import mesen_mcp.artifacts as artifacts_mod
from mesen_mcp.server import create_server

FIXTURE_PROJECT = str(Path(__file__).parent / "fixtures" / "sample_project")


MULTI_BANK_DBG = """\
version\tmajor=2,minor=0
file\tid=0,name="SuperFX.sgs",size=1200,mtime=0x6ACEE723,mod=0
seg\tid=0,name="CODE",start=0x808000,size=0x2000,addrsize=absolute,type=ro
seg\tid=1,name="GSUCODE",start=0x038000,size=0x0200,addrsize=absolute,type=ro
seg\tid=2,name="RODATA",start=0x048000,size=0x4000,addrsize=absolute,type=ro
seg\tid=3,name="GSURAM",start=0x700000,size=0x2000,addrsize=absolute,type=rw
seg\tid=4,name="EXPRAM",start=0x710000,size=0x10000,addrsize=absolute,type=rw
seg\tid=5,name="BSS",start=0x7E2000,size=0x0400,addrsize=absolute,type=rw
scope\tid=0,name="",mod=0,size=512
scope\tid=1,name="draw_cached_trap",mod=0,size=96,parent=0
span\tid=0,seg=1,start=0x0030,size=16,type=0
line\tid=10,file=0,line=88,span=0
sym\tid=0,name="Main",addrsize=absolute,scope=0,def=10,val=0x808010,seg=0,type=lab
sym\tid=1,name="GSU_Code",addrsize=absolute,scope=0,def=10,val=0x038000,seg=1,type=lab
sym\tid=2,name="draw_cached_trap_function",addrsize=absolute,scope=0,def=10,val=0x038030,seg=1,type=lab
sym\tid=3,name="@scanline_loop",addrsize=absolute,scope=1,def=10,val=0x038040,seg=1,type=lab
sym\tid=4,name="@trap_done",addrsize=absolute,scope=1,def=10,val=0x038090,seg=1,type=lab
sym\tid=5,name="SHOWCASE_MAP_ROM",addrsize=absolute,scope=0,def=10,val=0x048030,seg=2,type=lab
sym\tid=6,name="INPUT_VEC",addrsize=absolute,scope=0,def=10,val=0x700010,seg=3,type=lab
sym\tid=7,name="VECTOR_ADD_OUT",addrsize=absolute,scope=0,def=10,val=0x700014,seg=3,type=lab
sym\tid=8,name="STAGED_ATLAS",addrsize=absolute,scope=0,def=10,val=0x710000,seg=4,type=lab
sym\tid=9,name="STAGED_TAB",addrsize=absolute,scope=0,def=10,val=0x718000,seg=4,type=lab
sym\tid=10,name="STAGED_MAP",addrsize=absolute,scope=0,def=10,val=0x718004,seg=4,type=lab
sym\tid=11,name="HOST_SHADOW",addrsize=absolute,scope=0,def=10,val=0x7E2000,seg=5,type=lab
"""


def test_parse_address():
    assert parse_address(0x700B1A) == 0x700B1A
    assert parse_address("700B1A") == 0x700B1A
    assert parse_address("0x700B1A") == 0x700B1A
    assert parse_address("$700B1A") == 0x700B1A
    assert parse_address("80:9DA6") == 0x809DA6
    assert parse_address("0x80:0x9DA6") == 0x809DA6
    assert parse_address("$71:$8000") == 0x718000


def test_classify_region():
    assert classify_region(0x700000) == "GSURAM"
    assert classify_region(0x700B1A) == "GSURAM"
    assert classify_region(0x718000) == "GSURAM"
    assert classify_region(0x71FFFF) == "GSURAM"
    assert classify_region(0x7E0000) == "SNES-WRAM"
    assert classify_region(0x000100) == "SNES-WRAM"
    assert classify_region(0x002100) == "MMIO"
    assert classify_region(0x808000) == "ROM"
    assert classify_region(0x048000) == "ROM"
    assert classify_region(0x03AB60) == "ROM"


def test_build_artifact_index_from_strings_24bit_and_gsu_targets():
    cpu_sym_extra = """\
    al 718000 .STAGED_TAB
    al 700020 TEST_CUSTOM_FLAG
    al 000100 .ZP_TEMP
    al 002100 .INIDISP_MMIO
    """
    idx = BuildArtifactIndex.from_strings(
        dbg_text=MULTI_BANK_DBG, cpu_sym_text=cpu_sym_extra
    )

    # .cpu.sym augments .dbg without clobbering .dbg metadata
    staged = idx.resolve_symbol("STAGED_TAB")
    assert staged is not None
    assert staged.val == 0x718000
    assert staged.defined_at == {"path": "SuperFX.sgs", "lineNumber": 88}
    assert staged.size == 4

    custom = idx.resolve_symbol(".TEST_CUSTOM_FLAG")
    assert custom is not None
    assert custom.val == 0x700020

    # Unified matching: OUTPUT alias, _function suffix, scope:: stripping, @local after_addr
    out_sym = idx.resolve_symbol("OUTPUT")
    assert out_sym is not None and out_sym.name == "VECTOR_ADD_OUT"

    fn_sym = idx.resolve_symbol("iso::draw_cached_trap")
    assert fn_sym is not None and fn_sym.name == "draw_cached_trap_function"

    local_sym = idx.resolve_symbol(
        "@scanline_loop", after_addr=0x038030, prefer_region="ROM"
    )
    assert local_sym is not None and local_sym.val == 0x038040

    # Decision 7.1: Bank $70 vs Bank $71 linear gsuWorkRam addressing
    t_reg = idx.resolve_gsu_target("R12")
    assert t_reg.is_register and t_reg.register == "r12"

    t_b70 = idx.resolve_gsu_target(".INPUT_VEC")
    assert t_b70.work_ram_addr == 0x0010
    assert t_b70.size == 4

    t_b71 = idx.resolve_gsu_target("STAGED_TAB")
    assert t_b71.full_address == 0x718000
    assert t_b71.work_ram_addr == 0x18000
    assert t_b71.size == 4

    t_b71_off = idx.resolve_gsu_target("STAGED_TAB + 0x08")
    assert t_b71_off.work_ram_addr == 0x18008
    assert t_b71_off.has_explicit_offset is True

    # Decision 7.1: Reject ROM, SNES-WRAM, LowWRAM, MMIO, and invalid registers
    for bad_key in (
        "SHOWCASE_MAP_ROM",
        "draw_cached_trap_function",
        "Main",
        "HOST_SHADOW",
        "ZP_TEMP",
        "INIDISP_MMIO",
        "r16",
    ):
        with pytest.raises(ValueError):
            idx.resolve_gsu_target(bad_key)


def test_pc_hotspot_spans_exclude_rom_data_and_65816_in_both_dbg_and_cpu_sym():
    # 1. With .dbg GSUCODE segment: SHOWCASE_MAP_ROM is also at offset 0x8030 in Bank $04,
    #    Main is at 0x8010 in Bank $80, STAGED_TAB is at 0x8000 in Bank $71 — none may pollute GSU PC!
    idx_dbg = BuildArtifactIndex.from_strings(dbg_text=MULTI_BANK_DBG)
    assert idx_dbg.resolve_function(0x8004) == "GSU_Code"
    assert idx_dbg.resolve_function(0x8010) == "GSU_Code"
    assert idx_dbg.resolve_function(0x8034) == "draw_cached_trap"
    assert idx_dbg.resolve_label(0x8044) == ("@scanline_loop", "draw_cached_trap")

    # 2. Pure .cpu.sym fallback (no .dbg): still isolates GSU code bank $03 from $04/$70/$71/$80!
    cpu_sym_only = """\
al 038000 .GSU_Code
al 038030 .draw_cached_trap_function
al 038040 .@scanline_loop
al 048030 .SHOWCASE_MAP_ROM
al 700010 .INPUT_VEC
al 700014 .OUTPUT_VEC
al 718000 .STAGED_TAB
al 718004 .STAGED_MAP
al 808010 .Main
"""
    idx_sym = BuildArtifactIndex.from_strings(cpu_sym_text=cpu_sym_only)
    assert idx_sym.resolve_function(0x8010) == "GSU_Code"
    assert idx_sym.resolve_function(0x8035) == "draw_cached_trap"
    assert idx_sym.resolve_label(0x8042) == ("@scanline_loop", "draw_cached_trap")
    # Per-region-bank size estimation in .cpu.sym-only mode (and no $70 -> $71 cross-bank inflation!)
    in_sym = idx_sym.resolve_symbol("INPUT_VEC")
    out_sym = idx_sym.resolve_symbol("OUTPUT_VEC")
    tab_sym = idx_sym.resolve_symbol("STAGED_TAB")
    assert in_sym is not None and in_sym.size == 4
    assert out_sym is not None and out_sym.size is None
    assert tab_sym is not None and tab_sym.size == 4


def test_single_pass_dbg_retention_on_address_query(tmp_path, monkeypatch):
    proj = tmp_path / "single_pass_proj"
    proj.mkdir()
    (proj / "Test.dbg").write_text(MULTI_BANK_DBG, encoding="utf-8")
    (proj / "SuperFX.sgs").write_text(
        "\n".join(f"; line {i}" for i in range(1, 95)) + "\n", encoding="utf-8"
    )

    parse_calls = 0
    real_parse = artifacts_mod.parse_dbg_string

    def counted_parse(content: str):
        nonlocal parse_calls
        parse_calls += 1
        return real_parse(content)

    monkeypatch.setattr(artifacts_mod, "parse_dbg_string", counted_parse)

    res = resolve_address(str(proj), address="0x038034")
    assert res["status"] == "SUCCESS"
    assert parse_calls == 1
    assert res["sourceLocation"]["path"] == "SuperFX.sgs"
    assert res["sourceLocation"]["lineNumber"] == 88
    assert "==>    88 |" in res["sourceLocation"]["context"]


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
    assert res1["next_global_symbol"] == "ISO_CAM"
    assert res1["span_to_next_global"] == 2

    res2 = resolve_address(FIXTURE_PROJECT, symbol=".GSU_JOYPAD")
    assert res2["status"] == "SUCCESS"
    assert res2["address"] == "0x700B1A"


def test_address_exact_delta_and_rom_isolation():
    res_exact = resolve_address(FIXTURE_PROJECT, address="700B1A")
    assert res_exact["status"] == "SUCCESS"
    assert res_exact["exact_match"] is True
    assert res_exact["name"] == "GSU_JOYPAD"
    assert res_exact["delta"] == 0

    res_delta1 = resolve_address(FIXTURE_PROJECT, address="700B1B")
    assert res_delta1["exact_match"] is False
    assert res_delta1["nearestBefore"]["name"] == "GSU_JOYPAD"
    assert res_delta1["nearestBefore"]["delta"] == 1
    assert res_delta1["nearestAfter"]["name"] == "ISO_CAM"
    assert res_delta1["nearestAfter"]["delta"] == 1

    res_rom = resolve_address(FIXTURE_PROJECT, address="80:9DA6")
    assert res_rom["region"] == "ROM"
    assert res_rom["nearestBefore"]["name"] == "SFX_INIT_mmio"
    assert res_rom["nearestAfter"]["name"] == "SFX_INIT_oam"


def test_range_prefix_and_suggestions():
    res_range = resolve_address(FIXTURE_PROJECT, range="700000-701FFF")
    names = [s["name"] for s in res_range["symbols"]]
    assert names.count("GSU_JOYPAD") == 1
    addrs = [int(s["address"], 16) for s in res_range["symbols"]]
    assert addrs == sorted(addrs)

    res_prefix = resolve_address(FIXTURE_PROJECT, prefix="SFX_INIT")
    assert res_prefix["total_matches"] == 2

    res_miss = resolve_address(FIXTURE_PROJECT, symbol="test_stop")
    assert res_miss["status"] == "NOT_FOUND"
    assert res_miss["found"] is False
    assert isinstance(res_miss["suggestions"], list)


def test_end_symbol_and_16bit_end_address_bank_inheritance():
    # SFX_INIT_mmio is at 0x809D35 (bank 0x80), SFX_INIT_oam is at 0x809DFE
    res = resolve_address(
        FIXTURE_PROJECT,
        symbol="SFX_INIT_mmio",
        end_symbol="SFX_INIT_oam",
    )
    assert res["status"] == "SUCCESS"
    assert res["end_address"] == "0x809DFE"
    assert res["byte_span"] == 0x809DFE - 0x809D35
    assert res["fits_in_512b_cache"] is True

    # 16-bit end_address ("0x9DFE") must inherit matched_sym.bank (0x80) instead of underflowing to 0x009DFE!
    res_16bit_end = resolve_address(
        FIXTURE_PROJECT,
        symbol="SFX_INIT_mmio",
        end_address="0x9DFE",
    )
    assert res_16bit_end["status"] == "SUCCESS"
    assert res_16bit_end["end_address"] == "0x809DFE"
    assert res_16bit_end["byte_span"] == 0x809DFE - 0x809D35
    assert res_16bit_end["fits_in_512b_cache"] is True

    # Explicit 24-bit end_address continues to work
    res_24bit_end = resolve_address(
        FIXTURE_PROJECT,
        symbol="SFX_INIT_mmio",
        end_address="0x80A000",
    )
    assert res_24bit_end["byte_span"] == 0x80A000 - 0x809D35
    assert res_24bit_end["fits_in_512b_cache"] is False


def test_missing_project_missing_artifacts_and_stale_build_warning(tmp_path):
    res_missing = resolve_address("non_existent_directory_12345")
    assert res_missing["status"] == "ERROR"

    empty_dir = tmp_path / "empty_proj"
    empty_dir.mkdir()
    res_empty = resolve_address(str(empty_dir))
    assert res_empty["status"] == "ERROR"
    assert "searched_paths" in res_empty

    proj = tmp_path / "stale_proj"
    proj.mkdir()
    dbg = proj / "Test.dbg"
    dbg.write_text("version\tmajor=2,minor=0\n", encoding="utf-8")
    src = proj / "main.s"
    src.write_text("; source code\n", encoding="utf-8")
    os.utime(dbg, (1000, 1000))
    os.utime(src, (2000, 2000))

    res_stale = resolve_address(str(proj), symbol="anything")
    assert "stale build" in res_stale["warning"]
    assert res_stale["stale_build_warning"] == res_stale["warning"]


@pytest.mark.asyncio
async def test_server_sfx_resolve_address_and_sticky_project():
    server = create_server()
    result = await server.call_tool(
        "sfx_resolve_address",
        {"project": FIXTURE_PROJECT, "symbol_name": "GSU_JOYPAD"},
    )
    assert not result.is_error
    payload = json.loads(result.content[0].text)
    assert payload["found"] is True
    assert payload["address"] == "0x700B1A"

    # Sticky _LAST_PROJECT when project is omitted on follow-up call
    result_sticky = await server.call_tool(
        "sfx_resolve_address",
        {"addr": "0x700B1A"},
    )
    payload_sticky = json.loads(result_sticky.content[0].text)
    assert payload_sticky["exact_match"] is True
    assert payload_sticky["name"] == "GSU_JOYPAD"
