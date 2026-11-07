"""Symbol and address resolver for SuperFX ROM build artifacts (.dbg and .cpu.sym)."""

from dataclasses import dataclass
import difflib
import os
from pathlib import Path
import re
from typing import Any, Dict, List, Optional, Set, Tuple, Union

from mesen_mcp.dbg_parser import DbgData, Symbol, parse_dbg_file
from mesen_mcp.utils import resolve_test_dir


@dataclass
class ResolvedSymbol:
    name: str
    val: int
    bank: int
    offset: int
    region: str
    type: str = "lab"  # "lab", "equ", "imp"
    size: Optional[int] = None
    defined_at: Optional[Dict[str, Any]] = None
    scope: Optional[str] = None


def parse_address(val: Union[str, int]) -> int:
    """Parse address from integer or hex/bank:offset string."""
    if isinstance(val, int):
        return val
    s = str(val).strip()
    if not s:
        raise ValueError("Empty address string")

    # Check bank:offset format (e.g. "80:9DA6", "0x80:0x9DA6", "$80:$9DA6")
    if ":" in s:
        parts = s.split(":", 1)
        bank = parse_address(parts[0])
        offset = parse_address(parts[1])
        return ((bank & 0xFF) << 16) | (offset & 0xFFFF)

    if s.startswith("$"):
        s = s[1:]
    elif s.startswith(("0x", "0X")):
        s = s[2:]

    return int(s, 16)


def classify_region(addr: int) -> str:
    """Classify 24-bit SNES address into memory region."""
    bank = (addr >> 16) & 0xFF
    offset = addr & 0xFFFF

    # GSURAM / Cartridge SRAM: Banks $70-$77 ($700000-$71FFFF typical)
    if 0x70 <= bank <= 0x77:
        return "GSURAM"

    # SNES Work RAM (WRAM): Banks $7E-$7F
    if 0x7E <= bank <= 0x7F:
        return "SNES-WRAM"

    # Banks $00-$3F and $80-$BF
    if (0x00 <= bank <= 0x3F) or (0x80 <= bank <= 0xBF):
        if offset < 0x2000:
            return "SNES-WRAM"
        elif 0x2000 <= offset < 0x6000:
            return "MMIO"
        elif offset >= 0x8000:
            return "ROM"
        else:
            return "EXPANSION"

    # Banks $40-$6F and $C0-$FF
    if offset >= 0x8000 or (0xC0 <= bank <= 0xFF):
        return "ROM"

    return "OTHER"


def _find_build_artifacts(
    project_dir: Path,
) -> Tuple[Optional[Path], Optional[Path], List[Path]]:
    """Find .dbg and .cpu.sym artifacts following run-sfx-test.sh priority order."""
    searched: List[Path] = []
    dbg_file: Optional[Path] = None
    sym_file: Optional[Path] = None

    candidate_stems = ["Test", "SuperFX", project_dir.name]

    # Search for .dbg
    for stem in candidate_stems:
        p = project_dir / f"{stem}.dbg"
        searched.append(p)
        if p.is_file():
            dbg_file = p
            break
    if not dbg_file:
        for p in sorted(project_dir.glob("*.dbg")):
            searched.append(p)
            if p.is_file():
                dbg_file = p
                break

    # Search for .cpu.sym
    for stem in candidate_stems:
        p = project_dir / f"{stem}.cpu.sym"
        searched.append(p)
        if p.is_file():
            sym_file = p
            break
    if not sym_file:
        for p in sorted(project_dir.glob("*.cpu.sym")):
            searched.append(p)
            if p.is_file():
                sym_file = p
                break

    return dbg_file, sym_file, searched


def _check_stale_build(
    project_dir: Path, artifact_file: Path
) -> Optional[str]:
    """Check if the build artifact is older than the newest source file."""
    src_extensions = {".s", ".i", ".sgs", ".asm", ".inc", ".c", ".h"}
    newest_mtime = 0.0
    newest_src: Optional[Path] = None

    for root, _, files in os.walk(project_dir):
        # Skip .git and build artifact directories
        if ".git" in root or ".build" in root:
            continue
        for f in files:
            p = Path(root) / f
            if p.suffix.lower() in src_extensions:
                try:
                    mtime = p.stat().st_mtime
                    if mtime > newest_mtime:
                        newest_mtime = mtime
                        newest_src = p
                except OSError:
                    pass

    if newest_src and artifact_file.is_file():
        try:
            artifact_mtime = artifact_file.stat().st_mtime
            if artifact_mtime < newest_mtime:
                return (
                    f"Build artifact '{artifact_file.name}' is older than source file "
                    f"'{newest_src.name}' (stale build; recompile with make or run-sfx-test.sh)"
                )
        except OSError:
            pass

    return None


