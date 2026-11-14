import os
import pytest


def pytest_configure(config: pytest.Config) -> None:
    config.addinivalue_line(
        "markers",
        "emulator: end-to-end smoke tests requiring a real Mesen CE executable",
    )


def pytest_collection_modifyitems(
    config: pytest.Config, items: list[pytest.Item]
) -> None:
    markexpr = (getattr(config.option, "markexpr", "") or "").strip()
    explicitly_requested = (
        ("emulator" in markexpr and "not emulator" not in markexpr)
        or os.environ.get("RUN_MESEN_EMULATOR") == "1"
    )
    if explicitly_requested:
        return

    skip_emulator = pytest.mark.skip(
        reason="Skipped by default; pass `-m emulator` to run real Mesen CE smoke tests."
    )
    for item in items:
        if "emulator" in item.keywords:
            item.add_marker(skip_emulator)
