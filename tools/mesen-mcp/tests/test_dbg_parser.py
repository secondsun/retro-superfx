from mesen_mcp.dbg_parser import parse_dbg_file, parse_dbg_string


SAMPLE_DBG = """\
version\tmajor=2,minor=0
file\tid=0,name="Test.s",size=760,mtime=0x6ACEE723,mod=0
seg\tid=0,name="CODE",start=0x808000,size=0x0052,addrsize=absolute,type=ro,oname="Test.sfc",ooffs=0
scope\tid=0,name="",mod=0,size=48,span=0
scope\tid=1,name="gsu_sqrt32",mod=0,size=63,parent=0,span=1
span\tid=0,seg=0,start=0,size=2,type=0
span\tid=1,seg=0,start=2,size=4,type=1
line\tid=10,file=0,line=42,span=0+1
sym\tid=0,name="test_setup",addrsize=absolute,scope=0,def=10,val=0x700003,seg=0,type=lab
sym\tid=1,name="test_stop",addrsize=absolute,scope=0,def=20,val=0x700020,seg=0,type=lab
sym\tid=2,name="INPUT",addrsize=absolute,scope=0,def=30,val=0x700AC0,seg=0,type=lab
sym\tid=3,name="OUTPUT",addrsize=absolute,scope=0,def=40,val=0x700AC4,seg=0,type=lab
sym\tid=4,name="STAGED_TAB",addrsize=absolute,scope=0,def=10,val=0x718000,seg=0,type=lab
"""


def test_parse_dbg_string_records_and_24bit_symbols():
    data = parse_dbg_string(SAMPLE_DBG)
    assert data.version == "2.0"
    assert len(data.files) == 1
    assert data.files[0].name == "Test.s"
    assert len(data.segments) == 1
    assert data.segments[0].name == "CODE"
    assert len(data.scopes) == 2
    assert data.scopes[1].name == "gsu_sqrt32"
    assert len(data.spans) == 2
    assert data.spans[0].start == 0
    assert data.lines[10].spans == [0, 1]

    assert data.get_symbol_address("test_setup") == 0x700003
    assert data.get_symbol_offset("test_setup") == 0x0003
    assert data.get_symbol_bank("test_setup") == 0x70

    assert data.get_symbol_address("STAGED_TAB") == 0x718000
    assert data.get_symbol_bank("STAGED_TAB") == 0x71
    assert data.get_symbol_offset("STAGED_TAB") == 0x8000

    sym = data.get_symbol("test_setup")
    assert sym is not None
    assert sym.is_word_aligned is False
    assert data.get_symbol_def_location(sym) == {"path": "Test.s", "lineNumber": 42}


def test_parse_dbg_file_roundtrip(tmp_path):
    dbg_file = tmp_path / "Test.dbg"
    dbg_file.write_text(SAMPLE_DBG, encoding="utf-8")

    data = parse_dbg_file(dbg_file)
    assert data.get_symbol_address("INPUT") == 0x700AC0
    assert data.get_symbol_address("OUTPUT") == 0x700AC4
