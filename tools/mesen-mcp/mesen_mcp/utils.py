from pathlib import Path


def get_repo_root() -> Path:
    """Find the root directory of the retro-superfx repository."""
    # mesen_mcp is in tools/mesen-mcp/mesen_mcp -> 3 levels up is root
    return Path(__file__).resolve().parents[3]


def resolve_test_dir(test_name_or_path: str) -> Path:
    """Resolve test directory by path or name within snes-sfx-demo."""
    root_dir = get_repo_root()
    p = Path(test_name_or_path)

    if p.is_dir():
        return p.resolve()

    cand1 = root_dir / "snes-sfx-demo" / "X-GSU" / "tests" / test_name_or_path
    if cand1.is_dir():
        return cand1.resolve()

    cand2 = root_dir / "snes-sfx-demo" / "tests" / test_name_or_path
    if cand2.is_dir():
        return cand2.resolve()

    cand3 = root_dir / "snes-sfx-demo" / test_name_or_path
    if cand3.is_dir():
        return cand3.resolve()

    cand4 = root_dir / test_name_or_path
    if cand4.is_dir():
        return cand4.resolve()

    raise FileNotFoundError(f"Test directory not found: '{test_name_or_path}'")
