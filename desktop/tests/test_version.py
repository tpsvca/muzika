"""The desktop version has to move with the releases.

The About dialog said 1.0.0 through fourteen releases: Android takes its
version from the git tag, the desktop had its own written in two places, and
nothing compared them. It is written once now, in muzika/__init__.py; the
release workflow refuses a tag that differs, and this keeps it from falling
behind the changelog in between.
"""

from __future__ import annotations

import re
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "desktop"))

import muzika  # noqa: E402


def _key(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))


def test_the_version_is_the_newest_one_the_changelog_announces():
    headings = re.findall(r"^## .*\(v(\d+\.\d+\.\d+)\)", (ROOT / "CHANGELOG.md").read_text(), re.M)
    assert headings, "no released version found in CHANGELOG headings"
    assert muzika.__version__ == max(headings, key=_key)


def test_pyproject_reads_the_version_instead_of_repeating_it():
    project = tomllib.loads((ROOT / "desktop" / "pyproject.toml").read_text())
    assert "version" not in project["project"]
    assert "version" in project["project"]["dynamic"]
    assert project["tool"]["setuptools"]["dynamic"]["version"] == {"attr": "muzika.__version__"}
