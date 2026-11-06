#!/usr/bin/env python3
"""Extract symbol offsets and check word alignment from Test.cpu.sym."""

import argparse
import re
import sys
from pathlib import Path


OUTPUT_ALIASES = (
    "TEST_OUTPUT",
    "test_output",
    "VECTOR_SUBTRACT_OUT",
    "VECTOR_ADD_OUT",
    "VECTOR_NEGATE_OUT",
    "VECTOR_CROSS_OUT",
    "VECTOR_TRANSFORM_OUT",
    "vector_normalize_out",
)


def parse_sym_file(sym_path: Path):
    symbols = {}
    pattern = re.compile(r"^al\s+([0-9A-Fa-f]{6})\s+\.([A-Za-z0-9_@]+)")

    with open(sym_path, "r", encoding="utf-8") as f:
        for line in f:
            match = pattern.match(line.strip())
            if match:
                full_hex, name = match.groups()
                full_val = int(full_hex, 16)
                offset = full_val & 0xFFFF
                bank = (full_val >> 16) & 0xFF
                symbols[name] = {"full": full_val, "offset": offset, "bank": bank}
    return symbols


def lookup_symbol(symbols: dict, name: str):
    if name in symbols:
        return symbols[name]
    if name.upper() in symbols:
        return symbols[name.upper()]
    if name.lower() in symbols:
        return symbols[name.lower()]
    name_upper = name.upper()
    for k, v in symbols.items():
        if k.upper() == name_upper:
            return v
    if name_upper == "OUTPUT":
        for cand in OUTPUT_ALIASES:
            if cand in symbols:
                return symbols[cand]
            for k, v in symbols.items():
                if k.upper() == cand.upper():
                    return v
    return None


def main():
    parser = argparse.ArgumentParser(description="Extract symbol offsets from Test.cpu.sym")
    parser.add_argument("sym_file", help="Path to Test.cpu.sym")
    parser.add_argument(
        "--update-lua",
        help="Optional path to test.lua to automatically update symbol addresses",
    )
    args = parser.parse_args()

    sym_path = Path(args.sym_file)
    if not sym_path.exists():
        print(f"Error: Symbol file not found: {sym_path}", file=sys.stderr)
        sys.exit(1)

    symbols = parse_sym_file(sym_path)

    key_syms = ["test_setup", "test_start", "test_stop", "INPUT", "OUTPUT"]
    symbols_to_display = list(key_syms)

    var_pattern = re.compile(
        r"^(\s*(?:local\s+)?([A-Za-z0-9_]+)\s*=\s*)(0x[0-9A-Fa-f]+|\d+)(.*)$"
    )

    lua_lines = None
    lua_path = None
    if args.update_lua:
        lua_path = Path(args.update_lua)
        if not lua_path.exists():
            print(f"Error: Lua file not found: {lua_path}", file=sys.stderr)
            sys.exit(1)
        lua_lines = lua_path.read_text(encoding="utf-8").splitlines()
        for line in lua_lines:
            m = var_pattern.match(line)
            if m:
                var_name = m.group(2)
                if var_name.lower() in ("index", "fail_count", "i", "j", "k"):
                    continue
                if lookup_symbol(symbols, var_name) is not None:
                    if var_name not in symbols_to_display:
                        symbols_to_display.append(var_name)

    print(f"Symbols from {sym_path.name}:")
    print("-" * 50)
    for sym in symbols_to_display:
        info = lookup_symbol(symbols, sym)
        if info:
            offset = info["offset"]
            bank = info["bank"]
            aligned = (offset % 2 == 0)
            align_str = "word-aligned" if aligned else "WARNING: UNALIGNED (ODD ADDRESS)"
            print(f"  {sym:16} : 0x{offset:04X}  (Bank ${bank:02X}, {align_str})")
        elif sym in key_syms:
            print(f"  {sym:16} : NOT FOUND")

    print("-" * 50)

    if lua_path and lua_lines is not None:
        updated_lines = []
        for line in lua_lines:
            m = var_pattern.match(line)
            if m:
                prefix, var_name, current_val_str, suffix = m.groups()
                if var_name.lower() in ("index", "fail_count", "i", "j", "k"):
                    updated_lines.append(line)
                    continue
                info = lookup_symbol(symbols, var_name)
                if info is not None:
                    offset = info["offset"]
                    updated_lines.append(f"{prefix}0x{offset:04X}{suffix}")
                else:
                    updated_lines.append(line)
            else:
                updated_lines.append(line)

        lua_path.write_text("\n".join(updated_lines) + "\n", encoding="utf-8")
        print(f"Updated {lua_path} with extracted symbol offsets.")


if __name__ == "__main__":
    main()
