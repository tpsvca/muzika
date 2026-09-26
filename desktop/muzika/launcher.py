"""The command the user actually runs.

It exists only so the system check happens *before* anything imports Gtk.
``muzika/app.py`` calls ``gi.require_version`` at module level, so on a machine
missing a typelib merely importing it raised a ValueError traceback - which
told the user nothing about which package to install. Checking here, then
importing, turns that into a sentence they can act on.
"""

from __future__ import annotations

import sys


def main() -> int:
    from .preflight import report

    complaint = report()
    if complaint:
        print(complaint, file=sys.stderr)
        return 1

    from .app import main as run
    return run()


if __name__ == "__main__":
    sys.exit(main())
