"""Pure timeline controls; no FFmpeg, Minecraft or video files are opened."""
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "promo"))
from align_interface import inspect_source_timeline, source_frames_for_ticks, validate_source_video_frames


class InterfaceTimelineTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.timeline = Path(self.tmp.name) / "live.timeline.tsv"

    def write_rows(self, rows):
        self.timeline.write_text("frame\tworld_tick\n" + "".join(
            f"{frame}\t{tick}\n" for frame, tick in rows), encoding="utf-8")

    def test_covered_interval_uses_real_nearest_frames(self):
        self.write_rows([(0, "800.000000"), (1, "800.333333"), (2, "800.666667")])
        self.assertEqual([0, 1, 2], source_frames_for_ticks(self.timeline,
                         [800.000000, 800.333333, 800.666667]))

    def test_missing_source_boundary_is_rejected(self):
        self.write_rows([(0, "800.333333"), (1, "800.666667")])
        with self.assertRaisesRegex(ValueError, "does not cover"):
            source_frames_for_ticks(self.timeline, [800.000000, 800.333333])
        with self.assertRaisesRegex(ValueError, "does not cover"):
            source_frames_for_ticks(self.timeline, [800.333333, 801.000000])

    def test_missing_frame_or_nonfinite_tick_is_rejected(self):
        self.write_rows([(0, "800.000000"), (2, "800.666667")])
        with self.assertRaisesRegex(ValueError, "indices"):
            source_frames_for_ticks(self.timeline, [800.000000])
        self.write_rows([(0, "800.000000"), (1, "nan")])
        with self.assertRaisesRegex(ValueError, "finite"):
            source_frames_for_ticks(self.timeline, [800.000000])

    def test_long_internal_gap_is_rejected_before_capture(self):
        self.write_rows([(0, "800.000000"), (1, "820.000000")])
        with self.assertRaisesRegex(ValueError, "world-tick gap"):
            inspect_source_timeline(self.timeline, [800.333333, 810.000000, 819.666667])

    def test_small_live_gap_keeps_real_frames(self):
        self.write_rows([(0, "800.000000"), (1, "801.500000"), (2, "803.000000")])
        chosen, count = inspect_source_timeline(self.timeline, [800.333333, 801.000000, 802.000000, 802.666667])
        self.assertEqual(3, count)
        self.assertEqual(4, len(chosen))

    def test_video_and_sidecar_frame_count_must_match(self):
        with patch("align_interface.subprocess.check_output", return_value=b'{"streams":[{"nb_read_frames":"2"}]}') as probe:
            with self.assertRaisesRegex(ValueError, "MP4 has 2 frames but timeline has 3"):
                validate_source_video_frames("live.mp4", 3, "ffprobe")
            self.assertIn("-count_frames", probe.call_args.args[0])
            self.assertEqual(2, validate_source_video_frames("live.mp4", 2, "ffprobe"))
        with patch("align_interface.subprocess.check_output", return_value=b'{"streams":[]}'):
            with self.assertRaisesRegex(ValueError, "no countable"):
                validate_source_video_frames("live.mp4", 2, "ffprobe")


if __name__ == "__main__":
    unittest.main()
