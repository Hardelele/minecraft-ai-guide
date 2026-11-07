"""An orphan recorder sidecar must reject a missing hero output before retry."""
import sys
import tempfile
import unittest
import hashlib
import json
import os
import subprocess
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "promo"))
from capture_config import existing_capture_artifacts, fresh_attempt_prefix, verified_runtime_jars


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

    def test_exactly_one_approved_client_and_server(self):
        with tempfile.TemporaryDirectory() as root:
            game = Path(root) / "game"
            mods = game / "mods"
            mods.mkdir(parents=True)
            client_bytes, server_bytes = b"approved client", b"approved server"
            client_sha = hashlib.sha256(client_bytes).hexdigest()
            server_sha = hashlib.sha256(server_bytes).hexdigest()
            with self.assertRaisesRegex(ValueError, "AIGuideClient.*found 0"):
                verified_runtime_jars(game, client_sha, server_sha)
            client = mods / "AIGuideClient.jar"
            client.write_bytes(client_bytes)
            with self.assertRaisesRegex(ValueError, "AIGuide-.*found 0"):
                verified_runtime_jars(game, client_sha, server_sha)
            server = mods / "AIGuide-0.4.jar"
            server.write_bytes(server_bytes)
            self.assertEqual([{"path": str(client), "sha256": client_sha},
                              {"path": str(server), "sha256": server_sha}],
                             verified_runtime_jars(game, client_sha.upper(), server_sha.upper()))
            with self.assertRaisesRegex(ValueError, "AIGuideClient.*SHA256 mismatch"):
                verified_runtime_jars(game, hashlib.sha256(b"other client").hexdigest(), server_sha)
            with self.assertRaisesRegex(ValueError, "AIGuide-.*SHA256 mismatch"):
                verified_runtime_jars(game, client_sha, hashlib.sha256(b"other server").hexdigest())
            duplicate_client = mods / "AIGuideClient-duplicate.jar"
            duplicate_client.write_bytes(b"duplicate client")
            with self.assertRaisesRegex(ValueError, "AIGuideClient.*found 2"):
                verified_runtime_jars(game, client_sha, server_sha)
            duplicate_client.unlink()
            (mods / "AIGuide-duplicate.jar").write_bytes(b"duplicate server")
            with self.assertRaisesRegex(ValueError, "AIGuide-.*found 2"):
                verified_runtime_jars(game, client_sha, server_sha)

    def test_single_client_jar_gate_before_capture_directory_created(self):
        if os.name != "nt":
            self.skipTest("single-client UNC preflight is Windows-specific")
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            mods = root / "game" / "mods"
            mods.mkdir(parents=True)
            unc_root = "\\\\?\\" + str(root)  # a local extended path for a side-effect-free UNC-prefix control
            env = dict(os.environ, AIGUIDE_CAPTURE_ROOT=str(root), AIGUIDE_MEDIA_ROOT=unc_root,
                       AIGUIDE_FFMPEG=sys.executable, AIGUIDE_FFPROBE=sys.executable)
            expected = hashlib.sha256(b"approved").hexdigest()
            expected_server = hashlib.sha256(b"approved server").hexdigest()
            cmd = [sys.executable, str(Path(__file__).resolve().parents[1] / "promo" / "multicam.py"),
                   "recording", "--name", "must_not_exist", "--game", "game", "--single-client",
                   "--capture-source", "source", "--expected-jar-sha256", expected,
                   "--expected-server-jar-sha256", expected_server,
                   "--ui", str(root / "missing_ui.mp4")]

            def reject(message):
                result = subprocess.run(cmd, env=env, capture_output=True, text=True)
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn(message, result.stderr)
                self.assertFalse((root / "captures" / "must_not_exist").exists())

            reject("found 0")
            client = mods / "AIGuideClient.jar"
            client.write_bytes(b"wrong")
            reject("AIGuideClient*.jar SHA256 mismatch")
            client.write_bytes(b"approved")
            reject("AIGuide-*.jar")  # server absent
            server = mods / "AIGuide-0.4.jar"
            server.write_bytes(b"wrong server")
            reject("AIGuide-*.jar SHA256 mismatch")
            server.write_bytes(b"approved server")
            reject("live Commander UI source does not exist")  # approved JAR passed its gate
            (mods / "AIGuideClient-duplicate.jar").write_bytes(b"duplicate client")
            reject("AIGuideClient*.jar")
            (mods / "AIGuideClient-duplicate.jar").unlink()
            (mods / "AIGuide-duplicate.jar").write_bytes(b"duplicate server")
            reject("AIGuide-*.jar")

    def test_resume_rejects_changed_runtime_before_new_attempt(self):
        if os.name != "nt":
            self.skipTest("single-client UNC preflight is Windows-specific")
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            mods = root / "game" / "mods"
            mods.mkdir(parents=True)
            client_bytes, server_bytes = b"runtime B client", b"runtime B server"
            client_sha, server_sha = hashlib.sha256(client_bytes).hexdigest(), hashlib.sha256(server_bytes).hexdigest()
            (mods / "AIGuideClient-0.2.jar").write_bytes(client_bytes)
            (mods / "AIGuide-0.4.jar").write_bytes(server_bytes)
            dest = root / "captures" / "existing"
            dest.mkdir(parents=True)
            manifest = {"recording": "recording", "requested_start_tick": 0, "fps": 60, "frames": 480,
                        "hidden": True, "tracks": [], "capture_source": "runtime B",
                        "client_sha256": client_sha, "server_sha256": server_sha}
            manifest_path = dest / "manifest.json"
            env = dict(os.environ, AIGUIDE_CAPTURE_ROOT=str(root), AIGUIDE_MEDIA_ROOT="\\\\?\\" + str(root),
                       AIGUIDE_FFMPEG=sys.executable, AIGUIDE_FFPROBE=sys.executable)
            cmd = [sys.executable, str(Path(__file__).resolve().parents[1] / "promo" / "multicam.py"),
                   "recording", "--name", "existing", "--game", "game", "--single-client", "--resume",
                   "--capture-source", "runtime B", "--expected-jar-sha256", client_sha,
                   "--expected-server-jar-sha256", server_sha]
            for field, old_value in (("capture_source", "runtime A"),
                                     ("client_sha256", hashlib.sha256(b"runtime A client").hexdigest()),
                                     ("server_sha256", hashlib.sha256(b"runtime A server").hexdigest())):
                altered = dict(manifest, **{field: old_value})
                manifest_path.write_text(json.dumps(altered), encoding="utf-8")
                original = manifest_path.read_bytes()
                result = subprocess.run(cmd, env=env, capture_output=True, text=True)
                self.assertEqual(2, result.returncode, f"{field}: {result.stderr}")
                self.assertIn("runtime identity differs", result.stderr)
                self.assertEqual(original, manifest_path.read_bytes())
                self.assertEqual([manifest_path], list(dest.iterdir()))


if __name__ == "__main__":
    unittest.main()
