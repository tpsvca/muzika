
#: The one place the desktop version is written. pyproject.toml reads it, the
#: About dialog shows it, and the release workflow refuses a tag that differs -
#: Android takes its version from the tag, and the two must not drift apart.
__version__ = "1.14.1"