def _parse_cpu_sym(path: Path) -> List[Tuple[str, int]]:
    """Parse .cpu.sym file into a list of (symbol_name, address)."""
    results: List[Tuple[str, int]] = []
    pattern = re.compile(r"^\s*al\s+([0-9A-Fa-f]{6})\s+\.?([A-Za-z0-9_@]+)")
    try:
        content = path.read_text(encoding="utf-8", errors="replace")
        for line in content.splitlines():
            m = pattern.match(line.strip())
            if m:
                addr = int(m.group(1), 16)
                name = m.group(2)
                results.append((name, addr))
    except Exception:
        pass
    return results


def _build_symbol_database(
    dbg_file: Optional[Path], sym_file: Optional[Path]
) -> List[ResolvedSymbol]:
    """Build unified list of ResolvedSymbols from .dbg and .cpu.sym."""
    symbols: List[ResolvedSymbol] = []
    seen_keys: Set[Tuple[str, int]] = set()

    dbg_data: Optional[DbgData] = None
    if dbg_file and dbg_file.is_file():
        try:
            dbg_data = parse_dbg_file(dbg_file)
        except Exception:
            dbg_data = None

    # Process .dbg symbols
    if dbg_data:
        # Calculate sizes per segment for labels
        sizes: Dict[str, int] = {}
        by_seg: Dict[int, List[Symbol]] = {}
        for sym in dbg_data.all_symbols:
            if sym.seg is not None and sym.type == "lab":
                by_seg.setdefault(sym.seg, []).append(sym)

        for seg_syms in by_seg.values():
            sorted_seg = sorted(seg_syms, key=lambda s: s.val)
            for i, s in enumerate(sorted_seg):
                for j in range(i + 1, len(sorted_seg)):
                    if sorted_seg[j].val > s.val:
                        sizes[s.name] = sorted_seg[j].val - s.val
                        break

        for sym in dbg_data.all_symbols:
            # Skip imported symbols that do not define an address
            if sym.type == "imp":
                continue
            name = sym.name.lstrip(".")
            key = (name, sym.val)
            if key in seen_keys:
                continue
            seen_keys.add(key)

            loc = dbg_data.get_symbol_def_location(sym)
            size = sizes.get(sym.name)
            scope_name = None
            if sym.scope in dbg_data.scopes:
                sc = dbg_data.scopes[sym.scope]
                if sc.name:
                    scope_name = sc.name
                if size is None and sc.size > 0:
                    size = sc.size

            symbols.append(
                ResolvedSymbol(
                    name=name,
                    val=sym.val,
                    bank=sym.bank,
                    offset=sym.offset,
                    region=classify_region(sym.val),
                    type=sym.type or "lab",
                    size=size,
                    defined_at=loc,
                    scope=scope_name,
                )
            )

    # Process .cpu.sym symbols to augment or provide fallback
    if sym_file and sym_file.is_file():
        cpu_syms = _parse_cpu_sym(sym_file)
        for name, val in cpu_syms:
            clean_name = name.lstrip(".")
            key = (clean_name, val)
            if key in seen_keys:
                continue
            seen_keys.add(key)

            symbols.append(
                ResolvedSymbol(
                    name=clean_name,
                    val=val,
                    bank=(val >> 16) & 0xFF,
                    offset=val & 0xFFFF,
                    region=classify_region(val),
                    type="lab",
                )
            )

    # Fallback size estimation for address labels without size
    by_region: Dict[str, List[ResolvedSymbol]] = {}
    for sym in symbols:
        if sym.type == "lab":
            by_region.setdefault(sym.region, []).append(sym)

    for reg_syms in by_region.values():
        sorted_reg = sorted(reg_syms, key=lambda s: s.val)
        for i, s in enumerate(sorted_reg):
            if s.size is None:
                for j in range(i + 1, len(sorted_reg)):
                    if sorted_reg[j].val > s.val:
                        s.size = sorted_reg[j].val - s.val
                        break

    return symbols


