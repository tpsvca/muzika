#!/bin/bash
# Installs Muzika for the current user. Nothing is written outside ~/.local and
# no system Python package is touched.
#
# The app needs PyGObject and GStreamer, which are system packages, plus a few
# things from PyPI. Debian, Ubuntu and friends refuse `pip install --user`
# outright (PEP 668), so this creates a virtualenv that can still see the
# system GTK bindings and installs into that.
set -euo pipefail

here="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
venv="$HOME/.local/share/muzika/venv"
bindir="$HOME/.local/bin"

echo "Checking what the system provides…"
# The real check lives in muzika/preflight.py so the app can run it too. It
# verifies every typelib the code imports AND the libadwaita widgets the UI
# uses - not just that `import gi` works, which used to pass on systems where
# the app then could not start.
if ! PYTHONPATH="$here" python3 -m muzika.preflight; then
    exit 1
fi

echo "Creating the virtualenv at $venv…"
python3 -m venv --system-site-packages --upgrade-deps "$venv" >/dev/null

echo "Installing Muzika and its Python dependencies…"
"$venv/bin/pip" install --quiet --upgrade "$here"

mkdir -p "$bindir"
ln -sf "$venv/bin/muzika" "$bindir/muzika"

echo "Installing the desktop entry and icon…"
install -Dm644 "$here/data/lt.a777.Muzika.svg" \
    "$HOME/.local/share/icons/hicolor/scalable/apps/lt.a777.Muzika.svg"

# The Exec line is written with the real path rather than bare `muzika`:
# a desktop file launched by the shell does not reliably inherit ~/.local/bin.
desktop="$HOME/.local/share/applications/lt.a777.Muzika.desktop"
mkdir -p "$(dirname "$desktop")"
sed "s|^Exec=.*|Exec=$venv/bin/muzika|" "$here/data/lt.a777.Muzika.desktop" > "$desktop"
chmod 644 "$desktop"

command -v update-desktop-database >/dev/null && \
    update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
command -v gtk-update-icon-cache >/dev/null && \
    gtk-update-icon-cache -qtf "$HOME/.local/share/icons/hicolor" 2>/dev/null || true

echo
echo "Done. Launch Muzika from your applications menu, or run:"
echo "    $bindir/muzika"
echo
echo "If plain 'muzika' is not found, add ~/.local/bin to your PATH."
echo "To remove it again: $here/uninstall.sh"
