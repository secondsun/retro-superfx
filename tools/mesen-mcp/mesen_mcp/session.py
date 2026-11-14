"""Deep execution, symbol-index composition, and Lua harness lifecycle for SuperFX tests in Mesen."""

import contextlib
from dataclasses import dataclass, field
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from typing import Any, Callable, Dict, List, Literal, Optional, Protocol, Tuple, Union

from mesen_mcp.artifacts import (
     OUTPUT_ALIASES,
    BuildArtifactIndex,
    find_artifact_by_stem,
)
from mesen_mcp.mesen import find_mesen
from mesen_mcp.utils import resolve_test_dir

CompileMode = Literal["if_stale", "always", "skip"]


class MesenRunner(Protocol):
    """Port protocol for executing headless Mesen test runs."""

    def run(
        self, mesen_bin: str, args: List[str], timeout: int
    ) -> subprocess.CompletedProcess:
        """Run Mesen command with arguments and timeout."""
        ...


class SubprocessMesenRunner:
    """Production MesenRunner adapter executing Mesen via subprocess."""

    def run(
        self, mesen_bin: str, args: List[str], timeout: int
    ) -> subprocess.CompletedProcess:
        cmd = [mesen_bin] + args
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


class FakeMesenRunner:
    """In-memory MesenRunner test adapter capturing generated Lua scripts and simulating outputs."""

    def __init__(
        self,
        stdout: str = "",
        stderr: str = "",
        returncode: int = 0,
        on_run: Optional[
            Callable[[List[str], Optional[str]], Optional[subprocess.CompletedProcess]]
        ] = None,
    ):
        self.stdout = stdout
        self.stderr = stderr
        self.returncode = returncode
        self.on_run = on_run
        self.last_cmd: List[str] = []
        self.commands: List[List[str]] = []
        self.last_lua_code: Optional[str] = None
        self.lua_scripts: List[str] = []

    def run(
        self, mesen_bin: str, args: List[str], timeout: int
    ) -> subprocess.CompletedProcess:
        self.last_cmd = [mesen_bin] + list(args)
        self.commands.append(self.last_cmd)

        lua_code: Optional[str] = None
        for arg in args:
            if str(arg).endswith(".lua"):
                p = Path(arg)
                if p.is_file():
                    lua_code = p.read_text(encoding="utf-8", errors="replace")
                    break
        self.last_lua_code = lua_code
        if lua_code is not None:
            self.lua_scripts.append(lua_code)

        if self.on_run is not None:
            custom = self.on_run(self.last_cmd, lua_code)
            if custom is not None:
                return custom

        return subprocess.CompletedProcess(
            args=self.last_cmd,
            returncode=self.returncode,
            stdout=self.stdout,
            stderr=self.stderr,
        )