def format_symbol(sym: ResolvedSymbol) -> Dict[str, Any]:
    """Format a ResolvedSymbol into the canonical output dictionary."""
    res: Dict[str, Any] = {
        "name": sym.name,
        "address": f"0x{sym.val:06X}",
        "bank": sym.bank,
        "offset": sym.offset,
        "region": sym.region,
        "type": sym.type,
    }
    if sym.size is not None:
        res["size"] = sym.size
    if sym.defined_at:
        res["definedAt"] = sym.defined_at
    if sym.scope:
        res["scope"] = sym.scope
    if sym.region == "ROM":
        res["gsu_rom_bank"] = sym.bank & 0x7F
        res["note"] = (
            f"GSU accesses ROM via ROMB (bank ${sym.bank & 0x7F:02X}) and R14 "
            f"(offset ${sym.offset:04X}); ldw/ldb only access GSURAM ($700000-$71FFFF)."
        )
    return res


def _resolve_source_lines(
    dbg_data: Optional[DbgData],
    addr_val: int,
    project_dir: Path,
) -> Optional[Dict[str, Any]]:
    """Resolve an address to precise source file and line number using DbgData spans."""
    if not dbg_data:
        return None

    # Determine candidate addresses to check:
    # 1. Exact addr_val
    # 2. If addr_val < 0x10000 (e.g. 16-bit GSU PC), try matching against GSUCODE segment
    candidates = [addr_val]
    if addr_val < 0x10000:
        gsu_seg = next(
            (s for s in dbg_data.segments.values() if s.name == "GSUCODE"),
            None,
        )
        if gsu_seg:
            bank = gsu_seg.start >> 16
            candidates.insert(0, (bank << 16) | addr_val)

    for cand_addr in candidates:
        for seg in dbg_data.segments.values():
            if seg.start <= cand_addr < seg.start + seg.size:
                offset_in_seg = cand_addr - seg.start
                matching_spans = [
                    s
                    for s in dbg_data.spans.values()
                    if s.seg == seg.id and s.start <= offset_in_seg < s.start + s.size
                ]
                if not matching_spans:
                    continue
                # Sort matching spans: prefer application sources (.sgs, .i, .s) over generic assembler macros
                candidate_lines = []
                for sp in matching_spans:
                    for l in dbg_data.lines.values():
                        if sp.id in l.spans:
                            f = dbg_data.files.get(l.file)
                            if f and f.name:
                                candidate_lines.append((f.name, l.line, sp.size))

                # Prioritize project sources (.sgs, .s, .i that are not GSU_Assembler.i)
                def _file_priority(item: Tuple[str, int, int]) -> Tuple[int, int]:
                    fname, lno, span_sz = item
                    is_lib_macro = "GSU_Assembler.i" in fname
                    is_opt_or_proj = fname.endswith((".sgs", ".s", ".i")) and not is_lib_macro
                    # priority: 0 for project source, 1 for others
                    prio = 0 if is_opt_or_proj else 1
                    return (prio, span_sz)

                candidate_lines.sort(key=_file_priority)
                matched_line: Optional[Tuple[str, int]] = (
                    (candidate_lines[0][0], candidate_lines[0][1])
                    if candidate_lines
                    else None
                )

                if matched_line:
                    file_name, line_num = matched_line
                    # Attempt to resolve snippet lines
                    resolved_file_path = project_dir / file_name
                    if not resolved_file_path.is_file():
                        # Try relative to repo or project root
                        resolved_file_path = (project_dir / file_name).resolve()

                    snippet: List[str] = []
                    if resolved_file_path.is_file():
                        try:
                            with open(resolved_file_path, "r", encoding="utf-8", errors="replace") as f:
                                all_file_lines = f.readlines()
                            start_l = max(0, line_num - 3)
                            end_l = min(len(all_file_lines), line_num + 3)
                            for idx in range(start_l, end_l):
                                prefix_char = "==> " if (idx + 1) == line_num else "    "
                                snippet.append(f"{prefix_char}{idx + 1:5d} | {all_file_lines[idx].rstrip()}")
                        except Exception:
                            pass

                    res: Dict[str, Any] = {
                        "path": file_name,
                        "lineNumber": line_num,
                    }
                    if snippet:
                        res["context"] = "\n".join(snippet)
                    return res

    return None


_LAST_PROJECT: Optional[str] = None


