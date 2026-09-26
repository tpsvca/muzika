"""What Muzika needs from the system, and a readable complaint when it is not there.

Two distinct things go wrong on a fresh machine, and neither used to say so:

1. **A missing typelib.** The app needs Gtk, Adw, Gst *and* GstPbutils. On
   Debian and Ubuntu the GObject bindings are split into their own
   ``gir1.2-*`` packages, and the GStreamer ones are not pulled in by the
   plugin packages - so following a package list that names only the plugins
   leaves the app unable to import at all.

2. **A libadwaita that is too old.** The UI uses ``AdwWrapBox`` and
   ``AdwInlineViewSwitcher``, both added in libadwaita 1.7. Older releases
   import fine and then fail deep inside building a window, which reads as a
   crash rather than as "your distribution is too old".

The check is by capability, not by version number: it asks whether the exact
widgets the code uses are present. That stays correct when the UI starts using
something newer, which a hard-coded ``1.7`` would not.
"""

from __future__ import annotations

import sys
from pathlib import Path

# (namespace, version) pairs this app calls require_version() for.
REQUIRED_TYPELIBS: list[tuple[str, str]] = [
    ("Gtk", "4.0"),
    ("Adw", "1"),
    ("Gst", "1.0"),
    ("GstPbutils", "1.0"),
]

# libadwaita widgets the UI actually instantiates that are newer than 1.4.
REQUIRED_ADW_WIDGETS: list[str] = [
    "WrapBox",              # 1.7
    "InlineViewSwitcher",   # 1.7
    "Spinner",              # 1.6
    "Dialog",               # 1.5
    "AlertDialog",          # 1.5
    "PreferencesDialog",    # 1.5
    "AboutDialog",          # 1.5
    "ToolbarView",          # 1.4
    "NavigationView",       # 1.4
    "NavigationPage",       # 1.4
    "NavigationSplitView",  # 1.4
    "Breakpoint",           # 1.4
]

INSTALL_COMMANDS: dict[str, str] = {
    "fedora": (
        "sudo dnf install python3-gobject gtk4 libadwaita gstreamer1 "
        "gstreamer1-plugins-base gstreamer1-plugins-good gstreamer1-plugins-bad-free"
    ),
    "debian": (
        "sudo apt install python3-gi python3-gi-cairo gir1.2-gtk-4.0 gir1.2-adw-1 "
        "gir1.2-gstreamer-1.0 gir1.2-gst-plugins-base-1.0 "
        "gstreamer1.0-plugins-good gstreamer1.0-plugins-bad"
    ),
    "arch": (
        "sudo pacman -S python-gobject gtk4 libadwaita gstreamer "
        "gst-plugins-base gst-plugins-good gst-plugins-bad"
    ),
    "suse": (
        "sudo zypper install python3-gobject python3-gobject-Gdk typelib-1_0-Gtk-4_0 "
        "typelib-1_0-Adw-1 typelib-1_0-Gst-1_0 typelib-1_0-GstPbutils-1_0 "
        "gstreamer-plugins-good gstreamer-plugins-bad"
    ),
}

# Releases new enough for libadwaita 1.7. Named because "install libadwaita"
# is useless advice when the distribution simply does not carry a new enough one.
NEW_ENOUGH = (
    "Fedora 42+, Debian 13 (trixie)+, Ubuntu 25.04+, or a rolling release "
    "such as Arch"
)


def _distro_key() -> str:
    """Which package manager to suggest, from /etc/os-release."""
    try:
        fields = {}
        for line in Path("/etc/os-release").read_text().splitlines():
            if "=" in line:
                key, _, value = line.partition("=")
                fields[key] = value.strip().strip('"')
    except OSError:
        return "debian"
    ids = f"{fields.get('ID', '')} {fields.get('ID_LIKE', '')}".lower()
    for key in ("fedora", "arch", "suse"):
        if key in ids:
            return key
    if "rhel" in ids or "centos" in ids:
        return "fedora"
    return "debian"


def problems() -> list[str]:
    """Everything wrong with this system, in the order worth fixing it."""
    found: list[str] = []
    try:
        import gi
    except ImportError:
        return ["The Python GObject bindings are missing (python3-gi / python3-gobject)."]

    adw = None
    for namespace, version in REQUIRED_TYPELIBS:
        try:
            gi.require_version(namespace, version)
            module = __import__("gi.repository", fromlist=[namespace])
            loaded = getattr(module, namespace)
            if namespace == "Adw":
                adw = loaded
        except (ImportError, ValueError, AttributeError):
            found.append(f"The {namespace} {version} typelib is missing.")

    if adw is not None:
        missing = [name for name in REQUIRED_ADW_WIDGETS if not hasattr(adw, name)]
        if missing:
            version = (f"{adw.get_major_version()}.{adw.get_minor_version()}"
                       f".{adw.get_micro_version()}")
            found.append(
                f"libadwaita {version} is too old - it has no "
                f"Adw.{missing[0]}. Muzika needs 1.7 or newer, which means "
                f"{NEW_ENOUGH}."
            )
    return found


def report() -> str:
    """The whole complaint, ready to print, or an empty string when fine."""
    found = problems()
    if not found:
        return ""
    lines = ["Muzika cannot run on this system yet:", ""]
    lines += [f"  - {problem}" for problem in found]
    lines += ["", "Install what your distribution calls them:", "",
              f"    {INSTALL_COMMANDS[_distro_key()]}", ""]
    return "\n".join(lines)


def main() -> int:
    complaint = report()
    if complaint:
        print(complaint, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
