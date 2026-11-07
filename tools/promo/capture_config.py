"""Capture-only path resolution; no probing, encoders or game processes are started here."""
import os
from pathlib import Path
import shutil
import uuid
import hashlib
import re

LEGACY_BIN = Path(r"C:\Users\harde\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-8.1-full_build\bin")

CAPTURE_SUFFIXES = ("", ".timeline.tsv", ".frames.txt", ".ffmpeg.txt")


def verified_runtime_jar(game_dir, expected_sha256):
    """Fail before capture unless exactly one installed AI Guide JAR has the approved bytes."""
    if not re.fullmatch(r"[0-9a-fA-F]{64}", expected_sha256 or ""):
        raise ValueError("--expected-jar-sha256 requires a 64-character SHA256 digest")
    jars = sorted(jar for jar in (Path(game_dir) / "mods").glob("*AIGuide*.jar") if jar.is_file())
    if len(jars) != 1:
        raise ValueError(f"expected exactly one installed AIGuide JAR in {Path(game_dir) / 'mods'}; found {len(jars)}")
    digest = hashlib.sha256()
    with jars[0].open("rb") as data:
        for chunk in iter(lambda: data.read(1024 * 1024), b""):
            digest.update(chunk)
    actual = digest.hexdigest()
    if actual != expected_sha256.lower():
        raise ValueError(f"installed AIGuide JAR SHA256 mismatch: {jars[0]}")
    return {"path": str(jars[0]), "sha256": actual}


def existing_capture_artifacts(video):
    """An interrupted encoder may leave only diagnostics; preserve those on retry."""
    return [p for suffix in CAPTURE_SUFFIXES if (p := Path(str(video) + suffix)).exists()]


def fresh_attempt_prefix(directory, hero):
    """Give each fallback launch its own script and logs, even after an early failure."""
    root = Path(directory)
    for _ in range(16):
        prefix = root / f"hero_{hero}_{uuid.uuid4().hex}"
        if not any(root.glob(prefix.name + "*")):
            return prefix
    raise RuntimeError(f"could not reserve a fresh attempt name for hero {hero}")


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
