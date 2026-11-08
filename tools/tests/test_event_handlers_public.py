"""Forge calls an event handler through a class it generates in another package: the handler's class (and every class
around it) and the method must be public, or the game crashes with IllegalAccessError the first time the event comes
(a non-public PlaceLayer did so on making a world). Checked in the sources, no game needed."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DIRS = [ROOT / "client/src/hardelele/aiguide/client", ROOT / "src/hardelele/aiguide"]
CLASS = re.compile(r"^(\s*)((?:public|protected|private|static|final|abstract)\s+)*(?:class|enum)\s+(\w+)")
STRING = re.compile(r'"(?:\\.|[^"\\])*"')


def problems(text, name):
    out, stack, depth = [], [], 0   # stack: (depth inside the class, public, class name)
    lines = text.split("\n")
    for i, line in enumerate(lines):
        code = STRING.sub('""', line.split("//", 1)[0])
        m = CLASS.match(code)
        if m and "{" in code:
            stack.append((depth + 1, re.search(r"\bpublic\b", code[:m.end()]) is not None, m.group(3)))
        if "@SubscribeEvent" in code:
            rest = re.sub(r"^\([^)]*\)", "", code.split("@SubscribeEvent", 1)[1].strip()).strip()
            if not rest and i + 1 < len(lines):
                rest = lines[i + 1].strip()
            if not rest.startswith("public "):
                out.append(f"{name}:{i + 1}: the handler method is not public")
            for _, pub, cls in stack:
                if not pub:
                    out.append(f"{name}:{i + 1}: class {cls} around the handler is not public")
        depth += code.count("{") - code.count("}")
        while stack and depth < stack[-1][0]:
            stack.pop()
    return out


def main():
    found = []
    for d in DIRS:
        for f in sorted(d.glob("*.java")):
            found += problems(f.read_text(encoding="utf-8"), f.name)
    assert problems("final class A {\n    @SubscribeEvent\n    public void x() {}\n}", "A.java"), "a non-public class not caught"
    assert problems("public final class A {\n    static final class B {\n        @SubscribeEvent public void x() {}\n    }\n}", "A.java"), "a non-public nested class not caught"
    assert problems("public class A {\n    @SubscribeEvent\n    void x() {}\n}", "A.java"), "a non-public method not caught"
    assert not problems("public class A {\n    static class B {\n        int y;\n    }\n    @SubscribeEvent(priority = EventPriority.LOW)\n    public void x() {}\n}", "A.java"), "a closed nested class blamed"
    assert not found, "\n".join(found)
    print("test_event_handlers_public PASS")


if __name__ == "__main__":
    main()
