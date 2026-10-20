"""Unified execution and symbol resolution pipeline for SuperFX tests in Mesen."""

from dataclasses import dataclass, field
import os
from pathlib import Path
import re
import subprocess
from typing import Dict, List, Optional, Protocol

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

    OUTPUT_ALIASES = (
        "VECTOR_SUBTRACT_OUT",
        "VECTOR_ADD_OUT",
        "VECTOR_NEGATE_OUT",
        "VECTOR_CROSS_OUT",
        "VECTOR_TRANSFORM_OUT",
        "vector_normalize_out",
    )

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
        """Loads symbols from Test.dbg (with fallback to *.dbg and *.cpu.sym)."""
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
                for name, sym in dbg_data.symbols.items():
                    symbols_map[name] = sym.offset
            except Exception:
                pass

        if not symbols_map:
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
                            symbols_map[m.group(2)] = full_val & 0xFFFF

        sizes = cls._estimate_symbol_sizes(dbg_data)
        return cls(symbols=symbols_map, sizes=sizes)

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

    def compile(self) -> None:
        """Compiles the test ROM using make clean all in test_dir."""
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
        if self.mesen_path and not isinstance(self.runner, SubprocessMesenRunner):
            mesen_bin = Path(self.mesen_path)
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