def _match_symbol(
    all_symbols: List[ResolvedSymbol],
    raw_name: str,
    after_addr: Optional[int] = None,
    prefer_region: Optional[str] = None,
) -> Optional[ResolvedSymbol]:
    """Match a symbol by name (exact, _function/_func suffix, scoped suffix, case-insensitive).

    When after_addr is specified (e.g. resolving an end_symbol or local @label relative to a start symbol),
    prefers the nearest matching symbol in the same region with val >= after_addr.
    """
    clean_name = raw_name.lstrip(".")
    candidates = [clean_name]
    if not clean_name.endswith("_function"):
        candidates.append(f"{clean_name}_function")
    if not clean_name.endswith("_func"):
        candidates.append(f"{clean_name}_func")
    if "::" in clean_name:
        candidates.append(clean_name.split("::")[-1])

    def _pick_best(matches: List[ResolvedSymbol]) -> Optional[ResolvedSymbol]:
        if not matches:
            return None
        if after_addr is not None:
            forward = [
                m
                for m in matches
                if m.val >= after_addr
                and (prefer_region is None or m.region == prefer_region)
            ]
            if forward:
                forward.sort(key=lambda s: s.val - after_addr)
                return forward[0]
        return matches[0]

    for cand in candidates:
        exact_matches = [s for s in all_symbols if s.name == cand]
        picked = _pick_best(exact_matches)
        if picked:
            return picked

    for cand in candidates:
        cand_lower = cand.lower()
        ci_matches = [s for s in all_symbols if s.name.lower() == cand_lower]
        picked = _pick_best(ci_matches)
        if picked:
            return picked

    return None


