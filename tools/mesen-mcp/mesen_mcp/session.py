"""Unified execution and symbol resolution pipeline for SuperFX tests in Mesen."""

from dataclasses import dataclass, field
import os
from pathlib import Path
import re
import subprocess
from typing import Dict, List, Optional, Protocol, Tuple

from mesen_mcp.dbg_parser import DbgData, parse_dbg_file
from mesen_mcp.mesen import find_mesen
from mesen_mcp.utils import resolve_test_dir


class MesenRunner(Protocol):
    """Protocol for executing headless Mesen test runs."""

    def run(
        self, mesen_bin: str, args: List[str], timeout: int
    ) -> subprocess.CompletedProcess:
        """Run Mesen command with arguments and timeout."""
        ...


class SubprocessMesenRunner:
    """Default runner executing Mesen via subprocess."""

    def run(
        self, mesen_bin: str, args: List[str], timeout: int
    ) -> subprocess.CompletedProcess:
        cmd = [mesen_bin] + args
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


class FakeMesenRunner:
    """Test fake for executing Mesen in memory."""

    def __init__(
        self, stdout: str = "", stderr: str = "", returncode: int = 0
    ):
        self.stdout = stdout
        self.stderr = stderr
        self.returncode = returncode
        self.last_cmd: List[str] = []

    def run(
        self, mesen_bin: str, args: List[str], timeout: int
    ) -> subprocess.CompletedProcess:
        self.last_cmd = [mesen_bin] + args
        return subprocess.CompletedProcess(
            args=self.last_cmd,
            returncode=self.returncode,
            stdout=self.stdout,
            stderr=self.stderr,
        )


