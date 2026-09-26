"""Poking Syncthing is optional, so the important behaviour is what it does
when Syncthing is absent, misconfigured or simply not sharing this folder.

The folder backend has to keep working with Nextcloud, Dropbox or a plain
network share, so none of those cases may raise or block.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from muzika import syncthing  # noqa: E402

CONFIG = """<configuration version="37">
    <folder id="muzika-lib" label="Muzika library" path="{folder}"></folder>
    <folder id="photos" label="Photos" path="{other}"></folder>
    <gui enabled="true" tls="false">
        <address>127.0.0.1:8384</address>
        <apikey>abc123</apikey>
    </gui>
</configuration>
"""


@pytest.fixture()
def configured(tmp_path, monkeypatch):
    """A Syncthing config that shares tmp_path/sync, and nothing else."""
    folder = tmp_path / "sync"
    other = tmp_path / "photos"
    folder.mkdir()
    other.mkdir()
    state = tmp_path / "state"
    (state / "syncthing").mkdir(parents=True)
    (state / "syncthing" / "config.xml").write_text(
        CONFIG.format(folder=folder, other=other))
    monkeypatch.setenv("XDG_STATE_HOME", str(state))
    syncthing.forget()
    yield folder, other
    syncthing.forget()


def test_finds_the_folder_that_covers_a_path(configured):
    folder, _other = configured
    assert syncthing.folder_id(folder) == "muzika-lib"


def test_a_file_inside_the_folder_still_resolves(configured):
    """The caller passes the sync folder, but a path within it must work too."""
    folder, _other = configured
    assert syncthing.folder_id(folder / "muzika-library.json") == "muzika-lib"


def test_a_different_share_is_not_confused_for_ours(configured):
    _folder, other = configured
    assert syncthing.folder_id(other) == "photos"


def test_a_path_syncthing_does_not_share_gets_nothing(configured, tmp_path):
    assert syncthing.folder_id(tmp_path / "elsewhere") is None


def test_rescanning_an_unshared_path_does_nothing_and_does_not_raise(configured, tmp_path):
    """No folder means no request at all - this must not touch the network."""
    assert syncthing.rescan(tmp_path / "elsewhere") is False


def test_no_syncthing_at_all_is_not_an_error(tmp_path, monkeypatch):
    """Nextcloud, Dropbox and network shares are legitimate setups."""
    monkeypatch.setenv("XDG_STATE_HOME", str(tmp_path / "empty"))
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "empty"))
    monkeypatch.setattr(Path, "home", staticmethod(lambda: tmp_path / "empty"))
    syncthing.forget()
    assert syncthing.folder_id(tmp_path) is None
    assert syncthing.rescan(tmp_path) is False
    syncthing.forget()


def test_a_config_without_an_api_key_is_unusable(tmp_path, monkeypatch):
    """Syncthing can run with the GUI off; there is nothing to authenticate with."""
    state = tmp_path / "state"
    (state / "syncthing").mkdir(parents=True)
    (state / "syncthing" / "config.xml").write_text(
        CONFIG.format(folder=tmp_path, other=tmp_path).replace(
            "<apikey>abc123</apikey>", "<apikey></apikey>"))
    monkeypatch.setenv("XDG_STATE_HOME", str(state))
    syncthing.forget()
    assert syncthing.folder_id(tmp_path) is None
    syncthing.forget()


def test_a_corrupt_config_is_shrugged_off(tmp_path, monkeypatch):
    state = tmp_path / "state"
    (state / "syncthing").mkdir(parents=True)
    (state / "syncthing" / "config.xml").write_text("<not-xml")
    monkeypatch.setenv("XDG_STATE_HOME", str(state))
    syncthing.forget()
    assert syncthing.folder_id(tmp_path) is None
    assert syncthing.rescan(tmp_path) is False
    syncthing.forget()


def test_rescan_swallows_a_dead_api(configured, monkeypatch):
    """Syncthing configured but not running is the common case after a reboot."""
    folder, _other = configured

    def boom(*_args, **_kwargs):
        raise OSError("connection refused")

    monkeypatch.setattr(syncthing.requests, "post", boom)
    assert syncthing.rescan(folder) is False


def test_rescan_reports_success_when_syncthing_accepts(configured, monkeypatch):
    folder, _other = configured
    seen = {}

    class Response:
        status_code = 200

    def fake_post(url, params=None, headers=None, timeout=None):
        seen.update(url=url, params=params, headers=headers)
        return Response()

    monkeypatch.setattr(syncthing.requests, "post", fake_post)
    assert syncthing.rescan(folder) is True
    assert seen["url"].endswith("/rest/db/scan")
    assert seen["params"]["folder"] == "muzika-lib"
    assert seen["headers"]["X-API-Key"] == "abc123"
