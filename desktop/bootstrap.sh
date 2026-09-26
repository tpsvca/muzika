#!/bin/bash
# One command that takes a bare machine to a working Muzika.
#
#   curl -fsSL https://raw.githubusercontent.com/tpsvca/muzika/main/desktop/bootstrap.sh | bash
#
# It works out the distribution, shows you the package command before running
# it, clones the repo and hands over to install.sh. Nothing is installed
# system-wide except the distribution's own GTK, libadwaita and GStreamer
# packages, which every GNOME or KDE desktop has most of already.
#
# Overrides, both optional:
#   MUZIKA_SRC=/path   where to clone (default ~/muzika)
#   MUZIKA_YES=1       skip the confirmation, for unattended installs
#
# The package lists are kept identical to the ones in
# desktop/muzika/preflight.py, and tests/test_install_docs.py fails if they
# ever drift apart.
set -euo pipefail

REPO="https://github.com/tpsvca/muzika.git"
SRC="${MUZIKA_SRC:-$HOME/muzika}"

die() { printf '\n%s\n' "$*" >&2; exit 1; }

# --------------------------------------------------------------- distribution

distro_key() {
    [ -r /etc/os-release ] || { echo unknown; return; }
    # shellcheck disable=SC1091
    . /etc/os-release
    local ids="${ID:-} ${ID_LIKE:-}"
    case "$ids" in
        *fedora*|*rhel*|*centos*) echo fedora ;;
        *arch*)                   echo arch ;;
        *suse*)                   echo suse ;;
        *debian*|*ubuntu*)        echo debian ;;
        *)                        echo unknown ;;
    esac
}

packages_for() {
    case "$1" in
        fedora)
            echo "dnf install -y python3-gobject gtk4 libadwaita gstreamer1 gstreamer1-plugins-base gstreamer1-plugins-good gstreamer1-plugins-bad-free git"
            ;;
        debian)
            # apt update is not optional: an index naming a version the mirror
            # has already superseded fails with a bare 404.
            echo "sh -c 'apt-get update && apt-get install -y python3-gi python3-gi-cairo python3-venv gir1.2-gtk-4.0 gir1.2-adw-1 gir1.2-gstreamer-1.0 gir1.2-gst-plugins-base-1.0 gstreamer1.0-plugins-good gstreamer1.0-plugins-bad git'"
            ;;
        arch)
            # -Syu rather than -S: a partial upgrade is the wrong thing to do
            # on a rolling release, and -S alone hits the same stale-index 404.
            echo "pacman -Syu --noconfirm --needed python-gobject gtk4 libadwaita gstreamer gst-plugins-base gst-plugins-good gst-plugins-bad git"
            ;;
        suse)
            echo "zypper --non-interactive install python3-gobject python3-gobject-Gdk typelib-1_0-Gtk-4_0 typelib-1_0-Adw-1 typelib-1_0-Gst-1_0 typelib-1_0-GstPbutils-1_0 gstreamer-plugins-good gstreamer-plugins-bad git"
            ;;
    esac
}

# ------------------------------------------------------------------- consent

# Read from the terminal, not stdin: under `curl | bash`, stdin is the script.
confirm() {
    [ "${MUZIKA_YES:-}" = "1" ] && return 0
    # `test -r /dev/tty` is not enough: the node can exist and still refuse to
    # open, which is exactly the case in a pipeline with no controlling
    # terminal. Open it first, then ask.
    if ! exec 3</dev/tty 2>/dev/null; then
        die "No terminal to ask for confirmation on.
Re-run with MUZIKA_YES=1 if an unattended install is what you want."
    fi
    local answer
    printf '\nRun it? [y/N] '
    read -r answer <&3 || answer=n
    exec 3<&-
    case "$answer" in [yY]*) return 0 ;; *) die "Nothing was changed." ;; esac
}

# ----------------------------------------------------------------------- main

key="$(distro_key)"
if [ "$key" = unknown ]; then
    die "Unrecognised distribution. Install GTK4, libadwaita, GStreamer with its
GObject bindings, git and Python's venv module using your package manager,
then run:

    git clone $REPO && cd muzika/desktop && ./install.sh"
fi

packages="$(packages_for "$key")"
if [ "$(id -u)" -eq 0 ]; then
    elevate=""
elif command -v sudo >/dev/null; then
    elevate="sudo "
else
    die "This needs root to install packages, and sudo is not available.
Run it as root, or install them yourself:

    $packages"
fi

cat <<MSG
Muzika needs these from your distribution ($key):

    ${elevate}${packages}
MSG
confirm

# shellcheck disable=SC2086
eval "${elevate}${packages}" || die "Installing the packages failed. Nothing else was changed."

if [ -d "$SRC/.git" ]; then
    echo
    echo "Updating the existing checkout at $SRC…"
    git -C "$SRC" pull --ff-only
else
    echo
    echo "Cloning into $SRC…"
    git clone "$REPO" "$SRC"
fi

echo
exec "$SRC/desktop/install.sh"