@dataclass
class SymbolTable:
    """Encapsulates symbol address mapping, aliases, and size estimations."""

    symbols: Dict[str, int] = field(default_factory=dict)
    sizes: Dict[str, int] = field(default_factory=dict)
    label_symbols: Dict[str, int] = field(default_factory=dict)
    function_spans: List[Tuple[int, int, str]] = field(default_factory=list)
    label_spans: List[Tuple[int, int, str, str]] = field(default_factory=list)
    function_entries: Dict[str, int] = field(default_factory=dict)

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

    def __post_init__(self) -> None:
        if not self.label_symbols and self.symbols:
            self.label_symbols = dict(self.symbols)
        if not self.function_spans and self.label_symbols:
            self._build_spans_from_symbol_dict(self.label_symbols)
        elif self.function_spans and not self.function_entries:
            for start, _end, func_name in self.function_spans:
                if func_name not in self.function_entries:
                    self.function_entries[func_name] = start

    def _build_spans_from_symbol_dict(self, sym_dict: Dict[str, int]) -> None:
        """Builds fallback function and label intervals from a flat symbol->offset map."""
        filtered = [
            (name, offset & 0xFFFF)
            for name, offset in sym_dict.items()
            if "LOCAL-MACRO" not in name and not name.startswith("__")
        ]
        if not filtered:
            return
        filtered.sort(key=lambda item: (item[1], item[0].startswith("@")))

        # Identify function-level anchors (*_function or GSU_Code)
        func_anchors: List[Tuple[int, str]] = []
        seen_func_offsets = set()
        for name, offset in filtered:
            if name.endswith("_function"):
                clean_name = name[: -len("_function")]
            elif name in ("GSU_Code", "GSU_Entry", "main"):
                clean_name = name
            else:
                continue
            if offset not in seen_func_offsets:
                seen_func_offsets.add(offset)
                func_anchors.append((offset, clean_name))
                self.function_entries[clean_name] = offset

        if not func_anchors:
            # Fallback: treat all non-local labels as function anchors
            for name, offset in filtered:
                if not name.startswith("@") and offset not in seen_func_offsets:
                    seen_func_offsets.add(offset)
                    func_anchors.append((offset, name))
                    self.function_entries[name] = offset

        func_anchors.sort(key=lambda item: item[0])
        for i, (start, func_name) in enumerate(func_anchors):
            end = (
                func_anchors[i + 1][0]
                if i + 1 < len(func_anchors)
                else min(0x10000, start + 0x1000)
            )
            if end > start:
                self.function_spans.append((start, end, func_name))

        # Build label spans
        dedup_labels: List[Tuple[int, str]] = []
        seen_label_offsets = set()
        for name, offset in filtered:
            if offset not in seen_label_offsets:
                seen_label_offsets.add(offset)
                dedup_labels.append((offset, name))

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

    def resolve_function(self, offset: int) -> str:
        """Resolves a 16-bit GSU PC offset to its enclosing function name."""
        pc = offset & 0xFFFF
        for start, end, func_name in self.function_spans:
            if start <= pc < end:
                return func_name
        return f"0x{pc:04X}"

    def resolve_label(self, offset: int) -> Tuple[str, str]:
        """Resolves a 16-bit GSU PC offset to (label_name, enclosing_function)."""
        pc = offset & 0xFFFF
        for start, end, label_name, enclosing_func in self.label_spans:
            if start <= pc < end:
                return (label_name, enclosing_func)
        func_name = self.resolve_function(pc)
        return (func_name, func_name)

    def get_offset(self, name: str) -> Optional[int]:
        """Resolves symbol offset with case-insensitive fallback and OUTPUT aliases."""
        if name in self.symbols:
            return self.symbols[name]
        if name.upper() in self.symbols:
            return self.symbols[name.upper()]
        if name.lower() in self.symbols:
            return self.symbols[name.lower()]

        if name.upper() == "OUTPUT":
            for candidate in self.OUTPUT_ALIASES:
                if candidate in self.symbols:
                    return self.symbols[candidate]

        return None

    def get_size(self, name: str, default: int = 2) -> int:
        """Returns the estimated byte size of a symbol."""
        if name in self.sizes:
            return self.sizes[name]
        if name.upper() in self.sizes:
            return self.sizes[name.upper()]
        if name.lower() in self.sizes:
            return self.sizes[name.lower()]
        return default

    @classmethod
    def load(cls, test_dir: Path) -> "SymbolTable":
        """Loads symbols from Test.dbg and Test.cpu.sym (merging both for complete coverage)."""
        symbols_map: Dict[str, int] = {}
        dbg_data: Optional[DbgData] = None

        dbg_file = test_dir / "Test.dbg"
        if not dbg_file.is_file():
            dbg_candidates = list(test_dir.glob("*.dbg"))
            if dbg_candidates:
                dbg_file = dbg_candidates[0]

        if dbg_file.is_file():
            try:
                dbg_data = parse_dbg_file(dbg_file)
                label_map: Dict[str, int] = {}
                for name, sym in dbg_data.symbols.items():
                    symbols_map[name] = sym.offset
                    if sym.type == "lab":
                        label_map[name] = sym.offset
            except Exception:
                label_map = {}
        else:
            label_map = {}

        sym_file = test_dir / "Test.cpu.sym"
        if not sym_file.is_file():
            sym_candidates = list(test_dir.glob("*.cpu.sym"))
            if sym_candidates:
                sym_file = sym_candidates[0]

        if sym_file.is_file():
            sym_pattern = re.compile(
                r"^al\s+([0-9A-Fa-f]{6})\s+\.([A-Za-z0-9_@]+)"
            )
            with open(sym_file, "r", encoding="utf-8") as f:
                for line in f:
                    m = sym_pattern.match(line.strip())
                    if m:
                        full_val = int(m.group(1), 16)
                        offset = full_val & 0xFFFF
                        name = m.group(2)
                        symbols_map[name] = offset
                        if label_map:
                            label_map[name] = offset

        sizes = cls._estimate_symbol_sizes(dbg_data)
        func_spans, lbl_spans, func_entries = cls._build_dbg_spans(dbg_data)
        return cls(
            symbols=symbols_map,
            sizes=sizes,
            label_symbols=label_map if label_map else symbols_map,
            function_spans=func_spans,
            label_spans=lbl_spans,
            function_entries=func_entries,
        )

    @staticmethod
    def _build_dbg_spans(
        dbg_data: Optional[DbgData],
    ) -> Tuple[List[Tuple[int, int, str]], List[Tuple[int, int, str, str]], Dict[str, int]]:
        func_spans: List[Tuple[int, int, str]] = []
        label_spans: List[Tuple[int, int, str, str]] = []
        func_entries: Dict[str, int] = {}
        if not dbg_data:
            return func_spans, label_spans, func_entries

        gsu_seg = next(
            (s for s in dbg_data.segments.values() if s.name == "GSUCODE"),
            None,
        )
        if gsu_seg is None:
            return func_spans, label_spans, func_entries

        seg_start = gsu_seg.start & 0xFFFF
        seg_end = min(0x10000, seg_start + max(gsu_seg.size, 1))

        gsu_syms = [
            sym
            for sym in dbg_data.all_symbols
            if sym.seg == gsu_seg.id
            and sym.type == "lab"
            and "LOCAL-MACRO" not in sym.name
            and not sym.name.startswith("__")
        ]
        if not gsu_syms:
            return func_spans, label_spans, func_entries

        gsu_syms.sort(
            key=lambda s: (
                s.offset,
                0 if s.name.endswith("_function") or s.name == "GSU_Code" else 1,
                s.name.startswith("@"),
            )
        )

        # Build function-level spans from *_function scopes and GSU_Code entry
        func_anchors: List[Tuple[int, str, Optional[int]]] = []
        seen_func_offsets = set()
        for sym in gsu_syms:
            if sym.name.endswith("_function"):
                clean_name = sym.name[: -len("_function")]
                sc = dbg_data.get_scope(clean_name)
                sc_size = sc.size if (sc and sc.size > 0) else None
                if sym.offset not in seen_func_offsets:
                    seen_func_offsets.add(sym.offset)
                    func_anchors.append((sym.offset, clean_name, sc_size))
                    func_entries[clean_name] = sym.offset
            elif sym.name in ("GSU_Code", "GSU_Entry", "main"):
                if sym.offset not in seen_func_offsets:
                    seen_func_offsets.add(sym.offset)
                    func_anchors.append((sym.offset, sym.name, None))
                    func_entries[sym.name] = sym.offset

        func_anchors.sort(key=lambda item: item[0])
        for i, (start, func_name, sc_size) in enumerate(func_anchors):
            next_start = func_anchors[i + 1][0] if i + 1 < len(func_anchors) else seg_end
            end = min(next_start, start + sc_size) if sc_size else next_start
            if end > start:
                func_spans.append((start, end, func_name))

        def _resolve_func_for_offset(pc: int) -> str:
            for f_start, f_end, f_name in func_spans:
                if f_start <= pc < f_end:
                    return f_name
            return "GSU_Code"

        # Build fine-grained label spans within GSUCODE
        dedup_syms = []
        seen_lbl_offsets = set()
        for sym in gsu_syms:
            if sym.offset not in seen_lbl_offsets:
                seen_lbl_offsets.add(sym.offset)
                dedup_syms.append(sym)

        for i, sym in enumerate(dedup_syms):
            start = sym.offset
            end = dedup_syms[i + 1].offset if i + 1 < len(dedup_syms) else seg_end
            if end <= start:
                continue
            sc = dbg_data.scopes.get(sym.scope)
            enclosing_func = (
                sc.name if (sc and sc.name) else _resolve_func_for_offset(start)
            )
            label_name = sym.name
            if label_name.endswith("_function"):
                label_name = label_name[: -len("_function")]
            label_spans.append((start, end, label_name, enclosing_func))

        return func_spans, label_spans, func_entries

    @staticmethod
    def _estimate_symbol_sizes(dbg_data: Optional[DbgData]) -> Dict[str, int]:
        size_map: Dict[str, int] = {}
        if not dbg_data:
            return size_map

        by_seg: Dict[int, list] = {}
        for sym in dbg_data.all_symbols:
            if sym.seg is not None:
                by_seg.setdefault(sym.seg, []).append(sym)

        for seg_id, sym_list in by_seg.items():
            sorted_syms = sorted(sym_list, key=lambda s: s.val)
            for i, sym in enumerate(sorted_syms):
                next_val = None
                for j in range(i + 1, len(sorted_syms)):
                    if sorted_syms[j].val > sym.val:
                        next_val = sorted_syms[j].val
                        break
                if next_val is not None:
                    diff = next_val - sym.val
                    size_map[sym.name] = diff
                    size_map[sym.name.upper()] = diff
                    size_map[sym.name.lower()] = diff

        return size_map


