package hardelele.aiguide.client;

/** Keep every captured pixel while satisfying yuv420p's even output dimensions. */
final class CaptureVideoGeometry {
    private CaptureVideoGeometry() { }

    static int encoded(int size) {
        if (size < 1) throw new IllegalArgumentException("empty capture dimension");
        return size + (size & 1);
    }

    static String filter() {
        return "vflip,pad=ceil(iw/2)*2:ceil(ih/2)*2:0:0";
    }
}
