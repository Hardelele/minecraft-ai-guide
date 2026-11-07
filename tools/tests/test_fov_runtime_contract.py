"""Tie the FOV probe to this exact Minecraft 1.7.10 compile/runtime contract."""

import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CLASSES = ROOT / "lib" / "mc-forge-mcp.jar"


def check_wiring(director, state, commander):
    assert "fovSetting = CaptureFov.setting(" in director
    assert "float target = CaptureFov.setting(degrees);" in state
    assert "CaptureFov.degrees(mc.gameSettings.fovSetting)" in commander


class FovRuntimeContractTests(unittest.TestCase):
    def test_minecraft_field_is_degrees_and_call_sites_use_it(self):
        settings = subprocess.check_output(
            ["javap", "-c", "-private", "-classpath", str(CLASSES),
             "net.minecraft.client.settings.GameSettings"], text=True)
        renderer = subprocess.check_output(
            ["javap", "-c", "-private", "-classpath", str(CLASSES),
             "net.minecraft.client.renderer.EntityRenderer"], text=True)
        self.assertRegex(settings, r"float 70\.0f\s+\d+: putfield\s+#\d+\s+// Field fovSetting:F")
        fov = renderer.split("private float getFOVModifier", 1)[1].split("private void hurtCameraEffect", 1)[0]
        self.assertRegex(fov, r"getfield\s+#\d+\s+// Field net/minecraft/client/settings/GameSettings\.fovSetting:F\s+\d+: fstore\s+4")
        director = (ROOT / "client/src/hardelele/aiguide/client/Director.java").read_text(encoding="utf-8")
        state = (ROOT / "client/src/hardelele/aiguide/client/CaptureFovState.java").read_text(encoding="utf-8")
        commander = (ROOT / "client/src/hardelele/aiguide/client/Commander.java").read_text(encoding="utf-8")
        check_wiring(director, state, commander)
        mutants = (
            (director.replace("fovSetting = CaptureFov.setting(", "fovSetting = CaptureFov.slider(", 1), state, commander),
            (director, state.replace("float target = CaptureFov.setting(degrees);", "float target = CaptureFov.slider(degrees);", 1), commander),
            (director, state, commander.replace("CaptureFov.degrees(mc.gameSettings.fovSetting)", "70 + mc.gameSettings.fovSetting * 40", 1)),
        )
        for altered in mutants:
            with self.assertRaises(AssertionError):
                check_wiring(*altered)


if __name__ == "__main__":
    unittest.main()
