"""Pure in-process 24-bit build artifact and symbol index for SuperFX (.dbg and .cpu.sym)."""

from dataclasses import dataclass, field
import difflib
import os
from pathlib import Path
import re
from typing import Any, Dict, List, Optional, Sequence, Set, Tuple, Union

from mesen_mcp.dbg_parser import DbgData, Symbol, parse_dbg_string

OUTPUT_ALIASES: Tuple[str, ...] = (
    "TEST_OUTPUT",
    "test_output",
    "VECTOR_SUBTRACT_OUT",
    "VECTOR_ADD_OUT",
    "VECTOR_NEGATE_OUT",
    "VECTOR_CROSS_OUT",
    "VECTOR_TRANSFORM_OUT",
    "vector_normalize_out",
)

_CPU_SYM_PATTERN = re.compile(r"^\s*al\s+([0-9A-Fa-f]{6})\s+\.?([A-Za-z0-9_@:]+)")
_REGISTER_KEY_PATTERN = re.compile(r"^[rR](\d+)$")
_SYMBOL_OFFSET_KEY_PATTERN = re.compile(
    r"^(\.?[A-Za-z0-9_@:]+)(?:\s*([+-])\s*(0x[0-9A-Fa-f]+|\$[0-9A-Fa-f]+|\d+))?$"
)
_LUA_VAR_ASSIGN_PATTERN = re.compile(
    r"^(\s*(?:local\s+)?([A-Za-z0-9_]+)\s*=\s*)(0x[0-9A-Fa-f]+|\d+)(.*)$"
)
_LUA_SYNC_SKIP_VARS = frozenset({"index", "fail_count", "i", "j", "k"})

_STALE_SOURCE_EXTENSIONS = frozenset(
    {
        ".s",
        ".i",
        ".sgs",
        ".asm",
        ".inc",
        ".c",
        ".h",
        ".cfg",
        ".bin",
        ".pal",
        ".tab",
        ".map",
    }
)
_STALE_SOURCE_FILENAMES = frozenset({"makefile"})
_NON_GSU_CODE_SEGMENTS = frozenset(
    {
        "CODE",
        "MAIN_LOOP",
        "VBLANK",
        "RODATA",
        "DATA",
        "ZEROPAGE",
        "BSS",
        "HEADER",
        "VECTORS",
    }
)


def parse_address(val: Union[str, int]) -> int:
    """Parse a 16-bit or 24-bit address from integer or hex/bank:offset string."""
    if isinstance(val, int):
        return val
    s = str(val).strip()
    if not s:
        raise ValueError("Empty address string")

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
    """Classify a 24-bit SNES address into its canonical memory region."""
    bank = (addr >> 16) & 0xFF
    offset = addr & 0xFFFF

    # GSURAM / Cartridge Expansion SRAM: Banks $70-$77 ($700000-$71FFFF typical)
    if 0x70 <= bank <= 0x77:
        return "GSURAM"

    # SNES Work RAM (WRAM): Banks $7E-$7F
    if 0x7E <= bank <= 0x7F:
        return "SNES-WRAM"

    # System Banks $00-$3F and $80-$BF
    if (0x00 <= bank <= 0x3F) or (0x80 <= bank <= 0xBF):
        if offset < 0x2000:
            return "SNES-WRAM"
        if 0x2000 <= offset < 0x6000:
            return "MMIO"
        if offset >= 0x8000:
            return "ROM"
        return "EXPANSION"

    # HiROM / Extended Banks $40-$6F and $C0-$FF
    if offset >= 0x8000 or (0xC0 <= bank <= 0xFF):
        return "ROM"

    return "OTHER"


def find_artifact_by_stem(
    project_dir: Path, suffix: str
) -> Tuple[Optional[Path], List[Path]]:
    """Locate a build artifact with deterministic stem priority: Test -> SuperFX -> <dir.name> -> sorted glob."""
    searched: List[Path] = []
    seen_stems: Set[str] = set()
    stems: List[str] = []
    for cand in ("Test", "SuperFX", project_dir.name):
        if cand and cand not in seen_stems:
            seen_stems.add(cand)
            stems.append(cand)

    for stem in stems:
        p = project_dir / f"{stem}{suffix}"
        searched.append(p)
        if p.is_file():
            return p, searched

    for p in sorted(project_dir.glob(f"*{suffix}")):
        if p not in searched:
            searched.append(p)
        if p.is_file():
            return p, searched

    return None, searched


