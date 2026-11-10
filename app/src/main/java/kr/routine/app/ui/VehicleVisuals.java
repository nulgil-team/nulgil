package kr.routine.app.ui;

/** Claude's app-window tokens. Pure Java so sizing and contrast can be checked without Android. */
public final class VehicleVisuals {
    private VehicleVisuals() {}
    public record Palette(int background, int surface, int raised, int text, int secondary, int muted,
                          int primary, int primaryText, int selected, int selectedLine,
                          int warning, int warningText, int road, int roadLine, int scrim) {}
    public static final Palette NIGHT = new Palette(0xff262b30,0xff30363c,0xff3c444b,
        0xfff4f5f0,0xffc3cad0,0xffaab3bb,0xff1a66c2,0xffffffff,0xff1f3a5c,0xff6fb0f7,
        0xff4a3d12,0xffffd257,0xff495159,0xffe8eae5,0xdd080a0c);
    public static final Palette DAY = new Palette(0xffeceee9,0xfffbfbf8,0xffdde0da,
        0xff1c2024,0xff444b52,0xff565e65,0xff0b5cb5,0xffffffff,0xffd5e4f6,0xff0b5cb5,
        0xfffbedbc,0xff6d4b00,0xff5d666c,0xffffffff,0xcc1e2226);

    public record Metrics(int width, int height, float density, float scaledDensity) {
        public Metrics {
            if (width < 1 || height < 1 || !Float.isFinite(density) || density <= 0
                || !Float.isFinite(scaledDensity) || scaledDensity <= 0)
                throw new IllegalArgumentException("Positive window dimensions and density are required");
        }
        public boolean compact() { return width <= 1000; }
        public int padding() { return compact() ? 32 : 56; }
        public int text(int referencePixels) {
            return (int)Math.ceil(Math.max(referencePixels * Math.max(1f, scaledDensity / density), 30f * scaledDensity));
        }
        public int touch(int referencePixels) { return (int)Math.ceil(Math.max(referencePixels, 76f * density)); }
        public int destinationText() { return text(compact() ? 58 : 84); }
        public int etaText() { return text(compact() ? 104 : 148); }
        public int primaryHeight() { return touch(compact() ? 110 : 122); }
        public boolean stackButtons() { return compact() || width - 2 * padding() < text(40) * 24; }
        public boolean stackPreferences() { return compact() || width - 2 * padding() < text(38) * 24; }
        public int routeHeight() { return compact() ? 150 : 190; }
    }
    /** Page changes only; terminal=false after a definitive pre-send veto is never an unconfirmed result. */
    public static String screenAfterState(String current,boolean wasTerminal,boolean nowTerminal,boolean startedMoving) {
        if(startedMoving) return nowTerminal?"guide":"home";
        if(nowTerminal && !wasTerminal && (current.equals("home") || current.equals("guide"))) return "guide";
        if(!nowTerminal && current.equals("guide")) return "home";
        return current;
    }
    public static double contrast(int foreground, int background) {
        double a=luminance(foreground), b=luminance(background);
        return (Math.max(a,b)+0.05)/(Math.min(a,b)+0.05);
    }
    private static double luminance(int color) {
        return .2126*channel((color>>16)&255)+.7152*channel((color>>8)&255)+.0722*channel(color&255);
    }
    private static double channel(int value) {
        double c=value/255.0;
        return c<=.04045 ? c/12.92 : Math.pow((c+.055)/1.055,2.4);
    }
}