class SfxTestSession:
    """Manages the build, symbol resolution, and headless execution lifecycle for a SuperFX test."""

    def __init__(
        self,
        test_dir: Path,
        test_name: str,
        runner: Optional[MesenRunner] = None,
        mesen_path: Optional[str] = None,
    ):
        self.test_dir = test_dir
        self.test_name = test_name
        self.runner = runner or SubprocessMesenRunner()
        self.mesen_path = mesen_path
        self._symbols: Optional[SymbolTable] = None
        self._sfc_file: Optional[Path] = None

    @classmethod
    def from_test_name(
        cls,
        test_name: str,
        runner: Optional[MesenRunner] = None,
        mesen_path: Optional[str] = None,
    ) -> "SfxTestSession":
        test_dir = resolve_test_dir(test_name)
        return cls(
            test_dir=test_dir,
            test_name=test_name,
            runner=runner,
            mesen_path=mesen_path,
        )

    def compile(self, force: bool = False, skip: bool = False) -> None:
        """Compiles the test ROM using make clean all in test_dir."""
        if skip or (not isinstance(self.runner, SubprocessMesenRunner) and not force):
            return

        res = subprocess.run(
            ["make", "-C", str(self.test_dir), "clean", "all"],
            capture_output=True,
            text=True,
        )
        if res.returncode != 0:
            raise RuntimeError(
                f"Compilation failed (exit code {res.returncode}):\n{res.stdout}\n{res.stderr}"
            )

    @property
    def symbols(self) -> SymbolTable:
        """Lazily loads and returns the session's symbol table."""
        if self._symbols is None:
            self._symbols = SymbolTable.load(self.test_dir)
        return self._symbols

    @property
    def rom_path(self) -> Path:
        """Resolves the compiled .sfc ROM file in test_dir."""
        if self._sfc_file is None:
            for preferred in ("Test.sfc", "SuperFX.sfc"):
                cand = self.test_dir / preferred
                if cand.is_file():
                    self._sfc_file = cand
                    break
            if self._sfc_file is None:
                candidates = list(self.test_dir.glob("*.sfc"))
                if candidates:
                    self._sfc_file = candidates[0]
                else:
                    raise FileNotFoundError(
                        f"No .sfc ROM file found in {self.test_dir}"
                    )
        return self._sfc_file

    def sync_lua_script(self, lua_path: Path) -> None:
        """Synchronizes variable assignments in a Lua script with resolved symbol offsets."""
        if not lua_path.is_file():
            return

        content = lua_path.read_text(encoding="utf-8")
        var_pattern = re.compile(
            r"^(\s*(?:local\s+)?([A-Za-z0-9_]+)\s*=\s*)(0x[0-9A-Fa-f]+|\d+)(.*)$"
        )
        lines = content.splitlines()
        updated_lines = []

        for line in lines:
            m = var_pattern.match(line)
            if m:
                prefix, var_name, current_val_str, suffix = m.groups()
                if var_name.lower() in ("index", "fail_count", "i", "j", "k"):
                    updated_lines.append(line)
                    continue
                target_offset = self.symbols.get_offset(var_name)

                if target_offset is not None:
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
                else:
                    updated_lines.append(line)
            else:
                updated_lines.append(line)

        lua_path.write_text("\n".join(updated_lines) + "\n", encoding="utf-8")

    def run_lua(
        self, lua_script_path: Path, timeout_seconds: int = 15
    ) -> subprocess.CompletedProcess:
        """Executes Mesen with the given Lua script against the session's ROM."""
        if not isinstance(self.runner, SubprocessMesenRunner):
            mesen_bin = Path(self.mesen_path or "mesen")
        else:
            mesen_bin = find_mesen(override_path=self.mesen_path)

        return self.runner.run(
            str(mesen_bin),
            [
                "--testrunner",
                f"--timeout={timeout_seconds}",
                str(lua_script_path),
                str(self.rom_path),
            ],
            timeout=timeout_seconds + 5,
        )
