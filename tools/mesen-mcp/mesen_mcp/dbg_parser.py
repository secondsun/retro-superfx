from dataclasses import dataclass, field
import os
from pathlib import Path
import re
from typing import Dict, List, Optional, Union


@dataclass
class SourceFile:
    id: int
    name: str
    size: int = 0
    mod: str = ""


@dataclass
class Segment:
    id: int
    name: str
    start: int = 0
    size: int = 0
    addrsize: str = ""
    type: str = ""
    oname: Optional[str] = None
    ooffs: Optional[int] = None


@dataclass
class Span:
    id: int
    seg: int
    start: int
    size: int
    type: int = 0


@dataclass
class Scope:
    id: int
    name: str
    mod: int = 0
    size: int = 0
    parent: Optional[int] = None
    spans: List[int] = field(default_factory=list)


@dataclass
class Symbol:
    id: int
    name: str
    val: int
    scope: int
    addrsize: str = ""
    type: str = ""
    seg: Optional[int] = None
    def_lines: List[int] = field(default_factory=list)
    ref_lines: List[int] = field(default_factory=list)

    @property
    def offset(self) -> int:
        """16-bit offset within bank."""
        return self.val & 0xFFFF

    @property
    def bank(self) -> int:
        """8-bit bank index."""
        return (self.val >> 16) & 0xFF

    @property
    def is_word_aligned(self) -> bool:
        """Returns True if the 16-bit address is even."""
        return (self.offset % 2) == 0


@dataclass
class DbgData:
    version: str = "2.0"
    files: Dict[int, SourceFile] = field(default_factory=dict)
    segments: Dict[int, Segment] = field(default_factory=dict)
    scopes: Dict[int, Scope] = field(default_factory=dict)
    spans: Dict[int, Span] = field(default_factory=dict)
    symbols: Dict[str, Symbol] = field(default_factory=dict)
    symbols_by_id: Dict[int, Symbol] = field(default_factory=dict)
    all_symbols: List[Symbol] = field(default_factory=list)

    def get_symbol(self, name: str) -> Optional[Symbol]:
        return self.symbols.get(name)

    def get_symbol_address(self, name: str) -> Optional[int]:
        sym = self.get_symbol(name)
        return sym.val if sym else None

    def get_symbol_offset(self, name: str) -> Optional[int]:
        sym = self.get_symbol(name)
        return sym.offset if sym else None

    def get_symbol_bank(self, name: str) -> Optional[int]:
        sym = self.get_symbol(name)
        return sym.bank if sym else None

    def get_scope(self, name_or_id: Union[str, int]) -> Optional[Scope]:
        if isinstance(name_or_id, int):
            return self.scopes.get(name_or_id)
        for s in self.scopes.values():
            if s.name == name_or_id:
                return s
        return None

    def get_symbols_in_scope(self, scope_id: int) -> List[Symbol]:
        return [sym for sym in self.all_symbols if sym.scope == scope_id]


_FIELD_PATTERN = re.compile(r'([a-zA-Z0-9_]+)=(?:"([^"]*)"|([^,]*))')


def _parse_int(value: str, default: int = 0) -> int:
    if not value:
        return default
    value = value.strip()
    try:
        if value.startswith(("0x", "0X")):
            return int(value, 16)
        return int(value)
    except ValueError:
        return default


def _parse_int_list(value: str) -> List[int]:
    if not value:
        return []
    parts = value.replace("+", " ").split()
    res = []
    for p in parts:
        try:
            res.append(_parse_int(p))
        except ValueError:
            pass
    return res


def parse_dbg_string(content: str) -> DbgData:
    """Parse ld65 .dbg format content into DbgData."""
    data = DbgData()

    for line in content.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue

        parts = line.split("\t", 1)
        if len(parts) == 1:
            parts = line.split(" ", 1)
        if len(parts) < 2:
            continue

        rec_type, fields_raw = parts[0].strip(), parts[1].strip()
        fields: Dict[str, str] = {}
        for match in _FIELD_PATTERN.finditer(fields_raw):
            k, quoted_v, raw_v = match.groups()
            fields[k] = quoted_v if quoted_v is not None else raw_v

        if rec_type == "version":
            major = fields.get("major", "2")
            minor = fields.get("minor", "0")
            data.version = f"{major}.{minor}"

        elif rec_type == "file":
            file_id = _parse_int(fields.get("id", "0"))
            data.files[file_id] = SourceFile(
                id=file_id,
                name=fields.get("name", ""),
                size=_parse_int(fields.get("size", "0")),
                mod=fields.get("mod", ""),
            )

        elif rec_type == "seg":
            seg_id = _parse_int(fields.get("id", "0"))
            data.segments[seg_id] = Segment(
                id=seg_id,
                name=fields.get("name", ""),
                start=_parse_int(fields.get("start", "0")),
                size=_parse_int(fields.get("size", "0")),
                addrsize=fields.get("addrsize", ""),
                type=fields.get("type", ""),
                oname=fields.get("oname"),
                ooffs=_parse_int(fields.get("ooffs", "")) if "ooffs" in fields else None,
            )

        elif rec_type == "span":
            span_id = _parse_int(fields.get("id", "0"))
            data.spans[span_id] = Span(
                id=span_id,
                seg=_parse_int(fields.get("seg", "0")),
                start=_parse_int(fields.get("start", "0")),
                size=_parse_int(fields.get("size", "0")),
                type=_parse_int(fields.get("type", "0")),
            )

        elif rec_type == "scope":
            scope_id = _parse_int(fields.get("id", "0"))
            parent_id = _parse_int(fields["parent"]) if "parent" in fields else None
            data.scopes[scope_id] = Scope(
                id=scope_id,
                name=fields.get("name", ""),
                mod=_parse_int(fields.get("mod", "0")),
                size=_parse_int(fields.get("size", "0")),
                parent=parent_id,
                spans=_parse_int_list(fields.get("span", "")),
            )

        elif rec_type == "sym":
            sym_id = _parse_int(fields.get("id", "0"))
            name = fields.get("name", "")
            val = _parse_int(fields.get("val", "0"))
            scope_id = _parse_int(fields.get("scope", "0"))
            seg_id = _parse_int(fields["seg"]) if "seg" in fields else None

            symbol = Symbol(
                id=sym_id,
                name=name,
                val=val,
                scope=scope_id,
                addrsize=fields.get("addrsize", ""),
                type=fields.get("type", ""),
                seg=seg_id,
                def_lines=_parse_int_list(fields.get("def", "")),
                ref_lines=_parse_int_list(fields.get("ref", "")),
            )
            data.symbols_by_id[sym_id] = symbol
            data.all_symbols.append(symbol)

            # Store in primary symbol map: prefer labels and non-zero values if collision
            if name not in data.symbols:
                data.symbols[name] = symbol
            else:
                existing = data.symbols[name]
                if existing.type != "lab" and symbol.type == "lab":
                    data.symbols[name] = symbol

    return data


def parse_dbg_file(path: Union[str, Path]) -> DbgData:
    """Read and parse a .dbg file."""
    p = Path(path).resolve()
    if not p.is_file():
        raise FileNotFoundError(f"Debug file not found: {p}")
    return parse_dbg_string(p.read_text(encoding="utf-8", errors="replace"))
