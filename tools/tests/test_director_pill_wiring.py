"""Catch scripted-click regressions that a pure hit-box probe cannot see.

Director depends on Minecraft GUI classes, so this checks the narrow source wiring
from its script commands to the separately executed CaptureSafetyProbe.
"""

from pathlib import Path
import re
import unittest


DIRECTOR = (Path(__file__).resolve().parents[2] / "client" / "src" /
            "hardelele" / "aiguide" / "client" / "Director.java")


def command_body(source, command, following):
    start = source.index('if (cmd.equals("' + command + '"))')
    end = source.index('if (cmd.equals("' + following + '"))', start)
    return source[start:end]


def wiring_errors(source):
    errors = []
    try:
        move = command_body(source, "vmoveto", "vdown")
        click = command_body(source, "vclick", "vmod")
    except ValueError:
        return ["script command missing"]

    if not re.search(r"if\s*\(pillId\s*!=\s*null\).*?CaptureHitTarget\.reached",
                     move, re.DOTALL):
        errors.append("vmoveto does not check the current exact pill")
    if not re.search(r"return\s+t\s*>=\s*ticks\s*&&\s*reached\s*;", move):
        errors.append("vmoveto completes before rendered cursor arrival")
    if not re.search(r"catch\s*\(RuntimeException\s+ex\)\s*\{\s*abortToQuit\(ex\.getMessage\(\)\)", move):
        errors.append("changed or missing target does not abort movement")
    if not re.search(r"if\s*\(!reached\s*&&\s*System\.currentTimeMillis\(\)\s*-\s*pendingPillAtMs\s*>\s*5000\)\s*\{\s*abortToQuit",
                     move):
        errors.append("stalled cursor does not abort after five seconds")

    try:
        armed = click.index("if (pendingPill != null)")
        recheck = click.index("CaptureHitTarget.reached", armed)
        reject = click.index("if (!reached || num(arg, 0) != 0)", recheck)
        press = click.index("Commander.instance.vButton(num(arg, 0), true)", reject)
        if not armed < recheck < reject < press:
            errors.append("pill recheck does not guard the mouse down")
    except ValueError:
        errors.append("vclick can press before exact-pill recheck")
    if not re.search(r"catch\s*\(RuntimeException\s+ex\)\s*\{\s*abortToQuit\(ex\.getMessage\(\)\)", click):
        errors.append("changed or missing target does not abort click")
    if not re.search(r"if\s*\(!reached\s*\|\|\s*num\(arg,\s*0\)\s*!=\s*0\)\s*\{\s*abortToQuit", click):
        errors.append("unreached or non-left pill click does not abort")
    return errors


class DirectorPillWiringTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = DIRECTOR.read_text(encoding="utf-8")

    def test_actual_director_waits_then_rechecks_before_mouse_down(self):
        self.assertEqual([], wiring_errors(self.source))

    def test_old_tick_only_completion_and_removed_click_guard_are_caught(self):
        mutants = (
            ("return t >= ticks && reached;", "return t >= ticks;"),
            ("if (!reached || num(arg, 0) != 0)", "if (num(arg, 0) != 0)"),
            ("System.currentTimeMillis() - pendingPillAtMs > 5000",
             "System.currentTimeMillis() - pendingPillAtMs < 0"),
        )
        for original, bad in mutants:
            with self.subTest(mutant=bad):
                self.assertEqual(1, self.source.count(original))
                self.assertTrue(wiring_errors(self.source.replace(original, bad)))

        catch_abort = "catch (RuntimeException ex) { abortToQuit(ex.getMessage()); return true; }"
        self.assertEqual(2, self.source.count(catch_abort))
        self.assertTrue(wiring_errors(self.source.replace(
            catch_abort, "catch (RuntimeException ex) { return true; }", 1)))
        self.assertTrue(wiring_errors(self.source.replace(
            catch_abort, "catch (RuntimeException ex) { return true; }")))


if __name__ == "__main__":
    unittest.main()
