package knox.java;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.TexturePaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * The health tab's picture of a wound seen from above (the author, 2026-11-10: the close-up
 * of the health mockup 3, `.claude/mockups/health/tool/top.js`): the limb a dark silhouette with
 * frost-coloured contour lines that part and redden where it swells, the wound glowing (a pale
 * rim, red, a dark slit, a soft red glow beating with the bleeding), blood, gauze turns, glass,
 * a fracture seen through an X-ray window, a splint. Plain Java2D, no game classes: the game
 * draws it into a texture (KnoxWoundCanvas) and tools/health draws the same into PNGs beside the
 * mockup's shots. Geometry, colours and numbers are the mockup's (each piece says which
 * function of top.js it is); the words are not drawn here - they come out as Text items and the
 * game sets them in Knox's fonts (the layout kit), the offline tool in Fira Sans.
 * Coordinates are the mockup's viewBox units (0..480 x 0..170 for the table).
 */
public final class KnoxWoundArt {

    // ------------------------------------------------------------------ the mockup's numbers

    // mockup top.js: TV
    public static final float X0 = 70, X1 = 474, Y0 = 30, Y1 = 164, CX = 272, CY = 96;
    // mockup top.js: ARM - the limb's top and bottom edges at the box's left and right
    static final float ARM_T0 = 44, ARM_T1 = 48, ARM_B0 = 156, ARM_B1 = 150;
    // mockup top.js: TURN
    public static final float TURN_YT = 40, TURN_YB = 160, TURN_SLANT = 18;
    // mockup top.js: TV_SHARDS - where, which way it sticks out (0 up, clockwise), how long
    public static final float[][] SHARDS = {{-58, 10, -38, 27}, {4, -4, 16, 32}, {58, 14, 58, 24}};
    // mockup top.js: FRX - a broken bone in the window that sees through the skin
    public static final float FRX_WL = CX - 92, FRX_WR = CX + 92, FRX_WT = CY - 22, FRX_WB = CY + 22, FRX_FX = CX + 4;
    public static final float[] FRX_STRAPS = {CX - 108, CX, CX + 108};

    public static final int KIND_NONE = 0, KIND_DEEP = 1, KIND_LACER = 2, KIND_SCRATCH = 3, KIND_GLASS = 4,
            KIND_FRACTURE = 5, KIND_BURN = 6, KIND_BITE = 7, KIND_BULLET = 8;
    public static final int VIEW_TABLE = 0, VIEW_MINI = 1, VIEW_THUMB = 2;
    // mockup mg3.js: HIDE - what the picture leaves out while a mini-game draws it itself
    public static final int HIDE_DIRT = 1, HIDE_WOUND = 2, HIDE_BAND = 4, HIDE_GLASS = 8, HIDE_BONEFAR = 16, HIDE_SPLINT = 32;

    // the mockup's colours
    static final int RED = 0xe0483c, DULL = 0x9a4a40, SAND = 0xf1e3b2, MINT = 0x8fd0b0, ICE = 0x8ec5e0,
            BRIGHT = 0xece8d8, TEXT = 0xd9d6c8, DIM = 0x8f9289, WARN = 0xc9a24a, INFECT = 0xb9c24a, BLOOD_INK = 0xa3121e;

    /** What a picture shows: one wound of a body part and what was done to it (Lua fills it). */
    public static final class Scene {
        public int kind;
        public boolean bleed;        // the look is bleeding (the blood runs, the glow beats)
        public boolean wetBand;      // bleeding under a bandage (a red spot through it)
        public boolean dirt;         // dirt in the wound
        public boolean infected;     // an infected wound, not cleaned yet
        public boolean cleaned;
        public boolean stitched;
        public boolean band;         // a clean bandage on it
        public boolean dirtyBand;    // a dirty one
        public boolean splinted;
        public boolean glassOut;
        public boolean bullet;       // a bullet still in
        public float progress;       // healing 0..1 (a scratch's lines)
        public int lookRgb = RED;    // the colour of its look (the depth scale's marks)
        public int hide;
        public boolean noNotes;
        public int view;

        public void set(Scene o) {
            kind = o.kind; bleed = o.bleed; wetBand = o.wetBand; dirt = o.dirt; infected = o.infected; cleaned = o.cleaned;
            stitched = o.stitched; band = o.band; dirtyBand = o.dirtyBand; splinted = o.splinted; glassOut = o.glassOut;
            bullet = o.bullet; progress = o.progress; lookRgb = o.lookRgb; hide = o.hide; noNotes = o.noNotes; view = o.view;
        }

        public long sig() {
            long s = kind;
            s = s * 31 + (bleed ? 1 : 0) + (wetBand ? 2 : 0) + (dirt ? 4 : 0) + (infected ? 8 : 0) + (cleaned ? 16 : 0)
                    + (stitched ? 32 : 0) + (band ? 64 : 0) + (dirtyBand ? 128 : 0) + (splinted ? 256 : 0) + (glassOut ? 512 : 0)
                    + (bullet ? 1024 : 0) + (noNotes ? 2048 : 0);
            s = s * 31 + Math.round(progress * 100);
            s = s * 31 + lookRgb;
            s = s * 31 + hide;
            s = s * 31 + view;
            return s;
        }

        /** Something in it moves (the glow beats, the blood runs, the frost breathes...). */
        public boolean animated() {
            if (kind == KIND_NONE) return false;
            if (kind == KIND_FRACTURE) return true;
            if (kind == KIND_GLASS) return !glassOut;
            if (bleed || infected) return true;
            return false;
        }
    }

    /** A word on the picture: the game sets it in its own fonts (viewBox units, the mockup's size). */
    public static final class Text {
        public String key;           // a translation key's tail (IGUI_KnoxHP_<key>), or the text itself with raw
        public boolean raw;
        public float x, y;           // the baseline's point (as the SVG's text x, y)
        public int rgb;
        public float a = 1;
        public float size;           // the mockup's font-size
        public int anchor;           // -1 ends at x, 0 middle, 1 starts at x
        public int weight = 600;
        public float spacing;        // letter-spacing
    }

    // ------------------------------------------------------------------ drawing state

    public Graphics2D g;
    public double scale = 1;         // pixels per unit (for the blurs and the patterns)
    public double t;                 // seconds (the animations)
    public double beatPh;            // the heart's phase 0..1 (KnoxWoundArt.ecg's; the glow beats with it)
    public final ArrayList<Text> texts = new ArrayList<>();
    private int textsUsed;
    private final ArrayList<float[]> notes = new ArrayList<>();
    private final ArrayList<String> noteKeys = new ArrayList<>();

    public void begin(Graphics2D g, double scale, double t, double beatPh) {
        this.g = g;
        this.scale = scale;
        this.t = t;
        this.beatPh = beatPh;
        textsUsed = 0;
        notes.clear();
        noteKeys.clear();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    /** The words of the last picture (the first `count()` of `texts`). */
    public int count() {
        return textsUsed;
    }

    public Text text(String key, float x, float y, int rgb, float a, float size, int anchor, int weight, float spacing) {
        Text tx;
        if (textsUsed < texts.size()) tx = texts.get(textsUsed);
        else {
            tx = new Text();
            texts.add(tx);
        }
        textsUsed++;
        tx.key = key;
        tx.raw = false;
        tx.x = x;
        tx.y = y;
        tx.rgb = rgb;
        tx.a = a;
        tx.size = size;
        tx.anchor = anchor;
        tx.weight = weight;
        tx.spacing = spacing;
        return tx;
    }

    // ------------------------------------------------------------------ small helpers

    static Color col(int rgb, double a) {
        int al = (int) Math.round(Math.max(0, Math.min(1, a)) * 255);
        return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, al);
    }

    public void fill(Shape s, int rgb, double a) {
        if (a <= 0.002) return;
        g.setColor(col(rgb, a));
        g.fill(s);
    }

    public void fill(Shape s, Paint p) {
        g.setPaint(p);
        g.fill(s);
    }

    private final HashMap<Long, BasicStroke> strokes = new HashMap<>();

