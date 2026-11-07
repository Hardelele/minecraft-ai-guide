"""Narrow source mutants for the actual Recorder call sites, not only helper behavior."""

from pathlib import Path
import unittest


SOURCE = (Path(__file__).resolve().parents[2] / "client/src/hardelele/aiguide/client/Recorder.java").read_text(encoding="utf-8")


def check_wiring(source: str) -> None:
    render = source.split("public void render(TickEvent.RenderTickEvent e)", 1)[1].split("void begin()", 1)[0]
    begin = source.split("void begin()", 1)[1].split("void finish()", 1)[0]
    finish = source.split("void finish()", 1)[1]
    assert "if (pbo == null) begin();\n            requireEncoder();" in render
    assert "byte[] f = freeFrame();" in render
    assert "requireEncoder();\n            byte[] frame = pool.poll(10," in render
    assert '"-vf", CaptureVideoGeometry.filter()' in begin
    assert "CaptureVideoGeometry.encoded(h)" in begin
    assert "ff.waitFor(detached ? 30 : 10," in finish
    assert "ff.destroyForcibly();" in finish


class RecorderWiringTests(unittest.TestCase):
    def test_real_wiring_and_mutants(self):
        check_wiring(SOURCE)
        mutants = (
            SOURCE.replace("            requireEncoder();\n            if (mc.displayWidth", "            if (mc.displayWidth", 1),
            SOURCE.replace("byte[] f = freeFrame();", "byte[] f = pool.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);", 1),
            SOURCE.replace("pool.poll(10,", "pool.poll(200,", 1),
            SOURCE.replace('"-vf", CaptureVideoGeometry.filter()', '"-vf", "vflip"', 1),
            SOURCE.replace("ff.waitFor(detached ? 30 : 10,", "ff.waitFor(", 1),
        )
        for mutant in mutants:
            with self.assertRaises(AssertionError):
                check_wiring(mutant)


if __name__ == "__main__":
    unittest.main()