@dataclass
class ResolvedSymbol:
    """Canonical 24-bit resolved symbol record."""

    name: str
    val: int
    bank: int
    offset: int
    region: str
    type: str = "lab"  # "lab", "equ", "imp"
    size: Optional[int] = None
    defined_at: Optional[Dict[str, Any]] = None
    scope: Optional[str] = None
    segment_name: Optional[str] = None

    @property
    def work_ram_offset(self) -> Optional[int]:
        """Linear offset in Mesen emu.memType.gsuWorkRam for Bank $70/$71 symbols."""
        if self.bank in (0x70, 0x71):
            return ((self.bank - 0x70) << 16) | self.offset
        return None


@dataclass(frozen=True)
class GsuTarget:
    """Validated GSU register or Bank $70/$71 GSURAM target for Lua harness emission."""

    key: str
    base_name: str
    offset_delta: int = 0
    register: Optional[str] = None
    symbol: Optional[ResolvedSymbol] = None
    full_address: Optional[int] = None
    work_ram_addr: Optional[int] = None
    size: int = 2

    @property
    def is_register(self) -> bool:
        return self.register is not None

    @property
    def has_explicit_offset(self) -> bool:
        return self.offset_delta != 0


@dataclass(frozen=True)
class PcSpanMatch:
    """Resolved GSU 16-bit PC hotspot attribution."""

    pc: int
    function: str
    label: str


@dataclass(frozen=True)
class CacheSpanMetrics:
    """512-byte GSU instruction cache residency metrics between two addresses."""

    start_address: int
    end_address: int
    byte_span: int
    aligned_cache_bytes: int
    cache_lines_16b: int
    fits_in_512b_cache: bool

    def to_dict(self) -> Dict[str, Any]:
        return {
            "end_address": f"0x{self.end_address:06X}",
            "byte_span": self.byte_span,
            "aligned_cache_bytes": self.aligned_cache_bytes,
            "cache_lines_16b": self.cache_lines_16b,
            "fits_in_512b_cache": self.fits_in_512b_cache,
        }


def parse_cpu_sym_string(content: str) -> List[Tuple[str, int]]:
    """Parse ca65/VICE .cpu.sym text into (symbol_name, 24-bit address) pairs."""
    results: List[Tuple[str, int]] = []
    for line in content.splitlines():
        m = _CPU_SYM_PATTERN.match(line)
        if m:
            addr = int(m.group(1), 16)
            name = m.group(2)
            results.append((name, addr))
    return results


