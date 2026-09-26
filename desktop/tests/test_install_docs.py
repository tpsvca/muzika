"""The package lists exist in three places. They must not drift apart.

`bootstrap.sh` has to carry its own copy because it runs before the repo is
cloned; `preflight.py` has the authoritative one; the README shows them to
people. A fix applied to one and forgotten in the others is exactly how
somebody ends up following instructions that cannot work, which is what these
tests are here to stop.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

import pytest

DESKTOP = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DESKTOP))

from muzika.preflight import INSTALL_COMMANDS  # noqa: E402

README = DESKTOP.parent / "README.md"
BOOTSTRAP = DESKTOP / "bootstrap.sh"

# Distributions the project claims to support, and the manager each uses.
DISTROS = {
    "fedora": "dnf",
    "debian": "apt",
    "arch": "pacman",
    "suse": "zypper",
}


NOISE = {"", "&&", "sudo", "apt", "apt-get", "update", "sh", "-c", "y", "echo",
         "zypper", "dnf", "pacman", "install"}


def packages(command: str) -> set[str]:
    """Just the package names, free of the manager, its flags and shell quoting."""
    # Everything after the subcommand; pacman has no `install` verb at all.
    tail = re.split(r"\b(?:install|-Syu|-S)\b", command, maxsplit=1)[-1]
    words = re.split(r"[\s'\"]+", tail)
    return {
        word.strip("'\"") for word in words
        if word and not word.startswith("-") and "/" not in word and word not in NOISE
    }


@pytest.mark.parametrize("key", sorted(DISTROS))
def test_preflight_knows_every_supported_distro(key):
    assert key in INSTALL_COMMANDS
    assert DISTROS[key] in INSTALL_COMMANDS[key]


@pytest.mark.parametrize("key", sorted(DISTROS))
def test_bootstrap_installs_the_same_packages_as_preflight_names(key):
    bootstrap = BOOTSTRAP.read_text()
    block = re.search(rf"^\s*{key}\)\n(.*?)\n\s*;;", bootstrap, re.S | re.M)
    assert block, f"bootstrap.sh has no branch for {key}"

    from_bootstrap = packages(block.group(1))
    from_preflight = packages(INSTALL_COMMANDS[key])

    # bootstrap also installs git, which the app itself does not need.
    missing = from_preflight - from_bootstrap
    assert not missing, (
        f"{key}: bootstrap.sh would not install {sorted(missing)}, "
        "which preflight.py says the app needs"
    )
    assert "git" in from_bootstrap, f"{key}: bootstrap.sh needs git to clone"


@pytest.mark.parametrize("key", sorted(DISTROS))
def test_readme_shows_the_same_packages(key):
    readme = README.read_text()
    from_preflight = packages(INSTALL_COMMANDS[key])
    manager = DISTROS[key]

    # pacman's line is `pacman -Syu …` with no `install` verb, so match on the
    # manager rather than the verb.
    lines = [ln for ln in readme.splitlines()
             if re.match(rf"^sudo\b.*\b{manager}\b", ln)]
    assert lines, f"README shows no {manager} command"

    shown = set().union(*(packages(ln) for ln in lines))
    missing = from_preflight - shown
    assert not missing, (
        f"{key}: the README does not tell people to install {sorted(missing)}"
    )


def test_debian_refreshes_the_index_first():
    """A stale index asks for a .deb the mirror has superseded, and 404s."""
    assert "apt update" in INSTALL_COMMANDS["debian"] or \
           "apt-get update" in INSTALL_COMMANDS["debian"]
    assert "apt-get update" in BOOTSTRAP.read_text()


def test_arch_does_not_recommend_a_partial_upgrade():
    """-S without -y hits the same stale-index problem; -Syu is the Arch way."""
    assert "-Syu" in INSTALL_COMMANDS["arch"]
    assert "-Syu" in BOOTSTRAP.read_text()


def test_debian_installs_python3_venv():
    """Split out on Debian/Ubuntu, and nothing else pulls it in."""
    assert "python3-venv" in INSTALL_COMMANDS["debian"]
    assert "python3-venv" in BOOTSTRAP.read_text()


def test_every_list_carries_the_gstreamer_bindings():
    """The plugin packages are not the GObject bindings, on any distribution."""
    needs = {
        "fedora": ["gstreamer1", "gstreamer1-plugins-base"],
        "debian": ["gir1.2-gstreamer-1.0", "gir1.2-gst-plugins-base-1.0"],
        "arch": ["gstreamer", "gst-plugins-base"],
        "suse": ["typelib-1_0-Gst-1_0", "typelib-1_0-GstPbutils-1_0"],
    }
    for key, expected in needs.items():
        command = INSTALL_COMMANDS[key]
        for package in expected:
            assert package in command, f"{key} list is missing {package}"
