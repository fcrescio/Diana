"""Utility scripts and helpers for the Diana project."""

# Expose the notes_tools namespace for convenience when these utilities are
# used as a package.
from . import notes_tools  # noqa: F401  (re-exported for package discovery)

__all__ = ["notes_tools"]
