"""Thin pure in-process query formatter over BuildArtifactIndex for sfx_resolve_address."""

from typing import Any, Dict, List, Optional, Set, Tuple, Union

from mesen_mcp.artifacts import (
    BuildArtifactIndex,
    ResolvedSymbol,
    classify_region,
    parse_address,
)
from mesen_mcp.utils import resolve_test_dir

_LAST_PROJECT: Optional[str] = None


def format_symbol(sym: ResolvedSymbol) -> Dict[str, Any]:
    """Format a ResolvedSymbol into the canonical sfx_resolve_address dictionary."""
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


def resolve_address(
    project: Optional[str] = None,
    symbol: Optional[str] = None,
    symbols: Optional[List[str]] = None,
    end_symbol: Optional[str] = None,
    end_address: Optional[Union[str, int]] = None,
    address: Optional[Union[str, int]] = None,
    prefix: Optional[str] = None,
    range: Optional[str] = None,
    summary: bool = False,
    max_results: int = 20,
) -> Dict[str, Any]:
    """Resolve symbol <-> address from ld65 .dbg and .cpu.sym outputs without launching Mesen."""
    global _LAST_PROJECT

    target_project = project or _LAST_PROJECT or "snes-sfx-demo/X-GSU/IsoEngine"

    try:
        project_dir = resolve_test_dir(target_project)
        _LAST_PROJECT = target_project
    except FileNotFoundError:
        return {
            "status": "ERROR",
            "error": f"Project directory not found: '{target_project}'",
        }

    index = BuildArtifactIndex.from_dir(project_dir)
    if not index.has_artifacts:
        searched_names = [p.name for p in index.searched_paths]
        return {
            "status": "ERROR",
            "error": (
                f"No build artifacts (.dbg or .cpu.sym) found in '{project_dir}'. "
                f"Searched: {', '.join(searched_names)}. "
                f"Please build the project first (e.g. './tools/run-sfx-test.sh {project}' "
                f"or 'make -C {project_dir}')."
            ),
            "searched_paths": [str(p) for p in index.searched_paths],
        }

    output: Dict[str, Any] = {
        "status": "SUCCESS",
        "project": str(project_dir),
    }

    stale_warn = index.check_stale_build()
    if stale_warn:
        output["warning"] = stale_warn
        output["warnings"] = [stale_warn]
        output["stale_build_warning"] = stale_warn

    all_symbols = index.symbols

    # 1. Symbol Query
    if symbol is not None:
        matched_sym = index.resolve_symbol(symbol, allow_output_aliases=True)

        if matched_sym is not None:
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

            if end_symbol is not None:
                matched_end = index.resolve_symbol(
                    end_symbol,
                    allow_output_aliases=False,
                    after_addr=matched_sym.val,
                    prefer_region=matched_sym.region,
                )
                if matched_end is None:
                    err_out: Dict[str, Any] = {
                        "status": "NOT_FOUND",
                        "found": False,
                        "error": f"End symbol '{end_symbol}' not found in '{project}'.",
                    }
                    if stale_warn:
                        err_out["warning"] = stale_warn
                        err_out["warnings"] = [stale_warn]
                        err_out["stale_build_warning"] = stale_warn
                    return err_out
                output["end_symbol"] = matched_end.name
                metrics = index.compute_cache_span(
                    matched_sym, matched_end.val, inherit_bank=False
                )
                output.update(metrics.to_dict())
            elif end_address is not None:
                try:
                    metrics = index.compute_cache_span(
                        matched_sym, end_address, inherit_bank=True
                    )
                except ValueError as e:
                    return {
                        "status": "ERROR",
                        "error": f"Invalid end_address format '{end_address}': {e}",
                    }
                output.update(metrics.to_dict())
        else:
            output["status"] = "NOT_FOUND"
            output["found"] = False
            output["error"] = f"Symbol '{symbol}' not found in '{project}'."
            output["suggestions"] = index.suggest_symbols(symbol, limit=5)

    # 1b. Batch Symbols Query
    if symbols is not None:
        batch_results: Dict[str, Any] = {}
        missing: List[str] = []
        found_count = 0
        for sym_query in symbols:
            matched_sym = index.resolve_symbol(sym_query, allow_output_aliases=True)
            if matched_sym is not None:
                found_count += 1
                entry = format_symbol(matched_sym)
                entry["found"] = True
                entry["offset_hex"] = f"0x{matched_sym.offset:04X}"
                entry["word_aligned"] = (matched_sym.offset % 2) == 0
                batch_results[sym_query] = entry
            else:
                missing.append(sym_query)
                batch_results[sym_query] = {
                    "found": False,
                    "error": f"Symbol '{sym_query}' not found in '{target_project}'.",
                    "suggestions": index.suggest_symbols(sym_query, limit=5),
                }
        output["results"] = batch_results
        output["found_count"] = found_count
        output["missing"] = missing
        if missing and found_count == 0 and symbol is None:
            output["status"] = "NOT_FOUND"

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

        dedup_labels: List[ResolvedSymbol] = []
        seen: Set[Tuple[str, int]] = set()
        for s in all_symbols:
            if s.region == addr_region and s.type == "lab":
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

        if exact_sym is not None:
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
        if nearest_before is not None:
            nb_dict = format_symbol(nearest_before)
            nb_dict["delta"] = addr_val - nearest_before.val
        output["nearestBefore"] = nb_dict

        na_dict = None
        if nearest_after is not None:
            na_dict = format_symbol(nearest_after)
            na_dict["delta"] = nearest_after.val - addr_val
        output["nearestAfter"] = na_dict

        source_loc = index.resolve_source_location(addr_val)
        if source_loc is not None:
            output["sourceLocation"] = source_loc

    # 3. Range Query
    if range is not None:
        raw_range = range.strip()
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

        unique_range: List[ResolvedSymbol] = []
        seen_range: Set[Tuple[str, int]] = set()
        for s in all_symbols:
            if s.type == "lab" and start_addr <= s.val <= end_addr:
                if (s.name, s.val) not in seen_range:
                    seen_range.add((s.name, s.val))
                    unique_range.append(s)

        unique_range.sort(key=lambda s: (s.val, s.name))

        output["range"] = f"0x{start_addr:06X}-0x{end_addr:06X}"
        output["total"] = len(unique_range)
        output["symbols"] = [format_symbol(s) for s in unique_range[:max_results]]

    # 4. Prefix Query
    if prefix is not None:
        p_clean = prefix.lstrip(".").lower()
        unique_prefix: List[ResolvedSymbol] = []
        seen_p: Set[Tuple[str, int]] = set()
        for s in all_symbols:
            if s.name.lower().startswith(p_clean):
                if (s.name, s.val) not in seen_p:
                    seen_p.add((s.name, s.val))
                    unique_prefix.append(s)

        unique_prefix.sort(key=lambda s: (s.val, s.name))
        output["prefix"] = prefix
        output["total_matches"] = len(unique_prefix)
        output["matches"] = [format_symbol(s) for s in unique_prefix[:max_results]]

    # 5. Debug Artifact Summary Query
    if summary:
        dbg_summary: Dict[str, Any] = {
            "total_symbols": len(all_symbols),
            "dbg_file": str(index.dbg_file) if index.dbg_file else None,
            "sym_file": str(index.sym_file) if index.sym_file else None,
        }
        if index.dbg_data is not None:
            all_files = sorted(index.dbg_data.files.values(), key=lambda f: f.id)
            gsu_files = [
                {"id": f.id, "name": f.name, "size": f.size, "mod": f.mod}
                for f in all_files
                if f.name.endswith((".sgs", ".i", ".inc"))
            ]
            dbg_summary["file_count"] = len(all_files)
            dbg_summary["line_count"] = len(index.dbg_data.lines)
            dbg_summary["segment_count"] = len(index.dbg_data.segments)
            dbg_summary["gsu_file_count"] = len(gsu_files)
            dbg_summary["gsu_files"] = gsu_files[:max_results]
        output["dbg_summary"] = dbg_summary

    if (
        symbol is None
        and symbols is None
        and address is None
        and range is None
        and prefix is None
        and not summary
    ):
        output["message"] = (
            f"Loaded {len(all_symbols)} symbols from '{project}'. "
            f"Specify 'symbol', 'symbols', 'address', 'prefix', 'range', or 'summary=True' to query."
        )
        output["total_symbols"] = len(all_symbols)

    return output
