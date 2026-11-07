"""Capture-only path resolution; no probing, encoders or game processes are started here."""
import os
from pathlib import Path
import shutil

LEGACY_BIN = Path(r"C:\Users\harde\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-8.1-full_build\bin")


def executable(name, explicit=None, ffmpeg=None):
    """Explicit/env paths fail closed; a typo must not silently select a different binary."""
    configured = explicit or os.environ.get("AIGUIDE_" + name.upper())
    if configured:
        found = shutil.which(configured)
        if not found:
            raise ValueError(f"{name} executable unavailable: {configured}")
        return str(Path(found).resolve())
    if name == "ffprobe" and ffmpeg:
        sibling = Path(ffmpeg).with_name("ffprobe.exe" if os.name == "nt" else "ffprobe")
        if sibling.is_file():
            return str(sibling.resolve())
    found = shutil.which(name)
    if found:
        return str(Path(found).resolve())
    legacy = LEGACY_BIN / (name + ".exe")
    if legacy.is_file():
        return str(legacy)
    raise ValueError(f"{name} missing: use --{name}, AIGUIDE_{name.upper()} or PATH")
