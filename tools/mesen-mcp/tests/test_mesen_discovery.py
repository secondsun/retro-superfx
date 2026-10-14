import os
from pathlib import Path
import pytest
from mesen_mcp.mesen import find_mesen


def test_find_mesen_with_explicit_override(tmp_path):
    fake_mesen = tmp_path / "Mesen"
    fake_mesen.write_text("#!/bin/sh\nexit 0\n")
    fake_mesen.chmod(0o755)

    resolved = find_mesen(override_path=str(fake_mesen))
    assert resolved == fake_mesen


def test_find_mesen_with_env_var(tmp_path, monkeypatch):
    fake_mesen = tmp_path / "Mesen_env"
    fake_mesen.write_text("#!/bin/sh\nexit 0\n")
    fake_mesen.chmod(0o755)

    monkeypatch.setenv("MESEN_PATH", str(fake_mesen))
    resolved = find_mesen()
    assert resolved == fake_mesen


def test_find_mesen_default_installed():
    # If /home/summers/Programs/Mesen exists, it should be resolved
    default_path = Path("/home/summers/Programs/Mesen")
    if default_path.is_file() and os.access(default_path, os.X_OK):
        resolved = find_mesen()
        assert resolved.is_file()
        assert os.access(resolved, os.X_OK)


def test_find_mesen_not_found(monkeypatch):
    monkeypatch.delenv("MESEN_PATH", raising=False)
    monkeypatch.setenv("PATH", "")
    
    with pytest.raises(FileNotFoundError, match="Mesen emulator executable not found"):
        find_mesen(search_default_locations=False)
