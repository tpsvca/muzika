#!/bin/bash
# Installs Muzika for the current user: the Python package, the launcher entry
# and the icon. Nothing is written outside ~/.local.
set -euo pipefail
here="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"

echo "Installing the Python package…"
python3 -m pip install --user --upgrade "$here"

echo "Installing the desktop entry and icon…"
install -Dm644 "$here/data/lt.a777.Muzika.desktop" \
    "$HOME/.local/share/applications/lt.a777.Muzika.desktop"
install -Dm644 "$here/data/lt.a777.Muzika.svg" \
    "$HOME/.local/share/icons/hicolor/scalable/apps/lt.a777.Muzika.svg"

command -v update-desktop-database >/dev/null && \
    update-desktop-database "$HOME/.local/share/applications" || true
command -v gtk-update-icon-cache >/dev/null && \
    gtk-update-icon-cache -qtf "$HOME/.local/share/icons/hicolor" 2>/dev/null || true

echo
echo "Done. Launch it from your applications menu, or run: muzika"
echo "If 'muzika' is not found, add ~/.local/bin to your PATH."