@dataclass
class SymbolTable:
    """Backward-compatible facade over BuildArtifactIndex."""

    symbols: Dict[str, int] = field(default_factory=dict)
    sizes: Dict[str, int] = field(default_factory=dict)
    label_symbols: Dict[str, int] = field(default_factory=dict)
    function_spans: List[Tuple[int, int, str]] = field(default_factory=list)
    label_spans: List[Tuple[int, int, str, str]] = field(default_factory=list)
    function_entries: Dict[str, int] = field(default_factory=dict)
    _index: Optional[BuildArtifactIndex] = field(default=None, repr=False)

    OUTPUT_ALIASES = OUTPUT_ALIASES

    def __post_init__(self) -> None:
        if self._index is None:
            self._index = BuildArtifactIndex.from_symbol_dict(
                self.symbols, sizes=self.sizes
            )
        if not self.symbols and self._index.symbols:
            for sym in self._index.symbols:
                self.symbols[sym.name] = (
                    sym.work_ram_offset
                    if sym.work_ram_offset is not None
                    else sym.offset
                )
        if not self.sizes and self._index.symbols:
            for sym in self._index.symbols:
                if sym.size is not None:
                    self.sizes[sym.name] = sym.size
        if not self.label_symbols:
            self.label_symbols = dict(self.symbols)
        if not self.function_spans:
            self.function_spans = list(self._index.function_spans)
        if not self.label_spans:
            self.label_spans = list(self._index.label_spans)
        if not self.function_entries:
            self.function_entries = dict(self._index.function_entries)

    @property
    def index(self) -> BuildArtifactIndex:
        if self._index is None:
            self._index = BuildArtifactIndex.from_symbol_dict(
                self.symbols, sizes=self.sizes
            )
        return self._index

    @classmethod
    def from_index(cls, index: BuildArtifactIndex) -> "SymbolTable":
        sym_map: Dict[str, int] = {}
        label_map: Dict[str, int] = {}
        size_map: Dict[str, int] = {}
        for sym in index.symbols:
            mapped_offset = (
                sym.work_ram_offset
                if sym.work_ram_offset is not None
                else sym.offset
            )
            sym_map[sym.name] = mapped_offset
            if sym.type == "lab":
                label_map[sym.name] = mapped_offset
            if sym.size is not None:
                size_map[sym.name] = sym.size
        return cls(
            symbols=sym_map,
            sizes=size_map,
            label_symbols=label_map if label_map else dict(sym_map),
            function_spans=list(index.function_spans),
            label_spans=list(index.label_spans),
            function_entries=dict(index.function_entries),
            _index=index,
        )

    @classmethod
    def load(cls, test_dir: Path) -> "SymbolTable":
        return cls.from_index(BuildArtifactIndex.from_dir(test_dir))

    def resolve_function(self, offset: int) -> str:
        return self.index.resolve_function(offset)

    def resolve_label(self, offset: int) -> Tuple[str, str]:
        return self.index.resolve_label(offset)

    def get_address(self, name: str) -> Optional[int]:
        sym = self.index.resolve_symbol(name, allow_output_aliases=True)
        return sym.val if sym is not None else None

    def get_offset(self, name: str) -> Optional[int]:
        sym = self.index.resolve_symbol(name, allow_output_aliases=True)
        if sym is None:
            return None
        return (
            sym.work_ram_offset
            if sym.work_ram_offset is not None
            else sym.offset
        )

    def get_size(self, name: str, default: int = 2) -> int:
        sym = self.index.resolve_symbol(name, allow_output_aliases=True)
        if sym is not None and sym.size is not None and sym.size > 0:
            return sym.size
        if name in self.sizes:
            return self.sizes[name]
        if name.upper() in self.sizes:
            return self.sizes[name.upper()]
        if name.lower() in self.sizes:
            return self.sizes[name.lower()]
        return default


@dataclass(frozen=True)
class HarnessContext:
    """Ephemeral output file paths provided to a Lua harness builder inside run_harness."""

    results_json_path: Optional[Path] = None
    output_png_path: Optional[Path] = None


@dataclass
class HarnessOutcome:
    """Normalized execution result from SfxTestSession.run_harness."""

    exit_code: int
    stdout: str
    stderr: str
    output: str
    timed_out: bool = False
    data: Optional[Dict[str, Any]] = None
    png_path: Optional[Path] = None


def _coerce_stream_text(stream: Optional[Union[str, bytes]]) -> str:
    if stream is None:
        return ""
    if isinstance(stream, bytes):
        return stream.decode("utf-8", errors="replace")
    return str(stream)


def _safe_unlink(path: Optional[Path]) -> None:
    if path is None:
        return
    try:
        if path.is_file():
            path.unlink()
    except OSError:
        pass


def _escape_lua_string(val: str) -> str:
    return val.replace("\\", "\\\\").replace('"', '\\"').replace("'", "\\'")


def _extract_harness_json(
    json_path: Optional[Path],
    combined_output: str,
    sentinel: str,
) -> Optional[Dict[str, Any]]:
    if json_path is not None and json_path.is_file():
        try:
            if json_path.stat().st_size > 0:
                return json.loads(json_path.read_text(encoding="utf-8"))
        except Exception:
            pass

    pattern = rf"__{re.escape(sentinel)}_START__(.*?)__{re.escape(sentinel)}_END__"
    match = re.search(pattern, combined_output, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(1))
        except Exception:
            pass
    return None