@dataclass
class BuildArtifactIndex:
    """Pure in-process 24-bit symbol, PC-span, and cache-layout index over .dbg and .cpu.sym."""

    project_dir: Optional[Path] = None
    dbg_file: Optional[Path] = None
    sym_file: Optional[Path] = None
    searched_paths: List[Path] = field(default_factory=list)
    dbg_data: Optional[DbgData] = None
    symbols: List[ResolvedSymbol] = field(default_factory=list)
    function_spans: List[Tuple[int, int, str]] = field(default_factory=list)
    label_spans: List[Tuple[int, int, str, str]] = field(default_factory=list)
    function_entries: Dict[str, int] = field(default_factory=dict)
    _has_explicit_artifacts: bool = False

    @property
    def has_artifacts(self) -> bool:
        return (
            self._has_explicit_artifacts
            or self.dbg_file is not None
            or self.sym_file is not None
            or self.dbg_data is not None
            or len(self.symbols) > 0
        )

    @classmethod
    def from_dir(cls, project_dir: Union[str, Path]) -> "BuildArtifactIndex":
        """Load .dbg and .cpu.sym from project_dir in a single pass using deterministic stem priority."""
        p = Path(project_dir).resolve()
        dbg_file, searched_dbg = find_artifact_by_stem(p, ".dbg")
        sym_file, searched_sym = find_artifact_by_stem(p, ".cpu.sym")
        searched = searched_dbg + searched_sym

        dbg_text = ""
        if dbg_file is not None and dbg_file.is_file():
            try:
                dbg_text = dbg_file.read_text(encoding="utf-8", errors="replace")
            except OSError:
                dbg_text = ""

        cpu_sym_text = ""
        if sym_file is not None and sym_file.is_file():
            try:
                cpu_sym_text = sym_file.read_text(encoding="utf-8", errors="replace")
            except OSError:
                cpu_sym_text = ""

        return cls._build(
            dbg_text=dbg_text,
            cpu_sym_text=cpu_sym_text,
            project_dir=p,
            dbg_file=dbg_file,
            sym_file=sym_file,
            searched_paths=searched,
            has_explicit_artifacts=(dbg_file is not None or sym_file is not None),
        )

    @classmethod
    def from_strings(
        cls,
        dbg_text: str = "",
        cpu_sym_text: str = "",
        project_dir: Optional[Union[str, Path]] = None,
    ) -> "BuildArtifactIndex":
        """Construct a pure in-memory BuildArtifactIndex without touching the filesystem."""
        resolved_dir = Path(project_dir).resolve() if project_dir is not None else None
        has_content = bool(dbg_text.strip() or cpu_sym_text.strip())
        return cls._build(
            dbg_text=dbg_text,
            cpu_sym_text=cpu_sym_text,
            project_dir=resolved_dir,
            dbg_file=None,
            sym_file=None,
            searched_paths=[],
            has_explicit_artifacts=has_content,
        )

    @classmethod
    def from_symbol_dict(
        cls,
        symbols: Dict[str, int],
        sizes: Optional[Dict[str, int]] = None,
        project_dir: Optional[Union[str, Path]] = None,
    ) -> "BuildArtifactIndex":
        """Construct a BuildArtifactIndex from a legacy symbol->offset/address mapping."""
        resolved_dir = Path(project_dir).resolve() if project_dir is not None else None
        sizes_map = sizes or {}
        resolved_list: List[ResolvedSymbol] = []
        for raw_name, raw_val in symbols.items():
            clean_name = raw_name.lstrip(".")
            if raw_val < 0x10000:
                val = 0x700000 | (raw_val & 0xFFFF)
            elif raw_val < 0x20000:
                val = 0x710000 | (raw_val & 0xFFFF)
            else:
                val = raw_val
            sym_size = (
                sizes_map.get(raw_name)
                or sizes_map.get(clean_name)
                or sizes_map.get(clean_name.upper())
                or sizes_map.get(clean_name.lower())
            )
            resolved_list.append(
                ResolvedSymbol(
                    name=clean_name,
                    val=val,
                    bank=(val >> 16) & 0xFF,
                    offset=val & 0xFFFF,
                    region=classify_region(val),
                    type="lab",
                    size=sym_size,
                )
            )

        cls._populate_fallback_sizes(resolved_list)
        idx = cls(
            project_dir=resolved_dir,
            symbols=resolved_list,
            _has_explicit_artifacts=bool(resolved_list),
        )
        idx._build_pc_spans()
        return idx

    @classmethod
    def _build(
        cls,
        *,
        dbg_text: str,
        cpu_sym_text: str,
        project_dir: Optional[Path],
        dbg_file: Optional[Path],
        sym_file: Optional[Path],
        searched_paths: List[Path],
        has_explicit_artifacts: bool,
    ) -> "BuildArtifactIndex":
        dbg_data: Optional[DbgData] = None
        if dbg_text.strip():
            try:
                dbg_data = parse_dbg_string(dbg_text)
            except Exception:
                dbg_data = None

        symbols: List[ResolvedSymbol] = []
        seen_keys: Set[Tuple[str, int]] = set()
        dbg_label_names: Set[str] = set()

        if dbg_data is not None:
            seg_sizes: Dict[Tuple[int, int, str], int] = {}
            by_seg: Dict[int, List[Symbol]] = {}
            for sym in dbg_data.all_symbols:
                if sym.seg is not None and sym.type == "lab":
                    by_seg.setdefault(sym.seg, []).append(sym)

            for seg_id, seg_syms in by_seg.items():
                sorted_seg = sorted(seg_syms, key=lambda s: s.val)
                for i, s in enumerate(sorted_seg):
                    for j in range(i + 1, len(sorted_seg)):
                        if sorted_seg[j].val > s.val:
                            seg_sizes[(seg_id, s.val, s.name)] = (
                                sorted_seg[j].val - s.val
                            )
                            break

            for sym in dbg_data.all_symbols:
                if sym.type == "imp":
                    continue
                clean_name = sym.name.lstrip(".")
                key = (clean_name, sym.val)
                if key in seen_keys:
                    continue
                seen_keys.add(key)
                if sym.type == "lab":
                    dbg_label_names.add(clean_name)

                loc = dbg_data.get_symbol_def_location(sym)
                size = (
                    seg_sizes.get((sym.seg, sym.val, sym.name))
                    if sym.seg is not None
                    else None
                )
                scope_name: Optional[str] = None
                if sym.scope in dbg_data.scopes:
                    sc = dbg_data.scopes[sym.scope]
                    if sc.name:
                        scope_name = sc.name
                        if (
                            size is None
                            and sc.size > 0
                            and clean_name
                            in (sc.name, f"{sc.name}_function", f"{sc.name}_func")
                        ):
                            size = sc.size

                seg_name = (
                    dbg_data.segments[sym.seg].name
                    if (sym.seg is not None and sym.seg in dbg_data.segments)
                    else None
                )

                symbols.append(
                    ResolvedSymbol(
                        name=clean_name,
                        val=sym.val,
                        bank=sym.bank,
                        offset=sym.offset,
                        region=classify_region(sym.val),
                        type=sym.type or "lab",
                        size=size,
                        defined_at=loc,
                        scope=scope_name,
                        segment_name=seg_name,
                    )
                )

        if cpu_sym_text.strip():
            for raw_name, val in parse_cpu_sym_string(cpu_sym_text):
                clean_name = raw_name.lstrip(".")
                key = (clean_name, val)
                # Augment .dbg without clobbering existing .dbg label definitions
                if key in seen_keys or clean_name in dbg_label_names:
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

        cls._populate_fallback_sizes(symbols)

        index = cls(
            project_dir=project_dir,
            dbg_file=dbg_file,
            sym_file=sym_file,
            searched_paths=searched_paths,
            dbg_data=dbg_data,
            symbols=symbols,
            _has_explicit_artifacts=has_explicit_artifacts,
        )
        index._build_pc_spans()
        return index

    @staticmethod
    def _populate_fallback_sizes(symbols: List[ResolvedSymbol]) -> None:
        """Estimate sizes per (region, bank) for label symbols lacking .dbg segment sizes."""
        by_region_bank: Dict[Tuple[str, int], List[ResolvedSymbol]] = {}
        for sym in symbols:
            if sym.type == "lab":
                by_region_bank.setdefault((sym.region, sym.bank), []).append(sym)

        for group in by_region_bank.values():
            sorted_group = sorted(group, key=lambda s: s.val)
            for i, s in enumerate(sorted_group):
                if s.size is None:
                    for j in range(i + 1, len(sorted_group)):
                        if sorted_group[j].val > s.val:
                            s.size = sorted_group[j].val - s.val
                            break

    def _build_pc_spans(self) -> None:
        """Build 16-bit GSU PC function and label spans restricted strictly to GSU code/GSURAM."""
        if self.dbg_data is not None:
            gsu_segs = [
                s
                for s in self.dbg_data.segments.values()
                if s.name.upper().startswith("GSUCODE")
            ]
            if gsu_segs:
                self._build_dbg_gsu_spans(self.dbg_data, gsu_segs)
                return

        self._build_fallback_gsu_spans()

    def _build_dbg_gsu_spans(self, dbg_data: DbgData, gsu_segs: List[Any]) -> None:
        gsu_seg_ids = {s.id: s for s in gsu_segs}
        gsu_syms = [
            sym
            for sym in dbg_data.all_symbols
            if sym.seg in gsu_seg_ids
            and sym.type == "lab"
            and "LOCAL-MACRO" not in sym.name
            and not sym.name.startswith("__")
        ]
        if not gsu_syms:
            return

        seg_end_by_id = {
            s.id: min(0x10000, (s.start & 0xFFFF) + max(s.size, 1)) for s in gsu_segs
        }
        max_seg_end = max(seg_end_by_id.values(), default=0x10000)

        gsu_syms.sort(
            key=lambda s: (
                s.offset,
                0 if s.name.endswith("_function") or s.name == "GSU_Code" else 1,
                s.name.startswith("@"),
            )
        )

        func_anchors: List[Tuple[int, str, Optional[int]]] = []
        seen_func_offsets: Set[int] = set()
        for sym in gsu_syms:
            clean = sym.name.lstrip(".")
            if clean.endswith("_function"):
                fn_name = clean[: -len("_function")]
                sc = dbg_data.get_scope(fn_name)
                sc_size = sc.size if (sc and sc.size > 0) else None
                if sym.offset not in seen_func_offsets:
                    seen_func_offsets.add(sym.offset)
                    func_anchors.append((sym.offset, fn_name, sc_size))
                    self.function_entries[fn_name] = sym.offset
            elif clean in ("GSU_Code", "GSU_Entry", "main"):
                if sym.offset not in seen_func_offsets:
                    seen_func_offsets.add(sym.offset)
                    func_anchors.append((sym.offset, clean, None))
                    self.function_entries[clean] = sym.offset

        if not func_anchors:
            for sym in gsu_syms:
                clean = sym.name.lstrip(".")
                if not clean.startswith("@") and sym.offset not in seen_func_offsets:
                    seen_func_offsets.add(sym.offset)
                    func_anchors.append((sym.offset, clean, None))
                    self.function_entries[clean] = sym.offset

        func_anchors.sort(key=lambda item: item[0])
        for i, (start, func_name, sc_size) in enumerate(func_anchors):
            next_start = (
                func_anchors[i + 1][0] if i + 1 < len(func_anchors) else max_seg_end
            )
            end = min(next_start, start + sc_size) if sc_size else next_start
            if end > start:
                self.function_spans.append((start, end, func_name))

        dedup_syms: List[Symbol] = []
        seen_lbl_offsets: Set[int] = set()
        for sym in gsu_syms:
            if sym.offset not in seen_lbl_offsets:
                seen_lbl_offsets.add(sym.offset)
                dedup_syms.append(sym)

        for i, sym in enumerate(dedup_syms):
            start = sym.offset
            seg_end = (
                seg_end_by_id.get(sym.seg, max_seg_end)
                if sym.seg is not None
                else max_seg_end
            )
            end = dedup_syms[i + 1].offset if i + 1 < len(dedup_syms) else seg_end
            if end <= start:
                continue
            sc = dbg_data.scopes.get(sym.scope)
            enclosing_func = (
                sc.name if (sc and sc.name) else self.resolve_function(start)
            )
            label_name = sym.name.lstrip(".")
            if label_name.endswith("_function"):
                label_name = label_name[: -len("_function")]
            self.label_spans.append((start, end, label_name, enclosing_func))

    def _build_fallback_gsu_spans(self) -> None:
        """Build GSU PC intervals when GSUCODE .dbg segment is absent, excluding ROM data/65816/Bank $71."""
        eligible: List[ResolvedSymbol] = []
        for sym in self.symbols:
            if sym.type != "lab":
                continue
            if "LOCAL-MACRO" in sym.name or sym.name.startswith("__"):
                continue
            if sym.segment_name and sym.segment_name.upper() in _NON_GSU_CODE_SEGMENTS:
                continue
            # Never include 65816 ROM ($80-$FF), SNES WRAM ($7E-$7F), Staging Bank $71 ($71-$77),
            # or system-bank LowWRAM/MMIO ($00-$3F:<$8000) in GSU PC intervals.
            if sym.bank >= 0x80 or (0x71 <= sym.bank <= 0x7F):
                continue
            if 0x00 <= sym.bank <= 0x3F and sym.offset < 0x8000:
                continue
            eligible.append(sym)

        if not eligible:
            return

        anchor_banks = {
            s.bank
            for s in eligible
            if s.name in ("GSU_Code", "GSU_Entry")
            or s.name.endswith("_function")
            or s.name.endswith("_func")
        }
        if anchor_banks:
            filtered = [s for s in eligible if s.bank in anchor_banks]
        else:
            # Without explicit GSU function/entry markers, restrict strictly to Bank $70 GSURAM.
            filtered = [s for s in eligible if s.bank == 0x70]

        if not filtered:
            return

        filtered.sort(
            key=lambda s: (
                s.offset,
                0 if s.name.endswith("_function") or s.name == "GSU_Code" else 1,
                s.name.startswith("@"),
            )
        )

        func_anchors: List[Tuple[int, str]] = []
        seen_func_offsets: Set[int] = set()
        for sym in filtered:
            if sym.name.endswith("_function"):
                clean_name = sym.name[: -len("_function")]
            elif sym.name in ("GSU_Code", "GSU_Entry", "main"):
                clean_name = sym.name
            else:
                continue
            if sym.offset not in seen_func_offsets:
                seen_func_offsets.add(sym.offset)
                func_anchors.append((sym.offset, clean_name))
                self.function_entries[clean_name] = sym.offset

        if not func_anchors:
            for sym in filtered:
                if not sym.name.startswith("@") and sym.offset not in seen_func_offsets:
                    seen_func_offsets.add(sym.offset)
                    func_anchors.append((sym.offset, sym.name))
                    self.function_entries[sym.name] = sym.offset

        func_anchors.sort(key=lambda item: item[0])
        for i, (start, func_name) in enumerate(func_anchors):
            end = (
                func_anchors[i + 1][0]
                if i + 1 < len(func_anchors)
                else min(0x10000, start + 0x1000)
            )
            if end > start:
                self.function_spans.append((start, end, func_name))

        dedup_labels: List[Tuple[int, str]] = []
        seen_label_offsets: Set[int] = set()
        for sym in filtered:
            if sym.offset not in seen_label_offsets:
                seen_label_offsets.add(sym.offset)
                lbl = (
                    sym.name[: -len("_function")]
                    if sym.name.endswith("_function")
                    else sym.name
                )
                dedup_labels.append((sym.offset, lbl))

        dedup_labels.sort(key=lambda item: item[0])
        for i, (start, label_name) in enumerate(dedup_labels):
            end = (
                dedup_labels[i + 1][0]
                if i + 1 < len(dedup_labels)
                else min(0x10000, start + 0x0400)
            )
            if end > start:
                enclosing = self.resolve_function(start)
                self.label_spans.append((start, end, label_name, enclosing))

    def resolve_function(self, addr_or_pc: int) -> str:
        """Resolve a GSU PC offset to its enclosing function name."""
        pc = addr_or_pc & 0xFFFF
        for start, end, func_name in self.function_spans:
            if start <= pc < end:
                return func_name
        return f"0x{pc:04X}"

    def resolve_label(self, addr_or_pc: int) -> Tuple[str, str]:
        """Resolve a GSU PC offset to (label_name, enclosing_function)."""
        pc = addr_or_pc & 0xFFFF
        for start, end, label_name, enclosing_func in self.label_spans:
            if start <= pc < end:
                return (label_name, enclosing_func)
        func_name = self.resolve_function(pc)
        return (func_name, func_name)

    def resolve_pc(self, addr_or_pc: int) -> PcSpanMatch:
        """Resolve a GSU PC offset to a structured PcSpanMatch."""
        pc = addr_or_pc & 0xFFFF
        lbl, fn = self.resolve_label(pc)
        return PcSpanMatch(pc=pc, function=fn, label=lbl)

    def resolve_symbol(
        self,
        raw_name: str,
        *,
        allow_output_aliases: bool = True,
        allow_function_suffix: bool = True,
        after_addr: Optional[int] = None,
        prefer_region: Optional[str] = None,
    ) -> Optional[ResolvedSymbol]:
        """Unified symbol lookup supporting leading '.', _function/_func, scope::, OUTPUT aliases, and @local after_addr."""
        clean_name = raw_name.strip().lstrip(".")
        if not clean_name:
            return None

        primary_candidates: List[str] = []

        def _add_candidate(cand: str) -> None:
            if cand and cand not in primary_candidates:
                primary_candidates.append(cand)

        _add_candidate(clean_name)
        if allow_function_suffix:
            if not clean_name.endswith("_function"):
                _add_candidate(f"{clean_name}_function")
            if not clean_name.endswith("_func"):
                _add_candidate(f"{clean_name}_func")

        if "::" in clean_name:
            tail = clean_name.split("::")[-1].lstrip(".")
            _add_candidate(tail)
            if allow_function_suffix:
                if not tail.endswith("_function"):
                    _add_candidate(f"{tail}_function")
                if not tail.endswith("_func"):
                    _add_candidate(f"{tail}_func")

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

        def _search_candidates(cands: Sequence[str]) -> Optional[ResolvedSymbol]:
            for cand in cands:
                exact = [s for s in self.symbols if s.name == cand]
                picked = _pick_best(exact)
                if picked is not None:
                    return picked
            for cand in cands:
                cand_lower = cand.lower()
                ci = [s for s in self.symbols if s.name.lower() == cand_lower]
                picked = _pick_best(ci)
                if picked is not None:
                    return picked
            return None

        matched = _search_candidates(primary_candidates)
        if matched is not None:
            return matched

        if allow_output_aliases and clean_name.upper() == "OUTPUT":
            return _search_candidates(OUTPUT_ALIASES)

        return None

    def suggest_symbols(self, raw_name: str, limit: int = 5) -> List[str]:
        """Return up to `limit` close or substring symbol name suggestions."""
        clean_name = raw_name.strip().lstrip(".")
        seen: Set[str] = set()
        all_names: List[str] = []
        for s in self.symbols:
            if s.name not in seen:
                seen.add(s.name)
                all_names.append(s.name)

        close = list(difflib.get_close_matches(clean_name, all_names, n=limit, cutoff=0.3))
        if len(close) < limit and clean_name:
            needle = clean_name.lower()
            for name in all_names:
                if needle in name.lower() and name not in close:
                    close.append(name)
                    if len(close) >= limit:
                        break
        return close

    def resolve_gsu_target(self, key: str, *, role: str = "symbol") -> GsuTarget:
        """Resolve 'r0'-'r15' or 'SYM +/- offset' into a validated 24-bit Bank $70/$71 GsuTarget."""
        key_clean = key.strip()
        if not key_clean:
            raise ValueError(f"Empty {role} key")

        reg_match = _REGISTER_KEY_PATTERN.match(key_clean)
        if reg_match:
            reg_num = int(reg_match.group(1))
            if 0 <= reg_num <= 15:
                reg_name = f"r{reg_num}"
                return GsuTarget(
                    key=key,
                    base_name=reg_name,
                    offset_delta=0,
                    register=reg_name,
                    size=2,
                )
            # Fall through only if an actual symbol with that name exists; otherwise report invalid GSU register.
            if self.resolve_symbol(key_clean, allow_output_aliases=False) is None:
                raise ValueError(
                    f"Invalid GSU register '{key_clean}': SuperFX supports only r0-r15."
                )

        sym_match = _SYMBOL_OFFSET_KEY_PATTERN.match(key_clean)
        if not sym_match:
            raise ValueError(f"Invalid {role} target syntax: '{key}'")

        base_name, sign, offset_str = sym_match.groups()
        offset_delta = 0
        if offset_str:
            if offset_str.startswith("$"):
                offset_delta = int(offset_str[1:], 16)
            elif offset_str.startswith(("0x", "0X")):
                offset_delta = int(offset_str, 16)
            else:
                offset_delta = int(offset_str)
            if sign == "-":
                offset_delta = -offset_delta

        sym = self.resolve_symbol(base_name, allow_output_aliases=True)
        if sym is None:
            raise ValueError(f"Unresolved {role}: '{base_name}' in '{key}'")

        if sym.bank not in (0x70, 0x71):
            raise ValueError(
                f"Symbol '{sym.name}' at 0x{sym.val:06X} (bank ${sym.bank:02X}, region {sym.region}) "
                f"cannot be used as a GSURAM {role} in '{key}': emu.memType.gsuWorkRam only maps "
                f"Game Pak RAM Bank $70 ($70:0000-$70:FFFF) and Staging Bank $71 ($71:0000-$71:FFFF)."
            )

        full_addr = sym.val + offset_delta
        if not (0x700000 <= full_addr <= 0x71FFFF):
            raise ValueError(
                f"GSURAM {role} '{key}' resolves to 0x{full_addr:06X}, outside $70:0000-$71:FFFF."
            )

        target_bank = (full_addr >> 16) & 0xFF
        target_offset = full_addr & 0xFFFF
        work_ram_addr = ((target_bank - 0x70) << 16) | target_offset
        est_size = sym.size if (sym.size is not None and sym.size > 0) else 2

        return GsuTarget(
            key=key,
            base_name=base_name,
            offset_delta=offset_delta,
            register=None,
            symbol=sym,
            full_address=full_addr,
            work_ram_addr=work_ram_addr,
            size=est_size,
        )

    @staticmethod
    def compute_cache_span(
        start_sym: ResolvedSymbol,
        end_addr_or_val: Union[str, int],
        *,
        inherit_bank: bool = True,
    ) -> CacheSpanMetrics:
        """Compute byte_span and 512-byte GSU cache residency, inheriting start_sym.bank for 16-bit end addresses."""
        has_explicit_bank = isinstance(end_addr_or_val, str) and ":" in end_addr_or_val
        resolved_end = parse_address(end_addr_or_val)
        if (
            inherit_bank
            and not has_explicit_bank
            and resolved_end < 0x10000
            and start_sym.bank != 0
        ):
            resolved_end = (start_sym.bank << 16) | resolved_end

        byte_span = resolved_end - start_sym.val
        cbr_base = start_sym.val & ~0xF
        aligned_cache_bytes = resolved_end - cbr_base
        cache_lines_16b = (
            (((resolved_end + 15) & ~0xF) - cbr_base) // 16
            if resolved_end >= start_sym.val
            else 0
        )
        return CacheSpanMetrics(
            start_address=start_sym.val,
            end_address=resolved_end,
            byte_span=byte_span,
            aligned_cache_bytes=aligned_cache_bytes,
            cache_lines_16b=cache_lines_16b,
            fits_in_512b_cache=bool(0 <= aligned_cache_bytes <= 512),
        )

    def resolve_source_location(self, addr_val: int) -> Optional[Dict[str, Any]]:
        """Resolve an address to source file, 1-based lineNumber, and optional snippet using retained DbgData."""
        if self.dbg_data is None:
            return None

        candidates = [addr_val]
        if addr_val < 0x10000:
            gsu_seg = next(
                (
                    s
                    for s in self.dbg_data.segments.values()
                    if s.name.upper().startswith("GSUCODE")
                ),
                None,
            )
            if gsu_seg is not None:
                bank = (gsu_seg.start >> 16) & 0xFF
                candidates.insert(0, (bank << 16) | addr_val)

        for cand_addr in candidates:
            for seg in self.dbg_data.segments.values():
                if seg.start <= cand_addr < seg.start + seg.size:
                    offset_in_seg = cand_addr - seg.start
                    matching_spans = [
                        sp
                        for sp in self.dbg_data.spans.values()
                        if sp.seg == seg.id
                        and sp.start <= offset_in_seg < sp.start + sp.size
                    ]
                    if not matching_spans:
                        continue

                    candidate_lines: List[Tuple[str, int, int]] = []
                    for sp in matching_spans:
                        for line_rec in self.dbg_data.lines.values():
                            if sp.id in line_rec.spans:
                                file_rec = self.dbg_data.files.get(line_rec.file)
                                if file_rec and file_rec.name:
                                    candidate_lines.append(
                                        (file_rec.name, line_rec.line, sp.size)
                                    )

                    if not candidate_lines:
                        continue

                    def _file_priority(item: Tuple[str, int, int]) -> Tuple[int, int]:
                        fname, _lno, span_sz = item
                        is_lib_macro = "GSU_Assembler.i" in fname
                        is_proj = fname.endswith((".sgs", ".s", ".i")) and not is_lib_macro
                        return (0 if is_proj else 1, span_sz)

                    candidate_lines.sort(key=_file_priority)
                    file_name, line_num, _ = candidate_lines[0]

                    snippet: List[str] = []
                    if self.project_dir is not None:
                        resolved_file = self.project_dir / file_name
                        if not resolved_file.is_file():
                            resolved_file = (self.project_dir / file_name).resolve()
                        if resolved_file.is_file():
                            try:
                                all_lines = resolved_file.read_text(
                                    encoding="utf-8", errors="replace"
                                ).splitlines()
                                start_l = max(0, line_num - 3)
                                end_l = min(len(all_lines), line_num + 3)
                                for idx in range(start_l, end_l):
                                    marker = "==> " if (idx + 1) == line_num else "    "
                                    snippet.append(
                                        f"{marker}{idx + 1:5d} | {all_lines[idx].rstrip()}"
                                    )
                            except OSError:
                                pass

                    res: Dict[str, Any] = {
                        "path": file_name,
                        "lineNumber": line_num,
                    }
                    if snippet:
                        res["context"] = "\n".join(snippet)
                    return res

        return None

    def check_stale_build(
        self, extra_artifacts: Sequence[Path] = ()
    ) -> Optional[str]:
        """Return a warning message if any build artifact is older than the newest source/asset file."""
        if self.project_dir is None or not self.project_dir.is_dir():
            return None

        artifacts_to_check: List[Path] = []
        primary = self.dbg_file or self.sym_file
        if primary is not None:
            artifacts_to_check.append(primary)
        for extra in extra_artifacts:
            if extra not in artifacts_to_check:
                artifacts_to_check.append(extra)

        if not artifacts_to_check:
            return None

        newest_mtime = 0.0
        newest_src: Optional[Path] = None

        for root, dirs, files in os.walk(self.project_dir):
            dirs[:] = [d for d in dirs if d not in (".git", ".build", "__pycache__")]
            for fname in files:
                p = Path(root) / fname
                if (
                    p.suffix.lower() in _STALE_SOURCE_EXTENSIONS
                    or fname.lower() in _STALE_SOURCE_FILENAMES
                ):
                    try:
                        mtime = p.stat().st_mtime
                        if mtime > newest_mtime:
                            newest_mtime = mtime
                            newest_src = p
                    except OSError:
                        pass

        if newest_src is None:
            return None

        for art in artifacts_to_check:
            if art.is_file():
                try:
                    if art.stat().st_mtime < newest_mtime:
                        return (
                            f"Build artifact '{art.name}' is older than source file "
                            f"'{newest_src.name}' (stale build; recompile with make or run-sfx-test.sh)"
                        )
                except OSError:
                    pass

        return None

    def sync_lua_source(self, lua_source: str) -> str:
        """Synchronize numeric variable assignments in Lua test scripts with 24-bit Bank $70/$71 offsets."""
        lines = lua_source.splitlines()
        updated_lines: List[str] = []

        for line in lines:
            m = _LUA_VAR_ASSIGN_PATTERN.match(line)
            if not m:
                updated_lines.append(line)
                continue

            prefix, var_name, current_val_str, suffix = m.groups()
            if var_name.lower() in _LUA_SYNC_SKIP_VARS:
                updated_lines.append(line)
                continue

            sym = self.resolve_symbol(
                var_name,
                allow_output_aliases=True,
                allow_function_suffix=False,
            )
            if sym is None:
                updated_lines.append(line)
                continue

            target_offset = (
                sym.work_ram_offset
                if sym.work_ram_offset is not None
                else sym.offset
            )
            target_hex = f"0x{target_offset:04X}"
            try:
                current_val = (
                    int(current_val_str, 16)
                    if current_val_str.startswith(("0x", "0X"))
                    else int(current_val_str)
                )
            except ValueError:
                current_val = None

            if current_val != target_offset:
                updated_lines.append(f"{prefix}{target_hex}{suffix}")
            else:
                updated_lines.append(line)

        return ("\n".join(updated_lines) + "\n") if lines else ""
