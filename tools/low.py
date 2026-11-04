"""Run a build script below normal priority (its javac and SpecialSource inherit it), print only what matters.
python tools/low.py tools/build_client.py   (or tools/build.py)"""
import subprocess, sys

r = subprocess.run([sys.executable] + sys.argv[1:], capture_output=True, text=True, creationflags=0x4000)   # BELOW_NORMAL_PRIORITY_CLASS
lines = (r.stdout + r.stderr).splitlines()
for i, l in enumerate(lines):
    if "error" in l or "built:" in l:
        print("\n".join(lines[i:i + 3]) if "error" in l else l)
sys.exit(r.returncode)