class SfxTestSession:
    """Deep session module owning SuperFX compilation, BuildArtifactIndex, Lua harness generation, and Mesen execution."""

    def __init__(
        self,
        test_dir: Path,
        test_name: str,
        runner: Optional[MesenRunner] = None,
        mesen_path: Optional[str] = None,
        artifacts: Optional[BuildArtifactIndex] = None,
    ):
        self.test_dir = Path(test_dir).resolve()
        self.test_name = test_name
        self.runner: MesenRunner = runner or SubprocessMesenRunner()
        self.mesen_path = mesen_path
        self._artifacts: Optional[BuildArtifactIndex] = artifacts
        self._symbols: Optional[SymbolTable] = (
            SymbolTable.from_index(artifacts) if artifacts is not None else None
        )
        self._sfc_file: Optional[Path] = None

    @classmethod
    def from_test_name(
        cls,
        test_name: str,
        runner: Optional[MesenRunner] = None,
        mesen_path: Optional[str] = None,
        artifacts: Optional[BuildArtifactIndex] = None,
    ) -> "SfxTestSession":
        test_dir = resolve_test_dir(test_name)
        return cls(
            test_dir=test_dir,
            test_name=test_name,
            runner=runner,
            mesen_path=mesen_path,
            artifacts=artifacts,
        )

    @property
    def artifacts(self) -> BuildArtifactIndex:
        """Lazily load and return the 24-bit BuildArtifactIndex for test_dir."""
        if self._artifacts is None:
            if self._symbols is not None:
                self._artifacts = self._symbols.index
            else:
                self._artifacts = BuildArtifactIndex.from_dir(self.test_dir)
        return self._artifacts

    @artifacts.setter
    def artifacts(self, value: Optional[BuildArtifactIndex]) -> None:
        self._artifacts = value
        self._symbols = SymbolTable.from_index(value) if value is not None else None

    @property
    def symbols(self) -> SymbolTable:
        """Backward-compatible SymbolTable view backed by self.artifacts."""
        if self._symbols is None:
            self._symbols = SymbolTable.from_index(self.artifacts)
        elif self._artifacts is None:
            self._artifacts = self._symbols.index
        return self._symbols

    @symbols.setter
    def symbols(self, table: Optional[SymbolTable]) -> None:
        self._symbols = table
        self._artifacts = table.index if table is not None else None

    @property
    def rom_path(self) -> Path:
        """Resolve the compiled .sfc ROM file using deterministic stem priority."""
        if self._sfc_file is None:
            found, _searched = find_artifact_by_stem(self.test_dir, ".sfc")
            if found is None:
                raise FileNotFoundError(
                    f"No .sfc ROM file found in {self.test_dir}"
                )
            self._sfc_file = found
        return self._sfc_file

    def _has_makefile(self) -> bool:
        return (self.test_dir / "Makefile").is_file() or (
            self.test_dir / "makefile"
        ).is_file()

    def needs_compile(self) -> bool:
        """Return True if a Makefile exists and .sfc/.dbg/.cpu.sym artifacts are missing or stale."""
        if not self._has_makefile():
            return False
        sfc_file, _ = find_artifact_by_stem(self.test_dir, ".sfc")
        if sfc_file is None:
            return True
        idx = BuildArtifactIndex.from_dir(self.test_dir)
        if not idx.has_artifacts:
            return True
        if idx.check_stale_build(extra_artifacts=[sfc_file]) is not None:
            return True
        return False

    def compile(
        self,
        force: bool = False,
        skip: bool = False,
        mode: CompileMode = "if_stale",
    ) -> bool:
        """Incrementally build the ROM via `make all` when stale/missing, skipping gracefully without a Makefile."""
        if skip or mode == "skip":
            return False
        if not isinstance(self.runner, SubprocessMesenRunner) and not force:
            return False
        if not self._has_makefile():
            return False
        if not force and mode == "if_stale" and not self.needs_compile():
            return False

        res = subprocess.run(
            ["make", "-C", str(self.test_dir), "all"],
            capture_output=True,
            text=True,
        )
        if res.returncode != 0:
            raise RuntimeError(
                f"Compilation failed (exit code {res.returncode}):\n{res.stdout}\n{res.stderr}"
            )
        self._artifacts = None
        self._symbols = None
        self._sfc_file = None
        return True

    def resolve_gsu_exec_addr(
        self, symbol_name: str, default_addr: Optional[int] = None
    ) -> Optional[int]:
        """Resolve a 24-bit GSU execution address (supporting both GSURAM $70:xxxx and ROM GSUCODE $03:xxxx)."""
        sym = self.artifacts.resolve_symbol(
            symbol_name, allow_output_aliases=False
        )
        if sym is not None:
            return sym.val
        return default_addr

    @staticmethod
    def emit_gsu_exec_callback(
        callback_expr: str, addr_expr: Union[int, str]
    ) -> str:
        """Emit a 24-bit emu.memType.gsuMemory execution callback statement for GSU."""
        addr_str = (
            f"0x{addr_expr:06X}" if isinstance(addr_expr, int) else addr_expr
        )
        return (
            f"emu.addMemoryCallback({callback_expr}, emu.callbackType.exec, "
            f"{addr_str}, {addr_str}, emu.cpuType.gsu, emu.memType.gsuMemory)"
        )

    def emit_lua_overrides(
        self, inputs: Dict[str, int], *, indent: str = "    "
    ) -> List[str]:
        """Emit Lua statements injecting 16/32-bit values into Bank $70/$71 GSURAM or GSU registers r0-r15."""
        lines: List[str] = []
        for key, val in inputs.items():
            target = self.artifacts.resolve_gsu_target(key, role="input symbol")
            if target.is_register:
                lines.append(
                    f'{indent}emu.setState({{ ["cart.coprocessor.{target.register}"] = {val & 0xFFFF} }})'
                )
            else:
                assert target.work_ram_addr is not None
                addr = target.work_ram_addr
                if not target.has_explicit_offset and (
                    target.size >= 4 or abs(val) > 0xFFFF
                ):
                    high_word = (val >> 16) & 0xFFFF
                    low_word = val & 0xFFFF
                    lines.append(
                        f"{indent}emu.writeWord(0x{addr:04X}, {high_word}, emu.memType.gsuWorkRam)"
                    )
                    lines.append(
                        f"{indent}emu.writeWord(0x{addr + 2:04X}, {low_word}, emu.memType.gsuWorkRam)"
                    )
                else:
                    lines.append(
                        f"{indent}emu.writeWord(0x{addr:04X}, {val & 0xFFFF}, emu.memType.gsuWorkRam)"
                    )
        return lines

    def emit_lua_expectations(
        self,
        expected_outputs: Dict[str, int],
        *,
        epsilon: int = 0,
        indent: str = "    ",
    ) -> List[str]:
        """Emit Lua statements reading Bank $70/$71 GSURAM (16/32-bit big-word, signed) or r0-r15 and checking diffs."""
        lines: List[str] = []
        for idx, (key, exp_val) in enumerate(expected_outputs.items()):
            target = self.artifacts.resolve_gsu_target(key, role="output symbol")
            ident = re.sub(r"[^A-Za-z0-9_]", "_", key.strip()) or f"out_{idx}"
            var_suffix = f"{ident}_{idx}"
            escaped_key = _escape_lua_string(key)

            if target.is_register:
                lines.append(
                    f'{indent}local actual_{var_suffix} = emu.getState()["cart.coprocessor.{target.register}"] or 0'
                )
                if exp_val < 0:
                    lines.append(
                        f"{indent}if actual_{var_suffix} >= 32768 then actual_{var_suffix} = actual_{var_suffix} - 65536 end"
                    )
            else:
                assert target.work_ram_addr is not None
                addr = target.work_ram_addr
                if not target.has_explicit_offset and (
                    target.size >= 4 or abs(exp_val) > 0xFFFF
                ):
                    lines.append(
                        f"{indent}local hi_{var_suffix} = emu.readWord(0x{addr:04X}, emu.memType.gsuWorkRam, false)"
                    )
                    lines.append(
                        f"{indent}local lo_{var_suffix} = emu.readWord(0x{addr + 2:04X}, emu.memType.gsuWorkRam, false)"
                    )
                    lines.append(
                        f"{indent}local actual_{var_suffix} = (hi_{var_suffix} * 65536) + lo_{var_suffix}"
                    )
                    if exp_val < 0:
                        lines.append(
                            f"{indent}if actual_{var_suffix} >= 2147483648 then actual_{var_suffix} = actual_{var_suffix} - 4294967296 end"
                        )
                else:
                    lines.append(
                        f"{indent}local actual_{var_suffix} = emu.readWord(0x{addr:04X}, emu.memType.gsuWorkRam, false)"
                    )
                    if exp_val < 0:
                        lines.append(
                            f"{indent}if actual_{var_suffix} >= 32768 then actual_{var_suffix} = actual_{var_suffix} - 65536 end"
                        )

            lines.append(
                f"{indent}local diff_{var_suffix} = math.abs(actual_{var_suffix} - ({exp_val}))"
            )
            lines.append(
                f"{indent}if diff_{var_suffix} > {epsilon} then all_passed = false end"
            )
            lines.append(
                f'{indent}table.insert(results_parts, string.format("\\"%s\\": %d", "{escaped_key}", actual_{var_suffix}))'
            )
            lines.append(
                f'{indent}table.insert(diffs_parts, string.format("\\"%s\\": %d", "{escaped_key}", diff_{var_suffix}))'
            )
        return lines

    def build_math_harness_lua(
        self,
        *,
        input_overrides: Dict[str, int],
        expected_outputs: Dict[str, int],
        epsilon: int,
        results_json_path: Union[str, Path],
    ) -> str:
        """Build a complete ephemeral 24-bit Lua math verification harness script."""
        test_setup_addr = self.resolve_gsu_exec_addr(
            "test_setup", default_addr=0x700003
        )
        test_stop_addr = self.resolve_gsu_exec_addr(
            "test_stop", default_addr=0x700020
        )
        assert test_setup_addr is not None and test_stop_addr is not None

        setup_lines = self.emit_lua_overrides(input_overrides, indent="    ")
        stop_lines = [
            "    local results_parts = {}",
            "    local diffs_parts = {}",
            "    local all_passed = true",
        ]
        stop_lines.extend(
            self.emit_lua_expectations(
                expected_outputs, epsilon=epsilon, indent="    "
            )
        )

        escaped_results_path = _escape_lua_string(str(results_json_path))
        stop_lines.extend(
            [
                f'    local json_str = string.format(\'{{"passed": %s, "results": {{%s}}, "diffs": {{%s}}}}\', all_passed and "true" or "false", table.concat(results_parts, ", "), table.concat(diffs_parts, ", "))',
                f'    local f = io.open("{escaped_results_path}", "w")',
                "    if f then",
                "        f:write(json_str)",
                "        f:close()",
                "    end",
                '    print("__MATH_RESULTS_JSON_START__" .. json_str .. "__MATH_RESULTS_JSON_END__")',
                "    if all_passed then",
                '        print("TEST PASSED: SFX Math Assertions Satisfied")',
                "        emu.stop(0)",
                "    else",
                '        print("TEST FAILED: SFX Math Assertions Exceeded Epsilon")',
                "        emu.stop(1)",
                "    end",
            ]
        )

        setup_cb = self.emit_gsu_exec_callback("onTestSetup", "test_setup")
        stop_cb = self.emit_gsu_exec_callback("onTestStop", "test_stop")
        setup_body = "\n".join(setup_lines)
        stop_body = "\n".join(stop_lines)

        return (
            "-- Ephemeral Mesen SFX Math Assertion Script\n"
            f"local test_setup = 0x{test_setup_addr:06X}\n"
            f"local test_stop  = 0x{test_stop_addr:06X}\n\n"
            "function onTestSetup(address, value)\n"
            f"{setup_body}\n"
            "end\n\n"
            "function onTestStop(address, value)\n"
            f"{stop_body}\n"
            "end\n\n"
            f"{setup_cb}\n"
            f"{stop_cb}\n"
        )

    def sync_lua_source(self, lua_source: str) -> str:
        """Return Lua script text with symbol offsets synchronized (without mutating disk files)."""
        return self.artifacts.sync_lua_source(lua_source)

    def sync_lua_script(
        self,
        lua_path: Path,
        *,
        output_path: Optional[Path] = None,
        persist: bool = False,
    ) -> str:
        """Synchronize symbol offsets in a Lua script; leaves `lua_path` untouched unless `persist=True`."""
        if not lua_path.is_file():
            return ""
        synced = self.sync_lua_source(
            lua_path.read_text(encoding="utf-8", errors="replace")
        )
        if output_path is not None:
            output_path.write_text(synced, encoding="utf-8")
        elif persist:
            lua_path.write_text(synced, encoding="utf-8")
        return synced

    def run_lua(
        self, lua_script_path: Path, timeout_seconds: int = 15
    ) -> subprocess.CompletedProcess:
        """Execute Mesen with the given Lua script against the session's ROM."""
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

    def run_harness(
        self,
        build_lua: Callable[["SfxTestSession", HarnessContext], str],
        *,
        timeout_seconds: int = 15,
        json_sentinel: Optional[str] = None,
        output_png_path: Optional[Union[str, Path]] = None,
        keep_temp_png_on_success: bool = False,
        compile_mode: CompileMode = "if_stale",
    ) -> HarnessOutcome:
        """Unified compile, ephemeral temp-file, dual-channel JSON, and bytes-safe timeout lifecycle."""
        self.compile(mode=compile_mode)
        _ = self.rom_path

        png_succeeded = False

        with contextlib.ExitStack() as stack:
            json_path: Optional[Path] = None
            if json_sentinel is not None:
                fd_json, raw_json = tempfile.mkstemp(
                    prefix="sfx_harness_res_", suffix=".json"
                )
                os.close(fd_json)
                json_path = Path(raw_json).resolve()
                stack.callback(_safe_unlink, json_path)

            target_png: Optional[Path] = None
            if output_png_path is not None:
                target_png = Path(output_png_path).resolve()
                target_png.parent.mkdir(parents=True, exist_ok=True)
            elif keep_temp_png_on_success:
                fd_png, raw_png = tempfile.mkstemp(
                    prefix="sfx_capture_", suffix=".png"
                )
                os.close(fd_png)
                target_png = Path(raw_png).resolve()
                stack.callback(
                    lambda p=target_png: _safe_unlink(p)
                    if not png_succeeded
                    else None
                )

            ctx = HarnessContext(
                results_json_path=json_path, output_png_path=target_png
            )
            lua_code = build_lua(self, ctx)

            fd_lua, raw_lua = tempfile.mkstemp(
                prefix="sfx_harness_", suffix=".lua"
            )
            os.close(fd_lua)
            lua_path = Path(raw_lua).resolve()
            stack.callback(_safe_unlink, lua_path)
            lua_path.write_text(lua_code, encoding="utf-8")

            try:
                proc = self.run_lua(lua_path, timeout_seconds=timeout_seconds)
                stdout_str = _coerce_stream_text(proc.stdout)
                stderr_str = _coerce_stream_text(proc.stderr)
                combined = (
                    stdout_str + ("\n" + stderr_str if stderr_str else "")
                ).strip()
                data = (
                    _extract_harness_json(json_path, combined, json_sentinel)
                    if json_sentinel is not None
                    else None
                )
                png_valid = bool(
                    target_png is not None
                    and target_png.is_file()
                    and target_png.stat().st_size > 0
                )
                if png_valid:
                    png_succeeded = True

                return HarnessOutcome(
                    exit_code=proc.returncode,
                    stdout=stdout_str,
                    stderr=stderr_str,
                    output=combined,
                    timed_out=False,
                    data=data,
                    png_path=target_png if png_valid else None,
                )
            except subprocess.TimeoutExpired as te:
                stdout_str = _coerce_stream_text(
                    te.stdout if te.stdout is not None else te.output
                )
                stderr_str = _coerce_stream_text(te.stderr)
                combined = (
                    stdout_str + ("\n" + stderr_str if stderr_str else "")
                ).strip()
                partial_data = (
                    _extract_harness_json(json_path, combined, json_sentinel)
                    if json_sentinel is not None
                    else None
                )
                return HarnessOutcome(
                    exit_code=-1,
                    stdout=stdout_str,
                    stderr=stderr_str,
                    output=combined,
                    timed_out=True,
                    data=partial_data,
                    png_path=None,
                )
