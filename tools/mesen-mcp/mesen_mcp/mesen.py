import os
from pathlib import Path
import shutil
from typing import Optional


def find_mesen(
    override_path: Optional[str] = None,
    search_default_locations: bool = True,
) -> Path:
    """Discover the Mesen executable matching tools/run-sfx-test.sh."""
    # 1. Explicit override argument
    if override_path:
        path = Path(override_path).expanduser().resolve()
        if path.is_file() and os.access(path, os.X_OK):
            return path
        raise FileNotFoundError(f"Explicit Mesen executable not found or not executable: {override_path}")

    # 2. MESEN_PATH environment variable
    env_path = os.environ.get("MESEN_PATH")
    if env_path:
        path = Path(env_path).expanduser().resolve()
        if path.is_file() and os.access(path, os.X_OK):
            return path
        raise FileNotFoundError(f"MESEN_PATH points to invalid or non-executable binary: {env_path}")

    if not search_default_locations:
        raise FileNotFoundError(
            "Mesen emulator executable not found. "
            "Please ensure Mesen is placed in /home/summers/Programs/Mesen, specified via MESEN_PATH, or on PATH."
        )

    # 3. Standard locations from tools/run-sfx-test.sh
    candidates = [
        Path("/home/summers/Programs/Mesen"),
        Path(__file__).resolve().parents[3] / ".." / "Mesen2" / "bin" / "osx-arm64" / "Release" / "osx-arm64" / "publish" / "Mesen",
        Path("/Users/summerspittman/Downloads/Mesen/Contents/MacOS/Mesen"),
    ]

    for cand in candidates:
        cand_resolved = cand.resolve()
        if cand_resolved.is_file() and os.access(cand_resolved, os.X_OK):
            return cand_resolved

    # 4. PATH lookup
    for bin_name in ("Mesen", "mesen"):
        which_path = shutil.which(bin_name)
        if which_path:
            p = Path(which_path).resolve()
            if p.is_file() and os.access(p, os.X_OK):
                return p

    raise FileNotFoundError(
        "Mesen emulator executable not found. "
        "Please ensure Mesen is placed in /home/summers/Programs/Mesen, specified via MESEN_PATH, or on PATH."
    )
