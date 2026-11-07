"""The foreground-game guard must stop on presence or uncertain enumeration."""
import ctypes
import inspect
import subprocess
import sys
import textwrap
import unittest
from pathlib import Path

PROMO = Path(__file__).resolve().parents[1] / "promo"
sys.path.insert(0, str(PROMO))
import league_guard  # noqa: E402


class FakeKernel:
    def __init__(self, names=(), fail=None):
        self.names = list(names)
        self.fail = fail
        self.index = 0
        self.error = 0
        self.closed = False

    def CreateToolhelp32Snapshot(self, flags, process_id):
        assert (flags, process_id) == (2, 0)
        if self.fail == "snapshot":
            self.error = 5
            return ctypes.c_void_p(-1).value
        return 123

    def _entry(self, pointer):
        entry = ctypes.cast(pointer, ctypes.POINTER(league_guard.ProcessEntry32W)).contents
        self.expected_size = ctypes.sizeof(entry)
        entry.szExeFile = self.names[self.index]

    def Process32FirstW(self, snapshot, pointer):
        assert snapshot == 123
        if self.fail == "first" or not self.names:
            self.error = 5 if self.fail else 18
            return 0
        self._entry(pointer)
        return 1

    def Process32NextW(self, snapshot, pointer):
        assert snapshot == 123
        if self.fail == "next":
            self.error = 5
            return 0
        self.index += 1
        if self.index == len(self.names):
            self.error = 18
            return 0
        self._entry(pointer)
        return 1

    def CloseHandle(self, snapshot):
        assert snapshot == 123
        self.closed = True
        if self.fail == "close":
            self.error = 5
            return 0
        return 1


class LeagueGuardTests(unittest.TestCase):
    def call(self, names=(), fail=None, guard=league_guard.active_league):
        kernel = FakeKernel(names, fail)
        try:
            return guard(kernel=kernel, last_error=lambda: kernel.error)
        finally:
            if fail != "snapshot":
                self.assertTrue(kernel.closed)

    def test_present_absent_and_exact_image(self):
        self.assertTrue(self.call(["java.exe", "LeAgUe Of LeGeNdS.ExE"]))
        self.assertFalse(self.call(["java.exe", "LeagueClient.exe", "League of Legends.exe.backup"]))

    def test_enumeration_errors_are_never_absence(self):
        for stage in ("snapshot", "first", "next", "close"):
            with self.subTest(stage=stage), self.assertRaises(OSError):
                self.call(["java.exe"], stage)
        with self.assertRaises(OSError):
            self.call([])

    def test_capture_stops_on_presence_or_uncertainty(self):
        self.assertIsNone(league_guard.capture_stop_reason(lambda: False))
        self.assertIn("League of Legends.exe started", league_guard.capture_stop_reason(lambda: True))
        def timeout():
            raise subprocess.TimeoutExpired("tasklist", 10)
        self.assertIn("process check failed", league_guard.capture_stop_reason(timeout))
        def api_error():
            raise OSError(5, "snapshot failed")
        self.assertIn("process check failed", league_guard.capture_stop_reason(api_error))

    def test_take_wiring_and_wrong_comparison_mutant(self):
        take = (PROMO / "take.py").read_text(encoding="utf-8")
        self.assertIn("from league_guard import active_league, capture_stop_reason", take)
        self.assertIn("reason = capture_stop_reason()", take)
        self.assertIn("running = active_league()", take)
        self.assertIn("except Exception as exc:", take)
        self.assertNotIn('["tasklist", "/fo", "csv", "/nh"]', take)
        source = textwrap.dedent(inspect.getsource(league_guard.active_league))
        bad_source = source.replace('.casefold() == "league of legends.exe"', '.casefold() != "league of legends.exe"', 1)
        self.assertNotEqual(source, bad_source)
        namespace = vars(league_guard).copy()
        exec(bad_source, namespace)
        wrong_guard = namespace["active_league"]
        self.assertNotEqual(self.call(["League of Legends.exe"], guard=wrong_guard),
                            self.call(["League of Legends.exe"]))


if __name__ == "__main__":
    unittest.main()