def resolve_address(
    project: Optional[str] = None,
    symbol: Optional[str] = None,
    end_symbol: Optional[str] = None,
    end_address: Optional[Union[str, int]] = None,
    address: Optional[Union[str, int]] = None,
    prefix: Optional[str] = None,
    range: Optional[str] = None,
    max_results: int = 20,
) -> Dict[str, Any]:
    """Resolve symbol <-> address from ld65 .dbg and .cpu.sym outputs.

    Args:
        project: Directory name or path containing built ROM artifacts (optional, defaults to last used or 'IsoEngine').
        symbol: Symbol name to look up (with or without leading '.').
        end_symbol: Optional end symbol/label name to compute byte_span and 512-byte cache metrics from 'symbol'.
        end_address: Optional end address (hex string or int) to compute byte_span and cache metrics from 'symbol'.
        address: Hex string or int (or bank:offset like "80:9DA6") to resolve.
        prefix: Prefix string to find matching symbols.
        range: Memory address range string (e.g. "700000-701FFF").
        max_results: Maximum results to return in lists (default: 20).

    Returns:
        Structured resolution results.
    """
    global _LAST_PROJECT

    target_project = project
    if not target_project:
        target_project = _LAST_PROJECT or "snes-sfx-demo/X-GSU/IsoEngine"

    try:
        project_dir = resolve_test_dir(target_project)
        _LAST_PROJECT = target_project
    except FileNotFoundError:
        # If default fallback fails, try finding any standard dir
        return {
            "status": "ERROR",
            "error": f"Project directory not found: '{target_project}'",
        }

    dbg_file, sym_file, searched = _find_build_artifacts(project_dir)
    if not dbg_file and not sym_file:
        searched_names = [p.name for p in searched]
        return {
            "status": "ERROR",
            "error": (
                f"No build artifacts (.dbg or .cpu.sym) found in '{project_dir}'. "
                f"Searched: {', '.join(searched_names)}. "
                f"Please build the project first (e.g. './tools/run-sfx-test.sh {project}' "
                f"or 'make -C {project_dir}')."
            ),
            "searched_paths": [str(p) for p in searched],
        }

    warnings: List[str] = []
    primary_artifact = dbg_file or sym_file
    if primary_artifact:
        stale_warn = _check_stale_build(project_dir, primary_artifact)
        if stale_warn:
            warnings.append(stale_warn)

    all_symbols = _build_symbol_database(dbg_file, sym_file)

    output: Dict[str, Any] = {
        "status": "SUCCESS",
        "project": str(project_dir),
    }
    if warnings:
        output["warning"] = warnings[0]
        output["warnings"] = warnings

    # 1. Symbol Query
    if symbol is not None:
        clean_name = symbol.lstrip(".")
        matched_sym = _match_symbol(all_symbols, symbol)

        if matched_sym:
            output["found"] = True
            output["name"] = matched_sym.name
            output["address"] = f"0x{matched_sym.val:06X}"
            output["bank"] = matched_sym.bank
            output["offset"] = matched_sym.offset
            output["region"] = matched_sym.region
            output["type"] = matched_sym.type
            if matched_sym.size is not None:
                output["size"] = matched_sym.size
            if matched_sym.defined_at:
                output["definedAt"] = matched_sym.defined_at
            if matched_sym.region == "ROM":
                output["gsu_rom_bank"] = matched_sym.bank & 0x7F
                output["note"] = (
                    f"GSU accesses ROM via ROMB (bank ${matched_sym.bank & 0x7F:02X}) and R14 "
                    f"(offset ${matched_sym.offset:04X}); ldw/ldb only access GSURAM ($700000-$71FFFF)."
                )

            # Report distance to the next non-local symbol in the same bank/region
            if not matched_sym.name.startswith("@"):
                next_globals = [
                    s
                    for s in all_symbols
                    if s.region == matched_sym.region
                    and s.bank == matched_sym.bank
                    and s.type == "lab"
                    and not s.name.startswith("@")
                    and s.val > matched_sym.val
                ]
                if next_globals:
                    next_globals.sort(key=lambda s: s.val)
                    ng = next_globals[0]
                    output["next_global_symbol"] = ng.name
                    output["next_global_address"] = f"0x{ng.val:06X}"
                    output["span_to_next_global"] = ng.val - matched_sym.val

            # Compute explicit byte_span & 512-byte cache metrics when end_symbol or end_address is given
            resolved_end_val: Optional[int] = None
            if end_symbol is not None:
                matched_end = _match_symbol(
                    all_symbols,
                    end_symbol,
                    after_addr=matched_sym.val,
                    prefer_region=matched_sym.region,
                )
                if matched_end is None:
                    return {
                        "status": "NOT_FOUND",
                        "found": False,
                        "error": f"End symbol '{end_symbol}' not found in '{project}'.",
                    }
                output["end_symbol"] = matched_end.name
                resolved_end_val = matched_end.val
            elif end_address is not None:
                try:
                    resolved_end_val = parse_address(end_address)
                except ValueError as e:
                    return {
                        "status": "ERROR",
                        "error": f"Invalid end_address format '{end_address}': {e}",
                    }

            if resolved_end_val is not None:
                byte_span = resolved_end_val - matched_sym.val
                cbr_base = matched_sym.val & ~0xF
                aligned_cache_bytes = resolved_end_val - cbr_base
                cache_lines_16b = (
                    ((resolved_end_val + 15) & ~0xF) - cbr_base
                ) // 16 if resolved_end_val >= matched_sym.val else 0
                output["end_address"] = f"0x{resolved_end_val:06X}"
                output["byte_span"] = byte_span
                output["aligned_cache_bytes"] = aligned_cache_bytes
                output["cache_lines_16b"] = cache_lines_16b
                output["fits_in_512b_cache"] = bool(
                    0 <= aligned_cache_bytes <= 512
                )
        else:
            output["status"] = "NOT_FOUND"
            output["found"] = False
            output["error"] = f"Symbol '{symbol}' not found in '{project}'."
            all_names = [s.name for s in all_symbols]
            close = difflib.get_close_matches(clean_name, all_names, n=5, cutoff=0.3)
            if len(close) < 5:
                # Add substring matches
                for name in all_names:
                    if clean_name.lower() in name.lower() and name not in close:
                        close.append(name)
                        if len(close) >= 5:
                            break
            output["suggestions"] = close

    # 2. Address Query
    if address is not None:
        try:
            addr_val = parse_address(address)
        except ValueError as e:
            return {
                "status": "ERROR",
                "error": f"Invalid address format '{address}': {e}",
            }

        addr_region = classify_region(addr_val)
        output["query_address"] = f"0x{addr_val:06X}"
        output["region"] = addr_region

        # Filter candidate labels to the same memory region
        region_labels = [
            s for s in all_symbols if s.region == addr_region and s.type == "lab"
        ]
        # Deduplicate identical (name, val)
        dedup_labels: List[ResolvedSymbol] = []
        seen = set()
        for s in region_labels:
            if (s.name, s.val) not in seen:
                seen.add((s.name, s.val))
                dedup_labels.append(s)

        dedup_labels.sort(key=lambda s: s.val)

        exact_sym: Optional[ResolvedSymbol] = None
        nearest_before: Optional[ResolvedSymbol] = None
        nearest_after: Optional[ResolvedSymbol] = None

        for s in dedup_labels:
            if s.val == addr_val:
                exact_sym = s
                nearest_before = s
            elif s.val < addr_val:
                nearest_before = s
            elif s.val > addr_val:
                nearest_after = s
                break

        if exact_sym:
            output["found"] = True
            output["exact_match"] = True
            output["name"] = exact_sym.name
            output["address"] = f"0x{exact_sym.val:06X}"
            output["bank"] = exact_sym.bank
            output["offset"] = exact_sym.offset
            output["delta"] = 0
            if exact_sym.size is not None:
                output["size"] = exact_sym.size
            if exact_sym.defined_at:
                output["definedAt"] = exact_sym.defined_at
        else:
            output["exact_match"] = False

        nb_dict = None
        if nearest_before:
            nb_dict = format_symbol(nearest_before)
            nb_dict["delta"] = addr_val - nearest_before.val
        output["nearestBefore"] = nb_dict

        na_dict = None
        if nearest_after:
            na_dict = format_symbol(nearest_after)
            na_dict["delta"] = nearest_after.val - addr_val
        output["nearestAfter"] = na_dict

        # Also resolve source line location from dbg file if available
        dbg_data_obj = parse_dbg_file(dbg_file) if dbg_file else None
        source_loc = _resolve_source_lines(dbg_data_obj, addr_val, project_dir)
        if source_loc:
            output["sourceLocation"] = source_loc

    # 3. Range Query
    if range is not None:
        raw_range = range.strip()
        parts: List[str] = []
        if ".." in raw_range:
            parts = raw_range.split("..", 1)
        elif "-" in raw_range:
            parts = raw_range.split("-", 1)
        elif ":" in raw_range:
            parts = raw_range.split(":", 1)
        else:
            return {
                "status": "ERROR",
                "error": f"Invalid range format '{range}'. Expected 'start-end' (e.g. '700000-701FFF').",
            }

        try:
            start_addr = parse_address(parts[0])
            end_addr = parse_address(parts[1])
        except ValueError as e:
            return {
                "status": "ERROR",
                "error": f"Invalid address in range '{range}': {e}",
            }

        if start_addr > end_addr:
            start_addr, end_addr = end_addr, start_addr

        # Filter symbols within range
        in_range = [
            s
            for s in all_symbols
            if s.type == "lab" and start_addr <= s.val <= end_addr
        ]
        # Collapse duplicates (identical name and address)
        unique_range: List[ResolvedSymbol] = []
        seen_range = set()
        for s in in_range:
            if (s.name, s.val) not in seen_range:
                seen_range.add((s.name, s.val))
                unique_range.append(s)

        unique_range.sort(key=lambda s: (s.val, s.name))

        output["range"] = f"0x{start_addr:06X}-0x{end_addr:06X}"
        output["total"] = len(unique_range)
        output["symbols"] = [
            format_symbol(s) for s in unique_range[:max_results]
        ]

    # 4. Prefix Query
    if prefix is not None:
        p_clean = prefix.lstrip(".").lower()
        matched_prefix = [
            s for s in all_symbols if s.name.lower().startswith(p_clean)
        ]
        # Collapse duplicates
        unique_prefix: List[ResolvedSymbol] = []
        seen_p = set()
        for s in matched_prefix:
            if (s.name, s.val) not in seen_p:
                seen_p.add((s.name, s.val))
                unique_prefix.append(s)

        unique_prefix.sort(key=lambda s: (s.val, s.name))
        output["prefix"] = prefix
        output["total_matches"] = len(unique_prefix)
        output["matches"] = [
            format_symbol(s) for s in unique_prefix[:max_results]
        ]

    # If no specific query was requested, return summary
    if (
        symbol is None
        and address is None
        and range is None
        and prefix is None
    ):
        output["message"] = (
            f"Loaded {len(all_symbols)} symbols from '{project}'. "
            f"Specify 'symbol', 'address', 'prefix', or 'range' to query."
        )
        output["total_symbols"] = len(all_symbols)

    return output
