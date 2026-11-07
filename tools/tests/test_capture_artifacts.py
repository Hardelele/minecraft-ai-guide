"""An orphan recorder sidecar must reject a missing hero output before retry."""
import sys
import tempfile
import unittest
import hashlib
import os
import subprocess
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "promo"))
from capture_config import existing_capture_artifacts, fresh_attempt_prefix, verified_runtime_jar


class CaptureArtifactTests(unittest.TestCase):
    def test_each_output_is_a_collision(self):
        with tempfile.TemporaryDirectory() as root:
            video = Path(root) / "hero_1.mp4"
            self.assertEqual([], existing_capture_artifacts(video))
            for suffix in (".ffmpeg.txt", ".frames.txt", ".timeline.tsv", ""):
                artifact = Path(str(video) + suffix)
                artifact.write_text("partial", encoding="utf-8")
                self.assertEqual([artifact], existing_capture_artifacts(video))
                artifact.unlink()

    def test_fallback_attempt_never_reuses_early_launch_evidence(self):
        with tempfile.TemporaryDirectory() as root:
            old = Path(root) / "hero_1_launch.raw.log"
            old.write_text("early failure", encoding="utf-8")
            first = Path(root) / "hero_1_taken.txt"
            first.write_text("previous script", encoding="utf-8")
            with patch("capture_config.uuid.uuid4", side_effect=[SimpleNamespace(hex="taken"), SimpleNamespace(hex="fresh")]):
                prefix = fresh_attempt_prefix(root, 1)
            self.assertEqual(Path(root) / "hero_1_fresh", prefix)
            self.assertEqual("early failure", old.read_text(encoding="utf-8"))
            self.assertEqual("previous script", first.read_text(encoding="utf-8"))

    def test_exactly_one_approved_installed_jar(self):
        with tempfile.TemporaryDirectory() as root:
            game = Path(root) / "game"
            mods = game / "mods"
            mods.mkdir(parents=True)
            data = b"approved client artifact"
            expected = hashlib.sha256(data).hexdigest()
            with self.assertRaisesRegex(ValueError, "found 0"):
                verified_runtime_jar(game, expected)
            first = mods / "AIGuideClient.jar"
            first.write_bytes(data)
            with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
                verified_runtime_jar(game, hashlib.sha256(b"different artifact").hexdigest())
            self.assertEqual({"path": str(first), "sha256": expected}, verified_runtime_jar(game, expected.upper()))
            (mods / "OtherAIGuide.jar").write_bytes(b"duplicate")
            with self.assertRaisesRegex(ValueError, "found 2"):
                verified_runtime_jar(game, expected)

    def test_single_client_jar_gate_before_capture_directory_created(self):
        if os.name != "nt":
            self.skipTest("single-client UNC preflight is Windows-specific")
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            mods = root / "game" / "mods"
            mods.mkdir(parents=True)
            unc_root = "\\\\localhost\\" + root.drive[0] + "$\\" + str(root)[3:]
            env = dict(os.environ, AIGUIDE_CAPTURE_ROOT=str(root), AIGUIDE_MEDIA_ROOT=unc_root,
                       AIGUIDE_FFMPEG=sys.executable, AIGUIDE_FFPROBE=sys.executable)
            expected = hashlib.sha256(b"approved").hexdigest()
            cmd = [sys.executable, str(Path(__file__).resolve().parents[1] / "promo" / "multicam.py"),
                   "recording", "--name", "must_not_exist", "--game", "game", "--single-client",
                   "--capture-source", "source", "--expected-jar-sha256", expected,
                   "--ui", str(root / "missing_ui.mp4")]

            def reject(message):
                result = subprocess.run(cmd, env=env, capture_output=True, text=True)
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn(message, result.stderr)
                self.assertFalse((root / "captures" / "must_not_exist").exists())

            reject("found 0")
            jar = mods / "AIGuideClient.jar"
            jar.write_bytes(b"wrong")
            reject("SHA256 mismatch")
            jar.write_bytes(b"approved")
            reject("live Commander UI source does not exist")  # approved JAR passed its gate
            (mods / "OtherAIGuide.jar").write_bytes(b"duplicate")
            reject("found 2")


if __name__ == "__main__":
    unittest.main()
