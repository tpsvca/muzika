#!/bin/bash
# Removes everything install.sh created. Your library and settings in
# ~/.local/share/muzika and ~/.config/muzika are left alone.
set -euo pipefail
rm -rf "$HOME/.local/share/muzika/venv"
rm -f  "$HOME/.local/bin/muzika"
rm -f  "$HOME/.local/share/applications/lt.a777.Muzika.desktop"
rm -f  "$HOME/.local/share/icons/hicolor/scalable/apps/lt.a777.Muzika.svg"
command -v update-desktop-database >/dev/null && \
    update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
echo "Removed. Your playlists and settings were kept:"
echo "    ~/.local/share/muzika/muzika.db"
echo "    ~/.config/muzika/config.json"
