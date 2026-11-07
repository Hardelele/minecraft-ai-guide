"""An orphan recorder sidecar must reject a missing hero output before retry."""
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "promo"))
from capture_config import existing_capture_artifacts, fresh_attempt_prefix


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


if __name__ == "__main__":
    unittest.main()
