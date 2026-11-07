package hardelele.aiguide.client;

/** One logical frame is a full round of camera slots. The extra round drains the PBOs. */
final class CaptureClock {
    final int slots, count;
    final double start;
    int slot, frame;
    CaptureClock(int slots, int count, double start) {
        if (slots < 1 || slots > 4 || count < 1 || !Double.isFinite(start) || start < 0)
            throw new IllegalArgumentException("invalid capture clock");
        this.slots = slots; this.count = count; this.start = start;
    }
    double tick() { return start + Math.min(frame + 1, count) / 3.0; }
    double step() { return slot == 0 && frame < count ? 1.0 / 3 : 0; }
    double cameraStep() { return frame > 0 && frame < count ? 1.0 / 3 : 0; }
    boolean advance() {
        if (++slot == slots) { slot = 0; frame++; }
        return frame > count;
    }
}