    BasicStroke pen(double w, boolean round, boolean roundCap) {
        long key = Math.round(w * 1000) * 4 + (round ? 1 : 0) + (roundCap ? 2 : 0);
        BasicStroke b = strokes.get(key);
        if (b == null) {
            b = new BasicStroke((float) w, roundCap ? BasicStroke.CAP_ROUND : BasicStroke.CAP_BUTT,
                    round ? BasicStroke.JOIN_ROUND : BasicStroke.JOIN_MITER, 4f);
            strokes.put(key, b);
        }
        return b;
    }

    public void stroke(Shape s, int rgb, double a, double w) {
        stroke(s, rgb, a, w, false, false);
    }

    public void stroke(Shape s, int rgb, double a, double w, boolean roundJoin, boolean roundCap) {
        if (a <= 0.002) return;
        g.setColor(col(rgb, a));
        g.setStroke(pen(w, roundJoin, roundCap));
        g.draw(s);
    }

    public void dashed(Shape s, int rgb, double a, double w, float on, float off) {
        if (a <= 0.002) return;
        g.setColor(col(rgb, a));
        g.setStroke(new BasicStroke((float) w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 4f, new float[]{on, off}, 0f));
        g.draw(s);
    }

    public static Ellipse2D.Float ellipse(double cx, double cy, double rx, double ry) {
        return new Ellipse2D.Float((float) (cx - rx), (float) (cy - ry), (float) (rx * 2), (float) (ry * 2));
    }

    public static Rectangle2D.Float rect(double x, double y, double w, double h) {
        return new Rectangle2D.Float((float) x, (float) y, (float) w, (float) h);
    }

