package hardelele.aiguide.client;

/** Pure CPU: no Minecraft, GL, encoder, file I/O or world fixture. */
public final class CaptureClockProbe {
    static int checks;
    static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        for (int slots = 1; slots <= 4; slots++) for (int count : new int[] {1, 2, 480, 3600}) {
            CaptureClock c = new CaptureClock(slots, count, 800);
            double[] pending = new double[slots]; int[] sent = new int[slots];
            double advanced = 0; int renders = 0;
            do {
                int slot = c.slot;
                check(Math.abs(c.tick() - (800 + Math.min(c.frame + 1, count) / 3.0)) < 1e-9, "clock drift");
                if (c.frame > 0) { // two-PBO recorder reads the previous view, not the current one
                    check(Math.abs(pending[slot] - (800 + (sent[slot] + 1) / 3.0)) < 1e-9, "wrong hero frame/tick");
                    sent[slot]++;
                }
                pending[slot] = c.tick();
                advanced += c.step(); renders++;
                if (c.frame == count) check(c.step() == 0 && c.cameraStep() == 0, "drain moved world/camera");
            } while (!c.advance());
            check(renders == slots * (count + 1), "wrong drain count");
            check(Math.abs(advanced - count / 3.0) < 1e-8, "world advanced per camera instead of per round");
            for (int n : sent) check(n == count, "track frame missing");
        }
        for (int slots : new int[] {0, 5}) {
            try { new CaptureClock(slots, 1, 0); throw new AssertionError("invalid slot accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        System.out.println("CaptureClock: " + checks + " checks passed");
    }
}
