"""The opt-in bit must reach the exact child command before a client is created."""

import sys
from pathlib import Path
from unittest import TestCase, main
from unittest.mock import Mock

PROMO = Path(__file__).resolve().parents[1] / "promo"
sys.path.insert(0, str(PROMO))
from capture_config import capture_diag_cli_options, capture_diag_java_options  # noqa: E402


def check_wiring(take, multicam):
    assert "cmd[1:1] = capture_diag_java_options(a.capture_diag)" in take
    assert "capture_diag_cli_options(a.capture_diag)" in multicam
    assert '"capture_diag": a.capture_diag' in take
    assert multicam.count('"capture_diag": a.capture_diag') == 2
    assert '"server_sha256", "capture_diag"' in multicam


class CaptureDiagTests(TestCase):
    def test_mock_launch_absent_and_present(self):
        launcher = Mock()
        for enabled in (False, True):
            launcher(["python", "take.py", *capture_diag_cli_options(enabled)])
            child = launcher.call_args.args[0]
            self.assertEqual("--capture-diag" in child, enabled)
            java = ["java", *capture_diag_java_options(enabled), "main"]
            self.assertEqual("-Daiguide.capture.diag=true" in java, enabled)
        self.assertEqual(2, launcher.call_count)

    def test_real_wiring_and_disabled_mutants(self):
        take = (PROMO / "take.py").read_text(encoding="utf-8")
        multicam = (PROMO / "multicam.py").read_text(encoding="utf-8")
        check_wiring(take, multicam)
        mutants = (
            (take.replace("cmd[1:1] = capture_diag_java_options(a.capture_diag)", "", 1), multicam),
            (take, multicam.replace("+ capture_diag_cli_options(a.capture_diag)", "", 1)),
            (take, multicam.replace('"server_sha256", "capture_diag"', '"server_sha256"', 1)),
        )
        for bad_take, bad_multicam in mutants:
            with self.assertRaises(AssertionError):
                check_wiring(bad_take, bad_multicam)


if __name__ == "__main__":
    main()