    public static Path2D.Float line(double x1, double y1, double x2, double y2) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return p;
    }

    public static Path2D.Float quad(double x0, double y0, double cx, double cy, double x1, double y1) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(x0, y0);
        p.quadTo(cx, cy, x1, y1);
        return p;
    }

    static double clamp(double v, double a, double b) {
        return Math.max(a, Math.min(b, v));
    }

    // mockup top.js: hash
    public static double hash(double i, double k) {
        double v = Math.sin(i * 12.9898 + k * 78.233) * 43758.5453;
        return v - Math.floor(v);
    }

    /** mockup top.js: the scripts' `rnd` (Park-Miller: r = r * 16807 % 2147483647). */
    public static final class Rnd {
        long r;

        public Rnd(long seed) {
            r = seed;
        }

        public double next() {
            r = (r * 16807L) % 2147483647L;
            return r / 2147483647.0;
        }
    }

    // a CSS keyframe's ease-out (the animations' "ease-out" between two keyframes)
    static double easeOut(double x) {
        return 1 - (1 - x) * (1 - x);
    }

    static double easeInOut(double x) {
        return x < .5 ? 2 * x * x : 1 - Math.pow(-2 * x + 2, 2) / 2;
    }

    static double frac(double v) {
        return v - Math.floor(v);
    }

    // ------------------------------------------------------------------ the heart (the ECG, the beat)

    // mockup health_v1: ecgY
    public static double ecgY(double ph) {
        return .12 * gs(ph, .16, .03) - .16 * gs(ph, .265, .008) + 1 * gs(ph, .30, .012) - .26 * gs(ph, .335, .01) + .24 * gs(ph, .52, .05);
    }

    static double gs(double ph, double c, double w) {
        return Math.exp(-((ph - c) * (ph - c)) / (2 * w * w));
    }

    /**
     * The CSS `beatc` (scale 1 -> 1.1 at 18% -> 1, ease-out) on the heart's phase: the glow's
     * swell, 0..1. The mockup ran it on its own .58 s; here it is the ECG's beat (the R peak at .30
     * of the period, as the model's light), so the wound beats with the line in the panel.
     */
    public static double beatSwell(double ph) {
        double up = .18;
        double u = frac(ph - .30 + up);   // the swell's top (18% of the keyframes) on the R peak
        if (u < up) return easeOut(u / up);
        return 1 - easeOut((u - up) / (1 - up));
    }

    // ------------------------------------------------------------------ a gash (deep, cut)

    /** mockup top.js: GEO + geom(kind) - a gash along x: its middle line and half width. */
    public static final class Geo {
        public final float L, W;
        public final int depth;
        public final float cx = CX, cy = CY;

        Geo(float L, float W, int depth) {
            this.L = L;
            this.W = W;
            this.depth = depth;
        }

        public double cyAt(double x) {
            return cy + 3 * Math.sin((x - cx) / L * 1.6);
        }

        public double hw(double x, int side, double open, double f) {
            double u = (x - cx) / L, k = 1 - u * u;
            if (k <= 0) return 0;
            return Math.max(0, (W * Math.pow(k, .62) * open + (1.2 * Math.sin(x * .41 + side * 2.1) + .7 * Math.sin(x * .93 + side * 5)) * k * Math.min(1, open * 1.5)) * f);
        }

        /** mockup top.js: geom().ring - the gash's outline at f of its width. */
        public Path2D.Float ring(double f, double open) {
            final int N = 28;
            Path2D.Float p = new Path2D.Float();
            for (int i = 0; i <= N; i++) {
                double x = cx - L + 2 * L * i / N, yc = cyAt(x);
                if (i == 0) p.moveTo(x, yc - hw(x, 0, open, f));
                else p.lineTo(x, yc - hw(x, 0, open, f));
            }
            for (int i = N; i >= 0; i--) {
                double x = cx - L + 2 * L * i / N, yc = cyAt(x);
                p.lineTo(x, yc + hw(x, 1, open, f));
            }
            p.closePath();
            return p;
        }
    }

    public static final Geo DEEP = new Geo(92, 25, 2), LACER = new Geo(64, 15, 1);

    public static Geo geo(int kind) {
        return kind == KIND_DEEP || kind == KIND_BULLET ? DEEP : LACER;
    }

    static boolean gash(int kind) {
        return kind == KIND_DEEP || kind == KIND_LACER || kind == KIND_BITE || kind == KIND_BULLET;
    }

    // mockup top.js: dirtSpecks
    public static float[][] dirtSpecks(Geo g) {
        float[][] out = new float[10][2];
        for (int i = 0; i < 10; i++) {
            double x = g.cx + g.L * (-.72 + 1.44 * i / 9);
            int side = i % 2;
            double f = .62 + .2 * ((i * 7) % 3);
            out[i][0] = (float) x;
            out[i][1] = (float) (g.cyAt(x) + (side == 1 ? 1 : -1) * g.hw(x, side, 1, f));
        }
        return out;
    }

    // mockup top.js: swellOf - how far the swelling reaches round a wound (0: none)
    public static float swellOf(Scene s) {
        if (s.kind == KIND_FRACTURE) return s.splinted ? 0 : 70;
        if (!gash(s.kind)) return 0;
        boolean bandaged = s.band && !s.dirtyBand;
        return bandaged && !s.infected ? 0 : geo(s.kind).L;
    }

    // ------------------------------------------------------------------ the limb

    static double armTop(double x) {
        double xa = X0 - 20, xb = X1 + 20;
        return ARM_T0 + (ARM_T1 - ARM_T0) * (x - xa) / (xb - xa);
    }

    static double armBottom(double x) {
        double xa = X0 - 20, xb = X1 + 20;
        return ARM_B0 + (ARM_B1 - ARM_B0) * (x - xa) / (xb - xa);
    }

    /** The limb's outline (mockup top.js: inkLimb's `body`, the clip cArm). */
    public static Path2D.Float arm() {
        double xa = X0 - 20, xb = X1 + 20;
        Path2D.Float p = new Path2D.Float();
        p.moveTo(xa, ARM_T0);
        p.lineTo(xb, ARM_T1);
        p.lineTo(xb, ARM_B1);
        p.lineTo(xa, ARM_B0);
        p.closePath();
        return p;
    }

    /**
     * mockup top.js: inkLimb, LIMB = 'topo' - the box's ground, the dark limb, its contour lines
     * pushed apart by the swelling and reddening near it, the swelling's rings, the frost outline;
     * `plain` (the small pictures): no lines.
     */
    public void limb(float swell, boolean plain) {
        double xa = X0 - 20, xb = X1 + 20;
        fill(rect(X0, Y0, X1 - X0, Y1 - Y0), 0x1a1d1d, 1);
        Path2D.Float body = arm();
        fill(body, 0x0f1213, 1);
        if (!plain) {
            Shape clip = g.getClip();
            g.clip(body);
            double sw = swell > 0 ? swell : 1, amp = swell > 0 ? 22 * Math.min(1, swell / 80) : 0;
            for (int k = -7; k <= 7; k++) {
                double ph = k / 8.0 * Math.PI / 2, near = 0;
                Path2D.Float d = new Path2D.Float();
                for (int i = 0; i <= 60; i++) {
                    double x = xa + (xb - xa) * i / 60, m = (armTop(x) + armBottom(x)) / 2, half = (armBottom(x) - armTop(x)) / 2;
                    double y0 = m + half * Math.sin(ph), dxw = (x - CX) / (sw * 1.25), e = swell > 0 ? Math.exp(-dxw * dxw) : 0;
                    double off = y0 - CY, push = Math.signum(off == 0 ? 1 : off) * amp * e * Math.exp(-(off * off) / 1400);
                    near = Math.max(near, e * Math.exp(-(off * off) / 1400));
                    if (i == 0) d.moveTo(x, y0 + push);
                    else d.lineTo(x, y0 + push);
                }
                boolean odd = k % 2 != 0;
                stroke(d, near > .5 ? 0xb0503f : 0x9fc4d2, odd ? .35 : .6, odd ? .6 : .9);
            }
            if (swell > 0) {
                Geo dg = DEEP;
                for (int r = 1; r <= 3; r++) {
                    stroke(ellipse(CX, CY, swell + 10 + r * 9, Math.min(dg.W, swell * .3) + 6 + r * 7), 0xb0503f, .55 - r * .13, .8);
                }
            }
            g.setClip(clip);
        }
        Path2D.Float edge = new Path2D.Float();
        edge.moveTo(xa, ARM_T0);
        edge.lineTo(xb, ARM_T1);
        edge.moveTo(xb, ARM_B1);
        edge.lineTo(xa, ARM_B0);
        stroke(edge, 0xcfe3ea, 1, 1);
        if (!plain && swell > 0) text("ArtSwell", X0 + (X1 - X0) - 8, (float) armBottom(X1) - 6, 0xb0503f, .85f, 7.5f, -1, 600, .6f);
    }

    // ------------------------------------------------------------------ blurred pieces (cached)

    private final HashMap<String, Sprite> sprites = new HashMap<>();

    /** A blurred picture kept in pixels: drawn at its place in units. */
    static final class Sprite {
        BufferedImage img;
        double x, y, w, h;   // in units
    }

    /** Draws `shapes` (fills) blurred by sigma units into a sprite, once per key and scale. */
    Sprite blurred(String key, Shape[] shapes, int[] rgb, double[] alpha, double sigma) {
        String k = key + "@" + Math.round(scale * 100);
        Sprite sp = sprites.get(k);
        if (sp != null) return sp;
        Rectangle2D b = null;
        for (Shape s : shapes) b = b == null ? s.getBounds2D() : b.createUnion(s.getBounds2D());
        double m = sigma * 3;
        sp = new Sprite();
        sp.x = b.getX() - m;
        sp.y = b.getY() - m;
        sp.w = b.getWidth() + 2 * m;
        sp.h = b.getHeight() + 2 * m;
        int pw = Math.max(1, (int) Math.ceil(sp.w * scale)), ph = Math.max(1, (int) Math.ceil(sp.h * scale));
        BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D bg = img.createGraphics();
        bg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        bg.scale(scale, scale);
        bg.translate(-sp.x, -sp.y);
        for (int i = 0; i < shapes.length; i++) {
            bg.setColor(col(rgb[i], alpha[i]));
            bg.fill(shapes[i]);
        }
        bg.dispose();
        blur(((DataBufferInt) img.getRaster().getDataBuffer()).getData(), pw, ph, sigma * scale);
        sp.img = img;
        if (sprites.size() > 64) sprites.clear();
        sprites.put(k, sp);
        return sp;
    }

    void draw(Sprite sp, double a, double sx, double sy, double ox, double oy) {
        if (a <= 0.002) return;
        Composite was = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(1, a)));
        AffineTransform at = g.getTransform();
        g.translate(ox, oy);
        g.scale(sx, sy);
        g.translate(-ox, -oy);
        g.drawImage(sp.img, new AffineTransform(sp.w / sp.img.getWidth(), 0, 0, sp.h / sp.img.getHeight(), sp.x, sp.y), null);
        g.setTransform(at);
        g.setComposite(was);
    }

    /** A gaussian blur (three box passes) of premultiplied ARGB, in place. */
    public static void blur(int[] px, int w, int h, double sigma) {
        if (sigma < .5) return;
        int r = (int) Math.round((Math.sqrt(4 * sigma * sigma + 1) - 1) / 2);
        if (r < 1) return;
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 3; pass++) {
            boxH(px, tmp, w, h, r);
            boxV(tmp, px, w, h, r);
        }
    }

    private static void boxH(int[] src, int[] dst, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int o = y * w;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int x = -r; x <= r; x++) {
                int c = (x < 0 || x >= w) ? 0 : src[o + x];
                sa += c >>> 24; sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
            }
            for (int x = 0; x < w; x++) {
                dst[o + x] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int xi = x + r + 1, xo = x - r;
                int ci = xi < w ? src[o + xi] : 0, co = xo >= 0 ? src[o + xo] : 0;
                sa += (ci >>> 24) - (co >>> 24);
                sr += ((ci >> 16) & 255) - ((co >> 16) & 255);
                sg += ((ci >> 8) & 255) - ((co >> 8) & 255);
                sb += (ci & 255) - (co & 255);
            }
        }
    }

    private static void boxV(int[] src, int[] dst, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int y = -r; y <= r; y++) {
                int c = (y < 0 || y >= h) ? 0 : src[y * w + x];
                sa += c >>> 24; sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int yi = y + r + 1, yo = y - r;
                int ci = yi < h ? src[yi * w + x] : 0, co = yo >= 0 ? src[yo * w + x] : 0;
                sa += (ci >>> 24) - (co >>> 24);
                sr += ((ci >> 16) & 255) - ((co >> 16) & 255);
                sg += ((ci >> 8) & 255) - ((co >> 8) & 255);
                sb += (ci & 255) - (co & 255);
            }
        }
    }

    // ------------------------------------------------------------------ patterns

    private final HashMap<String, TexturePaint> patterns = new HashMap<>();

    /** mockup make_v3 defs: a pattern tile drawn by `art` at 12 px a unit. */
    interface Tile {
        void draw(KnoxWoundArt a);
    }

    TexturePaint pattern(String key, double tw, double th, Tile tile) {
        TexturePaint p = patterns.get(key);
        if (p != null) return p;
        double k = 12;
        int pw = (int) Math.round(tw * k), ph = (int) Math.round(th * k);
        BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
        Graphics2D tg = img.createGraphics();
        tg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        tg.scale(pw / tw, ph / th);
        Graphics2D keep = g;
        g = tg;
        tile.draw(this);
        // (a tile's lines wrap round its edges, as the SVG pattern's do)
        g = keep;
        tg.dispose();
        p = new TexturePaint(img, new Rectangle2D.Double(0, 0, tw, th));
        patterns.put(key, p);
        return p;
    }

    // pMusT: muscle fibres
    TexturePaint pMus() {
        return pattern("mus", 16, 5, a -> {
            Path2D.Float p = new Path2D.Float();
            p.moveTo(0, 2.5);
            p.quadTo(4, 1.4, 8, 2.5);
            p.quadTo(12, 3.6, 16, 2.5);
            a.stroke(p, 0x7a2622, 1, .7);
        });
    }

    // pBoneT / pBoneX: the bone's pores (light / X-ray)
    TexturePaint pBone(boolean xray) {
        return pattern(xray ? "bonex" : "bonet", 12, 10, a -> {
            a.fill(ellipse(3, 3, 1, 1), xray ? 0x82adc3 : 0xb3a88c, 1);
            a.fill(ellipse(9, 7.5, 1.3, 1.3), xray ? 0x8cb6cb : 0xbdb296, 1);
        });
    }

    // pWood: a board's grain
    TexturePaint pWood() {
        return pattern("wood", 40, 5, a -> {
            a.stroke(line(0, 2.5, 26, 2.5), 0x5a4f38, 1, .8);
            a.stroke(line(30, 2.5, 38, 2.5), 0x5a4f38, 1, .8);
        });
    }

    // pMesh: the gauze's open weave (2.8 units, turned 9 degrees - see mesh()). The mockup draws the
    // turn through fFray (fractal noise shifting it by up to 1.2 units), which makes the weave uneven;
    // that noise is baked once into a 10-cell tile here instead of being run on every frame (2026-11-10).
    static final int MESH_CELLS = 10, MESH_LAT = 31;

    TexturePaint pMesh() {
        TexturePaint p = patterns.get("mesh");
        if (p != null) return p;
        double k = 12, T = 2.8 * MESH_CELLS;
        int n = (int) Math.round(T * k);
        BufferedImage src = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D tg = src.createGraphics();
        tg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        tg.scale(k, k);
        Graphics2D keep = g;
        g = tg;
        for (int c = 0; c < MESH_CELLS; c++) stroke(line(0, .7 + 2.8 * c, T, .7 + 2.8 * c), 0xf1ecdf, 1, .95);
        for (int c = 0; c < MESH_CELLS; c++) stroke(line(.7 + 2.8 * c, 0, .7 + 2.8 * c, T), 0xe4ddcb, 1, .85);
        g = keep;
        tg.dispose();
        // two wrapping value-noise fields (x and y shift), two octaves, about a unit across - as fFray
        double[] nx = new double[MESH_LAT * MESH_LAT], ny = new double[MESH_LAT * MESH_LAT];
        double[] nx2 = new double[4 * MESH_LAT * MESH_LAT], ny2 = new double[4 * MESH_LAT * MESH_LAT];
        Rnd rnd = new Rnd(4);
        for (int q = 0; q < nx.length; q++) {
            nx[q] = rnd.next() * 2 - 1;
            ny[q] = rnd.next() * 2 - 1;
        }
        for (int q = 0; q < nx2.length; q++) {
            nx2[q] = rnd.next() * 2 - 1;
            ny2[q] = rnd.next() * 2 - 1;
        }
        int[] in = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();
        BufferedImage dst = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        int[] out = ((DataBufferInt) dst.getRaster().getDataBuffer()).getData();
        double shift = 2.4 * .3 * k;   // feDisplacementMap scale 2.4 x the noise's spread, in tile pixels
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double u = (double) x / n, v = (double) y / n;
                double dx = (lattice(nx, MESH_LAT, u, v) + .5 * lattice(nx2, 2 * MESH_LAT, u, v)) / 1.5;
                double dy = (lattice(ny, MESH_LAT, u, v) + .5 * lattice(ny2, 2 * MESH_LAT, u, v)) / 1.5;
                int sx = Math.floorMod((int) Math.round(x + dx * shift), n), sy = Math.floorMod((int) Math.round(y + dy * shift), n);
                out[y * n + x] = in[sy * n + sx];
            }
        }
        p = new TexturePaint(dst, new Rectangle2D.Double(0, 0, T, T));
        patterns.put("mesh", p);
        return p;
    }

    /** A wrapping value-noise field of m x m random values, read smoothly at u, v (0..1). */
    static double lattice(double[] f, int m, double u, double v) {
        double fx = u * m, fy = v * m;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        double tx = fx - x0, ty = fy - y0;
        tx = tx * tx * (3 - 2 * tx);
        ty = ty * ty * (3 - 2 * ty);
        int xa = Math.floorMod(x0, m), xb = Math.floorMod(x0 + 1, m), ya = Math.floorMod(y0, m), yb = Math.floorMod(y0 + 1, m);
        double a = f[ya * m + xa] + (f[ya * m + xb] - f[ya * m + xa]) * tx;
        double b = f[yb * m + xa] + (f[yb * m + xb] - f[yb * m + xa]) * tx;
        return a + (b - a) * ty;
    }

    /** fFray along an edge: a smooth shift of about half a unit, the same for the same y (no shimmer as it grows). */
    static double fray(int i, double y) {
        double f = y * 1.1;
        int a = (int) Math.floor(f);
        double t = f - a;
        t = t * t * (3 - 2 * t);
        double h0 = hash(i * 7 + 3, a), h1 = hash(i * 7 + 3, a + 1);
        return (h0 + (h1 - h0) * t - .5) * 1.4;
    }

    /** Fills `area` with the gauze's weave, turned 9 degrees as the mockup's pMesh. */
    void mesh(Shape area, double a) {
        if (a <= 0.002) return;
        Shape clip = g.getClip();
        Composite was = g.getComposite();
        if (a < .999) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) a));
        g.clip(area);
        Rectangle2D b = area.getBounds2D();
        AffineTransform at = g.getTransform();
        g.rotate(Math.toRadians(9));
        double d = Math.hypot(b.getWidth(), b.getHeight());
        double cxr = b.getCenterX() * Math.cos(Math.toRadians(-9)) - b.getCenterY() * Math.sin(Math.toRadians(-9));
        double cyr = b.getCenterX() * Math.sin(Math.toRadians(-9)) + b.getCenterY() * Math.cos(Math.toRadians(-9));
        g.setPaint(pMesh());
        g.fill(rect(cxr - d / 2 - 3, cyr - d / 2 - 3, d + 6, d + 6));
        g.setTransform(at);
        g.setClip(clip);
        g.setComposite(was);
    }

    void patterned(Shape area, TexturePaint p) {
        g.setPaint(p);
        g.fill(area);
    }

    // ------------------------------------------------------------------ the wound (glow, rim, red, slit)

    /** mockup top.js: inkWound - the glow, a pale rim, red, a dark slit, its outline. */
    public void inkWound(Geo geo, double open, int stroke, boolean bleed) {
        int ob = (int) Math.round(open * 20);
        Sprite glow = blurred("glow" + geo.L + "_" + ob, new Shape[]{geo.ring(1.6, ob / 20.0)}, new int[]{0xb0202c}, new double[]{1}, 6);
        double s = bleed ? 1 + .1 * beatSwell(beatPh) : 1;
        draw(glow, bleed ? .75 : .45, s, s, geo.cx, geo.cy);
        fill(geo.ring(1.06, open), 0xf2b9ab, 1);
        fill(geo.ring(.8, open), 0xc23a44, 1);
        fill(geo.ring(geo.depth >= 2 ? .34 : .22, open), 0x3e0610, 1);
        stroke(geo.ring(1.06, open), stroke, .7, .8, true, false);
    }

    /** mockup top.js: bloodSvg - the blood on the skin under the wound, its drips, the spurts. */
    public void blood(Geo geo) {
        int red = BLOOD_INK;
        double xa = geo.cx - .7 * geo.L, xb = geo.cx + .78 * geo.L;
        final int N = 18;
        Path2D.Float ooze = new Path2D.Float();
        double top = 1e9;
        for (int i = 0; i <= N; i++) {
            double tt = (double) i / N, x = xa + (xb - xa) * tt, y = geo.cyAt(x) + geo.hw(x, 1, 1, 1);
            if (i == 0) ooze.moveTo(x, y - .6);
            else ooze.lineTo(x, y - .6);
            top = Math.min(top, y - .6);
        }
        for (int i = N; i >= 0; i--) {
            double tt = (double) i / N, x = xa + (xb - xa) * tt, y = geo.cyAt(x) + geo.hw(x, 1, 1, 1);
            ooze.lineTo(x, y + 1 + (3 + 3.2 * Math.sin(tt * 9 + 1) + 1.6 * Math.sin(tt * 23)) * Math.sqrt(Math.sin(Math.PI * tt)));
        }
        ooze.closePath();
        // (the CSS `ooze`: scaleY 1 -> 1.14 from its top, on the beat)
        double sy = 1 + .14 * beatSwell(beatPh);
        AffineTransform at = g.getTransform();
        Rectangle2D ob = ooze.getBounds2D();
        g.translate(0, ob.getY());
        g.scale(1, sy);
        g.translate(0, -ob.getY());
        fill(ooze, red, 1);
        g.setTransform(at);
        double[][] drips = {{-.42, 3.4, 0}, {.08, 4.2, 0}, {.52, 2.6, 148}, {-.14, 2, 134}};
        for (double[] d : drips) {
            double x = geo.cx + d[0] * geo.L, y0 = geo.cyAt(x) + geo.hw(x, 1, 1, 1) + 2, ye = d[2] != 0 ? d[2] : Y1 + 6;
            stroke(quad(x, y0, x + 2.2, (y0 + ye) / 2, x + .6, ye), red, 1, d[1], false, true);
            if (d[2] != 0) fill(ellipse(x + .6, ye, d[1] * .85, d[1] * .85), red, 1);
        }
        // the spurts: rise 16 and fade (the CSS `spurt`, .58 s each, a little after one another)
        for (int i = 0; i < 4; i++) {
            double x = geo.cx - 16 + i * 11, y = geo.cyAt(x) - geo.hw(x, 0, 1, 1) - 3 - (i % 2) * 4, r = 2 - (i % 2) * .6;
            double u = frac(beatPh - .30 + i * .14 / .58);
            double op = u < .15 ? u / .15 : 1 - (u - .15) / .85, dy = -16 * easeOut(u);
            fill(ellipse(x, y + dy, r, r), red, op);
        }
    }

    // ------------------------------------------------------------------ the bandage

    /** mockup top.js: bandGeo - where the bandage lies (its left and span). */
    public static float[] bandGeo(int kind) {
        float L = kind == KIND_DEEP || kind == KIND_BULLET ? DEEP.L : (kind == KIND_LACER || kind == KIND_BITE) ? LACER.L : 44;
        float span = 2 * L + 30;
        return new float[]{CX - span / 2, span};
    }

    /** mockup top.js: turnGeo - n turns over the span: their step and width. */
    public static float[] turnGeo(float[] bg, int n) {
        float step = bg[1] / n;
        return new float[]{step, step * 1.55f, n};
    }

    // mockup top.js: turnLeft
    public static double turnLeft(float[] bg, float[] tg, double i) {
        return bg[0] + i * tg[0] - (tg[1] - tg[0]) / 2 - TURN_SLANT / 2;
    }

    // mockup top.js: turnLook - every turn its own lean, bow, width
    static double lookLean(double i) { return (hash(i, 1) - .5) * 28; }
    static double lookBow(double i) { return (hash(i, 2) - .5) * 18; }
    static double lookWide(double i) { return 1 + (hash(i, 3) - .5) * .36; }
    static double lookPh(double i) { return hash(i, 4) * 6.28; }

    // mockup top.js: turnX - a turn's left edge at y (base: its left edge at the top)
    public static double turnX(double base, int i, double y) {
        double u = (y - TURN_YT) / (TURN_YB - TURN_YT);
        long ri = Math.round((double) i);
        return base + (TURN_SLANT + lookLean(ri)) * u + lookBow(ri) * Math.sin(Math.PI * u);
    }

    /**
     * mockup top.js: turnSvg - one gauze turn from the top down to ye: see-through gauze with a
     * denser patch here and there, the weave, darker where it wraps over the limb's edges, dirty
     * yellow, creases, loose threads off its free edge and fuzz on it. base: its left edge at the top.
     */
    public void turn(float[] tg, int i, double ye, boolean dirty, double base) {
        double yt = TURN_YT;
        if (ye <= yt) return;
        // (a point a unit apart, not 3 as the mockup, so its fFray noise on the outline can be followed - fray())
        int N = Math.max(2, (int) Math.ceil(ye - yt));
        double[] lx = new double[N + 1], ly = new double[N + 1], rx = new double[N + 1];
        double ph = lookPh(i), wide = lookWide(i);
        for (int k = 0; k <= N; k++) {
            double y = yt + (ye - yt) * k / N, x = turnX(base, i, y);
            double fr = Math.sin(y * .23 + i * 3) * .6 + Math.sin(y * .71 + i) * .25 + Math.sin(y * .06 + ph) * 3;
            lx[k] = x + fr + fray(i, y);
            ly[k] = y;
            rx[k] = x + tg[1] * wide + Math.sin(y * .05 + ph * 2) * 3 + fray(i + 50, y);
        }
        Path2D.Float d = new Path2D.Float(), edge = new Path2D.Float();
        for (int k = 0; k <= N; k++) {
            if (k == 0) {
                d.moveTo(lx[k], ly[k]);
                edge.moveTo(lx[k] - 1, ly[k]);
            } else {
                d.lineTo(lx[k], ly[k]);
                edge.lineTo(lx[k] - 1, ly[k]);
            }
        }
        for (int k = N; k >= 0; k--) d.lineTo(rx[k], ly[k]);
        d.closePath();
        Rnd rnd = new Rnd(97 + i * 31L);
        stroke(edge, 0x000000, .3, 2);
        fill(d, 0xe9e3d3, .34);
        Shape clip = g.getClip();
        g.clip(d);
        for (int k = 0; k < 4; k++) {
            double y = yt + (TURN_YB - yt) * rnd.next();
            if (y > ye) continue;
            double x = turnX(base, i, y) + tg[1] * (.2 + .6 * rnd.next());
            double erx = 6 + 10 * rnd.next(), ery = 10 + 18 * rnd.next(), op = .25 + .3 * rnd.next();
            fill(ellipse(x, y, erx, ery), 0xe9e3d3, op);
        }
        Rectangle2D.Float r = rect(turnX(base, i, yt) - 4, yt, tg[1] + TURN_SLANT + 8, ye - yt);
        mesh(r, 1);
        fill(r, wrapPaint());
        if (dirty) fill(d, 0x7a6a34, .32);
        // creases where it pulled: short curved folds from the edges in, a dark line with a light one beside
        int nk = 2 + (int) Math.floor(hash(i, 5) * 2);
        for (int k = 0; k < nk; k++) {
            double y0 = yt + 12 + (TURN_YB - yt - 24) * hash(i, 6 + k);
            if (y0 > ye - 4) continue;
            boolean fromL = hash(i, 9 + k) < .5;
            double x0 = turnX(base, i, y0) + (fromL ? 2 : tg[1] * wide - 2);
            double dx = (fromL ? 1 : -1) * tg[1] * (.3 + .3 * hash(i, 12 + k)), dy = (hash(i, 15 + k) - .5) * 26;
            stroke(quad(x0, y0, x0 + dx * .5, y0 + dy * .2 - 3, x0 + dx, y0 + dy), 0x000000, .28, 1.1);
            stroke(quad(x0, y0 + 1.2, x0 + dx * .5, y0 + 1.2 + dy * .2 - 3, x0 + dx, y0 + 1.2 + dy), 0xfffdf6, .45, .6);
        }
        g.setClip(clip);
        // loose threads off the free edge, fuzz on the face
        for (int k = 0; k < 5; k++) {
            int idx = (int) Math.floor(rnd.next() * (N + 1));
            double px = lx[Math.min(N, idx)], py = ly[Math.min(N, idx)];
            double len = 3 + 7 * rnd.next(), a = Math.PI + (rnd.next() - .5) * 1.4;
            stroke(quad(px, py, px + Math.cos(a) * len * .5, py + Math.sin(a) * len * .5 - 2, px + Math.cos(a) * len, py + Math.sin(a) * len), 0xebe5d6, .8, .45);
        }
        for (int k = 0; k < 9; k++) {
            double y = yt + (ye - yt) * rnd.next(), x = turnX(base, i, y) + tg[1] * rnd.next(), a = rnd.next() * 6.28, l = 1.5 + 2.5 * rnd.next();
            stroke(quad(x, y, x + Math.cos(a) * l, y + Math.sin(a) * l, x + Math.cos(a + .8) * l * 1.6, y + Math.sin(a + .8) * l * 1.6), 0xf6f2e8, .55, .35);
        }
    }

    private LinearGradientPaint wrap;

    // gWrap: darker where the gauze wraps over the limb's edges
    LinearGradientPaint wrapPaint() {
        if (wrap == null) {
            wrap = new LinearGradientPaint(new Point2D.Float(0, 44), new Point2D.Float(0, 156), new float[]{0, .2f, .78f, 1},
                    new Color[]{col(0, .5), col(0, 0), col(0, 0), col(0, .55)});
        }
        return wrap;
    }

    /** mockup top.js: rollSvg - the roll at the end of the turn being laid (the same gauze). */
    public void roll(float[] tg, int i, double y, boolean under, double base) {
        double x = turnX(base, i, y), w = tg[1] + 4, h = 11, x0 = x - 2, y0 = y - h / 2, ex = x0 + w;
        Composite was = g.getComposite();
        if (under) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .3f));
        fill(ellipse(x0 + w / 2, y + h * .62, w / 2 + 2, 3), 0x000000, .35);
        Rectangle2D.Float r = rect(x0, y0, w, h);
        fill(r, new LinearGradientPaint(new Point2D.Double(0, y0), new Point2D.Double(0, y0 + h), new float[]{0, .5f, 1},
                new Color[]{new Color(0xf4f1e8), new Color(0xd9d3c5), new Color(0x9d9788)}));
        mesh(r, .75);
        for (int k = 1; k < 4; k++) stroke(line(x0, y0 + h * k / 4, ex, y0 + h * k / 4), 0x8d8676, .25, .4);
        fill(ellipse(ex, y, 3, h / 2), 0xe6e0d0, 1);
        for (int k = 1; k <= 4; k++) stroke(ellipse(ex, y, 3 * (1 - k * .19), h / 2 * (1 - k * .19)), 0x9a9384, 1, .35);
        fill(ellipse(ex, y, .9, 1.8), 0x4a463e, 1);
        stroke(new RoundRectangle2D.Double(x0, y0, w, h, 4, 4), 0x5f5a4f, .8, .7);
        stroke(quad(x0 + w * .3, y0 + .5, x0 + w * .3 + 1, y0 + .5 - 3, x0 + w * .3 + 4, y0 + .5 - 4), 0xebe5d6, .8, .45);
        stroke(quad(ex + 1, y + 2, ex + 4, y + 3, ex + 5, y + 6), 0xebe5d6, .8, .45);
        stroke(quad(x0 + w * .7, y0 + h - .5, x0 + w * .7 - 1, y0 + h + 2.5, x0 + w * .7 + 1, y0 + h + 4.5), 0xebe5d6, .8, .45);
        g.setComposite(was);
    }

    /** mockup top.js: padSvg - a folded gauze pad pressed on the wound. */
    public void pad(double x, double y, double w, double h) {
        fill(rect(x - w / 2 + 2, y - h / 2 + 3, w, h), 0x000000, .3);
        Rectangle2D.Float r = rect(x - w / 2, y - h / 2, w, h);
        fill(r, 0xc9c0ab, .85);
        mesh(r, 1);
        Path2D.Float p = new Path2D.Float();
        p.moveTo(x - w / 2, y - h / 6);
        p.lineTo(x + w / 2, y - h / 6);
        p.moveTo(x - w / 2, y + h / 6);
        p.lineTo(x + w / 2, y + h / 6);
        p.moveTo(x - w / 6, y - h / 2);
        p.lineTo(x - w / 6, y + h / 2);
        stroke(p, 0x8d8676, .35, .6);
    }

    /** mockup top.js: bandSvg - a neat bandage of n * 2 turns (dirty: greyed yellow, soaked stains). */
    public void bandage(int kind, int n, boolean dirty) {
        float[] bg = bandGeo(kind), tg = turnGeo(bg, n * 2);
        Shape clip = g.getClip();
        g.clip(arm());
        for (int i = 0; i < (int) tg[2]; i++) turn(tg, i, TURN_YB, dirty, turnLeft(bg, tg, i));
        if (dirty) {
            Sprite soak = blurred("soak", new Shape[]{ellipse(CX - 4, CY + 2, 38, 11), ellipse(CX - 2, CY + 2, 22, 5), ellipse(CX + 58, CY + 30, 16, 8), ellipse(CX - 66, CY - 24, 12, 6)},
                    new int[]{0x6e2a1c, 0x4e1a12, 0x8a7a3a, 0x8a7a3a}, new double[]{.6, .7, .5, .45}, 3);
            draw(soak, 1, 1, 1, 0, 0);
        }
        g.setClip(clip);
    }

    // ------------------------------------------------------------------ glass, the fracture

    // mockup top.js: shardPath
    public static Path2D.Float shardPath(double h) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(-4.4, 3);
        p.lineTo(0, 7);
        p.lineTo(4.8, 2);
        p.lineTo(3.6, -h * .7);
        p.lineTo(-1.8, -h);
        p.lineTo(-5, -h * .45);
        p.closePath();
        return p;
    }

    // mockup top.js: boneL / boneR
    public static Path2D.Float boneL() {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(FRX_WL - 2, CY - 8);
        p.lineTo(FRX_FX - 2, CY - 8);
        zig(p, FRX_FX - 2, CY - 8);
        p.lineTo(FRX_WL - 2, CY + 8);
        p.closePath();
        return p;
    }

    public static Path2D.Float boneR(double ox, double oy) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(FRX_WR + 20, CY - 8 + oy);
        p.lineTo(FRX_FX + 2 + ox, CY - 8 + oy);
        zig(p, FRX_FX + 2 + ox, CY - 8 + oy);
        p.lineTo(FRX_WR + 20, CY + 8 + oy);
        p.closePath();
        return p;
    }

    // (the break's zigzag: l4 4 l-5 4 l6 4 l-4 4)
    private static void zig(Path2D.Float p, double x, double y) {
        x += 4; y += 4; p.lineTo(x, y);
        x -= 5; y += 4; p.lineTo(x, y);
        x += 6; y += 4; p.lineTo(x, y);
        x -= 4; y += 4; p.lineTo(x, y);
    }

    // mockup top.js: boneSvg
    public void bone(Shape d, int edge) {
        fill(d, 0xa9cfe2, 1);
        patterned(d, pBone(true));
        stroke(d, edge, 1, 1.4, true, false);
    }

    // mockup top.js: boardsSvg
    public void boards() {
        for (double dy : new double[]{-40, 31}) {
            Rectangle2D.Float r = rect(CX - 134, CY + dy, 268, 9);
            fill(r, 0x33301f, 1);
            stroke(r, 0xb9a57a, 1, 1.2);
            patterned(r, pWood());
        }
    }

    // mockup top.js: strapSvg
    public void strap(double x, int edge, double tight) {
        Rectangle2D.Float r = rect(x - 6 + tight, CY - 45, 12 - 2 * tight, 90);
        fill(r, 0x34332c, 1);
        stroke(r, edge, 1, 1.2);
    }

    private Shape xrayClip;

    /** mockup top.js: xrayOpen - the window that sees through the skin, the cold glow in it. */
    public void xrayOpen(boolean spl) {
        RoundRectangle2D.Double win = new RoundRectangle2D.Double(FRX_WL, FRX_WT, FRX_WR - FRX_WL, FRX_WB - FRX_WT, 8, 8);
        fill(win, 0x0d1216, 1);
        stroke(win, ICE, .7, .8);
        xrayClip = g.getClip();
        g.clip(rect(FRX_WL, FRX_WT, FRX_WR - FRX_WL, FRX_WB - FRX_WT));
        // the CSS `cold`: its light 1 -> .45 -> 1 over 2.4 s
        double cold = 1 - .55 * (.5 - .5 * Math.cos(2 * Math.PI * frac(t / 2.4)));
        double rx = spl ? 38 : 62;
        Composite was = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) cold));
        fill(ellipse(FRX_FX, CY, rx, 26), radial(FRX_FX, CY, rx, 26, new float[]{0, 1}, new Color[]{col(ICE, .5), col(ICE, 0)}));
        g.setComposite(was);
    }

    // mockup top.js: xrayClose
    public void xrayClose() {
        g.setClip(xrayClip);
        text("ArtXray", FRX_WL + 4, FRX_WT + 8, ICE, .75f, 6, 1, 600, .8f);
    }

    /** An elliptic radial gradient (the SVG's radialGradient on an ellipse: objectBoundingBox). */
    static Paint radial(double cx, double cy, double rx, double ry, float[] stops, Color[] cols) {
        return new RadialGradientPaint(new Point2D.Double(cx, cy), (float) rx, new Point2D.Double(cx, cy), stops, cols,
                MultipleGradientPaint.CycleMethod.NO_CYCLE, MultipleGradientPaint.ColorSpaceType.SRGB,
                new AffineTransform(1, 0, 0, ry / rx, 0, cy - cy * ry / rx));
    }

    // ------------------------------------------------------------------ the whole picture

    void note(double x, double y, String key, int rgb) {
        notes.add(new float[]{(float) x, (float) y, rgb});
        noteKeys.add(key);
    }

    /** mockup top.js: section(pid, o) - the picture of one wound, everything at its place. */
    public void section(Scene w) {
        Shape clip0 = g.getClip();
        g.clip(rect(X0, Y0, X1 - X0, Y1 - Y0));
        boolean small = w.view != VIEW_TABLE;
        limb(swellOf(w), small);
        int depth = 0;
        int depthCol = w.lookRgb;
        Path2D.Float under = new Path2D.Float();   // the wound's outline, seen through a bandage
        boolean hidWound = (w.hide & HIDE_WOUND) != 0, hidDirt = (w.hide & HIDE_DIRT) != 0, hidGlass = (w.hide & HIDE_GLASS) != 0;
        boolean band = w.band && !w.dirtyBand, dirtyBand = w.dirtyBand;
        if (w.kind == KIND_FRACTURE) {
            boolean spl = w.splinted;
            depth = 4;
            Color bru = col(0x6a2a3c, .7), bru0 = col(0x6a2a3c, 0);
            fill(ellipse(FRX_FX, CY, 120, 52), radial(FRX_FX, CY, 120, 52, new float[]{0, 1}, new Color[]{bru, bru0}));
            fill(ellipse(FRX_FX - 30, CY + 6, 86, 40), radial(FRX_FX - 30, CY + 6, 86, 40, new float[]{0, 1}, new Color[]{bru, bru0}));
            if (spl && (w.hide & HIDE_SPLINT) == 0) {
                boards();
                for (float x : FRX_STRAPS) strap(x, 0xcfc9b4, 1.5);
                note(CX - 122, CY - 36, "NoteSplint", 0xb9a57a);
            }
            xrayOpen(spl);
            bone(boneL(), ICE);
            if ((w.hide & HIDE_BONEFAR) == 0) {
                bone(boneR(spl ? 0 : 5, spl ? 0 : -6), ICE);
                if (!spl) {
                    Path2D.Float p = new Path2D.Float();
                    p.moveTo(FRX_FX - 2, CY - 13); p.lineTo(FRX_FX + 2, CY - 10);
                    p.moveTo(FRX_FX + 16, CY + 12); p.lineTo(FRX_FX + 12, CY + 15);
                    p.moveTo(FRX_FX + 9, CY + 15); p.lineTo(FRX_FX + 9, CY + 19);
                    stroke(p, ICE, .8, 1.1);
                    note(FRX_FX + 40, CY - 14, "NoteShifted", ICE);
                    note(CX - 104, CY - 30, "NoteSwell", 0xb0667c);
                } else note(FRX_FX + 34, CY - 8, "NoteBoneOk", ICE);
            }
            xrayClose();
        } else if (gash(w.kind)) {
            Geo geo = geo(w.kind);
            boolean st = w.stitched, cleaned = w.cleaned, bleed = w.bleed;
            depth = geo.depth;
            Path2D.Float u = geo.ring(1, st ? .1 : 1);
            under.append(u, false);
            if (w.infected && !cleaned) {
                // the CSS `creep`: scale 1 -> 1.14 and its light 1 -> .75 over 4.5 s; the specks drift
                double k = easeInOut(.5 - .5 * Math.cos(2 * Math.PI * frac(t / 4.5)));
                double sc = 1 + .14 * k;
                Composite was = g.getComposite();
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) (1 - .25 * k)));
                double rx = (geo.L + 44) * sc, ry = 52 * sc;
                fill(ellipse(CX, CY, rx, ry), radial(CX, CY, rx, ry, new float[]{0, .6f, 1},
                        new Color[]{col(INFECT, .55), col(INFECT, .18), col(INFECT, 0)}));
                g.setComposite(was);
                note(CX + geo.L + 10, CY - 24, "NoteInfect", INFECT);
                if (!hidDirt) {
                    for (int i = 0; i < 9; i++) {
                        double u2 = frac(t / 6 + i * .67 / 6), op = u2 < .2 ? u2 / .2 : 1 - (u2 - .2) / .8;
                        fill(ellipse(CX - 60 + i * 14 + 14 * u2, CY - 26 + (i * 37 % 50) + 8 * u2, 1.6, 1.6), INFECT, op);
                    }
                }
            }
            if (bleed && !st && !(band || dirtyBand)) {
                blood(geo);
                double x = CX + .58 * geo.L;
                note(x, geo.cyAt(x) + geo.hw(x, 1, 1, 1) + 3, "NoteBlood", RED);
            }
            if (!hidWound) {
                if (!st) {
                    inkWound(geo, 1, cleaned ? DULL : (w.kind == KIND_DEEP || w.kind == KIND_BULLET ? RED : 0xc8743c), bleed);
                    if (w.kind == KIND_BULLET && w.bullet) {
                        // (no bullet in the mockup: a dark lead slug in the slit, a glint on it)
                        fill(ellipse(CX + 6, geo.cyAt(CX + 6), 5.5, 3.6), 0x3a3d40, 1);
                        stroke(ellipse(CX + 6, geo.cyAt(CX + 6), 5.5, 3.6), 0xb8bec4, .8, .7);
                        fill(ellipse(CX + 4.5, geo.cyAt(CX + 6) - 1, 1.6, .9), 0xeaf6fb, .8);
                        note(CX + 6, geo.cyAt(CX + 6) - 4, "NoteBullet", 0xb8bec4);
                    }
                } else {
                    if (bleed) {
                        double s = 1 + .1 * beatSwell(beatPh);
                        AffineTransform at = g.getTransform();
                        g.translate(CX, CY);
                        g.scale(s, s);
                        g.translate(-CX, -CY);
                        fill(geo.ring(1.7, .1), RED, .75);
                        g.setTransform(at);
                        note(CX + 30, geo.cyAt(CX + 30) + 3, "NoteOoze", RED);
                    }
                    Path2D.Float sl = geo.ring(1, .1);
                    fill(sl, 0x170a09, 1);
                    stroke(sl, DULL, 1, 1.1, true, false);
                    for (double f : new double[]{-.6, 0, .6}) stitch(geo, CX + f * geo.L);
                    double x = CX - .6 * geo.L;
                    note(x, geo.cyAt(x) - 7, "NoteStitches", TEXT);
                }
            }
            if (w.dirt && !cleaned && !hidDirt) {
                float[][] sp = dirtSpecks(geo);
                for (float[] p : sp) fill(rect(p[0] - 1.2, p[1] - 1.2, 2.4, 2.4), 0xc9aa4a, 1);
                note(sp[2][0], sp[2][1], "NoteDirt", 0xc9aa4a);
            } else if (w.kind != KIND_LACER && w.kind != KIND_BITE && cleaned && !st && !hidWound) {
                double x = CX - .5 * geo.L;
                note(x, geo.cyAt(x) - geo.hw(x, 0, 1, .88), "NoteWashed", DIM);
            }
            if (w.kind == KIND_BITE) note(CX - .3 * geo.L, geo.cyAt(CX - .3 * geo.L) - geo.hw(CX - .3 * geo.L, 0, 1, 1), "NoteBite", 0xc8743c);
        } else if (w.kind == KIND_SCRATCH) {
            depth = 1;
            double[][] lines = {{-22, -12}, {0, 0}, {22, 12}};
            for (double[] dd : lines) {
                double x = CX + dd[0], y = CY + dd[1], p = w.progress;
                under.moveTo(x - 15, y + 4);
                under.lineTo(x + 15, y - 4);
                AffineTransform at = g.getTransform();
                g.rotate(Math.toRadians(-15), x, y);
                Path2D.Float s = new Path2D.Float();
                s.moveTo(x - 16, y);
                s.quadTo(x, y - 3, x + 16, y);
                s.quadTo(x, y + 3, x - 16, y);
                s.closePath();
                fill(s, 0x170a09, 1);
                stroke(s, DULL, 1, 1);
                if (p > 0) stroke(line(x - 16, y, x - 16 + 32 * p, y), MINT, .9, 1.6);
                g.setTransform(at);
            }
            note(CX - 30, CY - 9, "NoteHealing", MINT);
        } else if (w.kind == KIND_GLASS) {
            depth = 1;
            boolean out = w.glassOut;
            for (float[] sh : SHARDS) {
                double x = CX + sh[0], y = CY + sh[1];
                under.moveTo(x - 5, y);
                under.lineTo(x + 5, y);
                AffineTransform at = g.getTransform();
                g.translate(x, y);
                g.rotate(Math.toRadians(sh[2]));
                Ellipse2D.Float hole = ellipse(0, 1.5, 8, 2.8);
                fill(hole, 0x170a09, 1);
                stroke(hole, DULL, 1, .9);
                if (!out && !hidGlass) shard(sh[3], sh[0]);
                g.setTransform(at);
            }
            if (!out && !hidGlass) note(CX + SHARDS[1][0] + 5, CY + SHARDS[1][1] - 14, "NoteGlass", ICE);
        } else if (w.kind == KIND_BURN) {
            // (no burn in the mockup: a raw patch, blisters on it, glowing as the open wounds)
            depth = 1;
            Sprite glow = blurred("burnglow", new Shape[]{ellipse(CX, CY, 70, 30)}, new int[]{0xb0402c}, new double[]{1}, 6);
            draw(glow, .5, 1, 1, CX, CY);
            Rnd r = new Rnd(23);
            for (int i = 0; i < 7; i++) {
                double x = CX - 52 + 104 * r.next(), y = CY - 14 + 28 * r.next(), rx = 14 + 16 * r.next(), ry = 7 + 7 * r.next();
                fill(ellipse(x, y, rx, ry), i % 2 == 0 ? 0xa3392f : 0xc0625a, .85);
            }
            for (int i = 0; i < 6; i++) {
                double x = CX - 40 + 80 * r.next(), y = CY - 10 + 20 * r.next(), rr = 2 + 2.5 * r.next();
                fill(ellipse(x, y, rr, rr), 0xf2c9a8, .85);
                stroke(ellipse(x, y, rr, rr), 0x8c3a30, .8, .6);
            }
            under.append(ellipse(CX, CY, 60, 22), false);
            note(CX + 30, CY - 16, "NoteBurn", 0xd07040);
        }
        float[] bg = bandGeo(w.kind);
        if (dirtyBand && (w.hide & HIDE_BAND) == 0) {
            bandage(w.kind, 3, true);
            note(bg[0] + 14, CY - 44, "NoteDirtyBand", 0xb39a62);
        } else if (band && (w.hide & HIDE_BAND) == 0) {
            bandage(w.kind, 3, false);
            g.setColor(col(0xcfc9b4, .5));
            g.setStroke(new BasicStroke(.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 4f, new float[]{3, 2.4f}, 0));
            g.draw(under);
            if (w.wetBand) fill(ellipse(CX, CY, 17, 7), 0x7a2620, .85);
            note(bg[0] + bg[1] - 12, CY - 44, "NoteBand", 0xcfc9b4);
        }
        g.setClip(clip0);
        // the frame
        stroke(rect(X0, Y0, X1 - X0, Y1 - Y0), 0x3c4141, 1, 1);
        stroke(line(X0, Y0, X1, Y0), 0x8e938a, 1, 1.2);
        if (!small) {
            // how deep it went: the layers by name, the ones the wound reached marked in its colour
            text("ArtDepth", 61, CY - 36, 0x5b615c, 1, 7, -1, 600, .8f);
            String[] names = {"ArtSkin", "ArtMuscle", "ArtBone"};
            int[] cols = {0xd79e8c, 0xa3392f, 0xe4dcc6};
            for (int i = 0; i < 3; i++) {
                double y = CY - 28 + i * 15;
                boolean on = depth == 4 ? i == 2 : i < depth;
                text(names[i], 45, (float) (y + 8.5), on ? TEXT : 0x5b615c, 1, 8.5f, -1, 600, .7f);
                Rectangle2D.Float sq = rect(50, y, 11, 11);
                fill(sq, cols[i], 1);
                if (i == 1) patterned(sq, pMus());
                if (i == 2) patterned(sq, pBone(false));
                stroke(sq, on ? depthCol : 0x3c4141, 1, on ? 1.4 : .8);
            }
            if (!w.noNotes) drawNotes();
        }
    }

    // a stitch across the closed slit (mockup top.js: section's ШВЫ; mg3 drawGame's done stitches)
    void stitch(Geo geo, double x) {
        double y = geo.cyAt(x);
        stroke(quad(x, y - 7, x + 2.5, y, x, y + 7), TEXT, 1, 1.1);
        cross(x, y - 9);
        cross(x, y + 5);
    }

    void cross(double x, double y) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(x - 2, y);
        p.lineTo(x + 2, y + 4);
        p.moveTo(x + 2, y);
        p.lineTo(x - 2, y + 4);
        stroke(p, TEXT, 1, 1);
    }

    // a shard of glass (at the origin, turned): pale glass, its frost outline, the glint (CSS `glint`)
    void shard(double h, double dx) {
        Path2D.Float s = shardPath(h);
        fill(s, ICE, .16);
        stroke(s, ICE, 1, 1.2, true, false);
        double u = frac(t / 2.2 + dx * .03 / 2.2);
        double op = u < .7 ? .15 : u < .8 ? .15 + .85 * (u - .7) / .1 : 1 - .85 * (u - .8) / .2;
        stroke(line(-1, -h * .75, 1, -h * .3), 0xeaf6fb, op, 1.1);
    }

    /** mockup top.js: section's notes - names on leader lines, left of the middle and right of it. */
    void drawNotes() {
        int n = notes.size();
        // left: by x down from the middle; right: by x up
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            float xa = notes.get(a)[0], xb = notes.get(b)[0];
            boolean la = xa < CX, lb = xb < CX;
            if (la != lb) return la ? -1 : 1;
            return la ? Float.compare(xb, xa) : Float.compare(xa, xb);
        });
        int jl = 0, jr = 0;
        for (Integer oi : order) {
            float[] nt = notes.get(oi);
            boolean left = nt[0] < CX;
            int j = left ? jl++ : jr++, sgn = left ? -1 : 1, rgb = (int) nt[2];
            double ty = 13 + 11 * j, tx = CX + sgn * (118 + 26 * j);
            stroke(line(tx - sgn * 4, ty - 3, nt[0], nt[1]), rgb, .75, .8);
            fill(rect(nt[0] - 1.5, nt[1] - 1.5, 3, 3), rgb, 1);
            text(noteKeys.get(oi), (float) tx, (float) ty, rgb, 1, 8.5f, left ? -1 : 1, 600, .7f);
        }
    }

    // ------------------------------------------------------------------ the mini-games' marks

    /** mockup mg3.js: RING - a ring that breathes, where to press. */
    public void ring(double x, double y, double r, int rgb, double tt) {
        double s = Math.sin(tt * 7);
        stroke(ellipse(x, y, r + 1.2 * s, r + 1.2 * s), rgb, .65 + .35 * s, 1.4);
    }

    // ------------------------------------------------------------------ the cardiogram

    /**
     * mockup health_v1: drawEcg - the line of the heart on its grid (w x h px), the newest at the
     * right, fading to the left, a dot at its head. phNow: the heart's phase now; period: 60 / BPM
     * (the line runs 62 px a second, as the mockup's); k: pixels per mockup pixel.
     */
    public static void ecg(Graphics2D g, int w, int h, double phNow, double period, int rgb, double k) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(new Color(0x070808));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0x141717));
        int step = (int) Math.max(4, Math.round(12 * k));
        for (int x = 0; x < w; x += step) g.fillRect(x, 0, Math.max(1, (int) Math.round(k)), h);
        for (int y = 0; y < h; y += step) g.fillRect(0, y, w, Math.max(1, (int) Math.round(k)));
        double mid = 54 * k, amp = 34 * k;
        int r = (rgb >> 16) & 255, gg = (rgb >> 8) & 255, b = rgb & 255;
        g.setStroke(new BasicStroke((float) (1.6 * k), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        double py = 0;
        int last = w - (int) Math.round(6 * k);
        for (int x = 0; x < last; x++) {
            double ph = frac(phNow - (w - 7 * k - x) / (62 * k) / period), y = mid - ecgY(ph) * amp;
            if (x > 0) {
                double a = .08 + .92 * Math.pow((double) x / w, 2.2);
                g.setColor(new Color(r, gg, b, (int) Math.round(255 * a)));
                g.draw(new java.awt.geom.Line2D.Double(x - 1, py, x, y));
            }
            py = y;
        }
        g.setColor(new Color((r + 255) / 2, (gg + 255) / 2, (b + 255) / 2));
        g.fill(new Rectangle2D.Double(w - 8 * k, py - 2 * k, 4 * k, 4 * k));
    }
}
