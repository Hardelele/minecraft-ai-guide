"""An orphan recorder sidecar must reject a missing hero output before retry."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "promo"))
from capture_config import existing_capture_artifacts


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


if __name__ == "__main__":
    unittest.main()
