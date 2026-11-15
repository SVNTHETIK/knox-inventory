package knox.java;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import javax.imageio.ImageIO;

import me.zed_0xff.zombie_buddy.Patch;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjglx.BufferUtils;

import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import zombie.ZomboidFileSystem;
import zombie.core.SpriteRenderer;
import zombie.core.skinnedmodel.advancedanimation.AnimatedModel;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.textures.SmartTexture;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.ui.UI3DModel;

/**
 * The health tab's 3D character, painted every frame: the colour of each body
 * part, the light of the part under the mouse, the X-ray of a broken bone - laid
 * over the character's own baked skin, without rebuilding the model.
 *
 * Grid Character Hub (and the tab at first) dressed the model in hidden clothing
 * items whose texture is a part's mask: the game bakes a clothing item's tint into
 * the skin, so every colour change meant a new SurvivorDesc - the model rebuilt,
 * its animation started over, a pulse could only step.
 *
 * Here Lua hands over a list of layers each frame (knoxHealthPaint): a mask in
 * the skin's UV space (the mask clothing's own pictures, the bones of
 * tools/make_health_bones.ps1) and a colour with its strength. On the render
 * thread, before the model is drawn, the skin's GL texture - this UI model's own
 * bake, nothing else draws with it - is read once (a clean copy), and each frame
 * the clean copy with the layers over it is written back into it (glTexSubImage2D).
 * The mixing is done here on the CPU: 256x256 texels, a fraction of a millisecond.
 *
 * (Three tries on the GPU before, 2026-10-19: the game's TextureCombiner with a
 * shader of ours gave a skin without a trace of the layers - knox_health_paint.png
 * showed it - and swapping the texture handed to the model was not needed at all.
 * The skin's GL texture is in the same layout as the masks, row for row.)
 */
public class KnoxHealthPaint {

    /** A mask in texels (alpha 0..1, row 0 = the picture's top = the texture's row 0). */
    public static final class Mask {
        public final int w, h;
        public final float[] a;
        public int x0, y0, x1, y1;   // where it is not empty

        Mask(int w, int h, float[] a) {
            this.w = w;
            this.h = h;
            this.a = a;
            bounds();
        }

        // the part's main island (the rows with its texels at more than half - a foot and a shin
        // have a second, small one at the texture's top): its centre (where the wound's light is
        // brightest, where blood runs down from) and its rows (a fracture's scan goes along them),
        // in the mask's texels (2026-11-10)
        public float cx = -1, cy = -1;
        public int cy0, cy1;
        private final Map<Integer, float[]> falls = new HashMap<>();

        synchronized void core() {
            if (cx >= 0) return;
            int bestN = 0, run0 = -1, runN = 0;
            double sx = 0, sy = 0, bx = w / 2.0, by = h / 2.0;
            int b0 = 0, b1 = h - 1;
            for (int y = 0; y <= h; y++) {
                int n = 0;
                double rx = 0;
                if (y < h) {
                    for (int x = 0; x < w; x++) {
                        if (a[y * w + x] > .5f) {
                            n++;
                            rx += x;
                        }
                    }
                }
                if (n > 0) {
                    if (run0 < 0) {
                        run0 = y;
                        runN = 0;
                        sx = 0;
                        sy = 0;
                    }
                    runN += n;
                    sx += rx;
                    sy += (double) y * n;
                } else if (run0 >= 0) {
                    if (runN > bestN) {
                        bestN = runN;
                        bx = sx / runN;
                        by = sy / runN;
                        b0 = run0;
                        b1 = y - 1;
                    }
                    run0 = -1;
                }
            }
            cy0 = b0;
            cy1 = b1;
            cy = (float) by;
            cx = (float) bx;
        }

        /** exp(-d2 / c) for every texel, d2 the squared distance to the centre in the mockup's pixels. */
        synchronized float[] fall(int c) {
            float[] f = falls.get(c);
            if (f != null) return f;
            core();
            f = new float[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double dx = (x - cx) * PX, dy = (y - cy) * PX;
                    f[y * w + x] = (float) Math.exp(-(dx * dx + dy * dy) / c);
                }
            }
            falls.put(c, f);
            return f;
        }

        void bounds() {
            x0 = w; y0 = h; x1 = -1; y1 = -1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (a[y * w + x] > 0.002f) {
                        if (x < x0) x0 = x;
                        if (x > x1) x1 = x;
                        if (y < y0) y0 = y;
                        if (y > y1) y1 = y;
                    }
                }
            }
        }
    }

    /** One layer of a frame's paint. */
    public static final class Layer {
        public Mask mask;
        public float r, g, b, a;
        public boolean light;   // light on the skin (towards white by rgb * a), not a colour over it
        public boolean see;     // the skin see-through by a (its alpha down, the colour premultiplied)
        public int mode;        // the layer's mode as Lua gave it (see knoxHealthPaint)
    }

    // The health tab's light on the body as its mockup health_v3 draws it (drawBody; the author,
    // 2026-11-10), on the skin's texels instead of the picture's pixels. The mockup's model is
    // about 3.1 of its pixels a mask texel (the body 256 texels tall in the skin, 800 px on the
    // page): its distances are kept in its pixels. Its "rim" (the model's edge light, a picture
    // the skin does not have) is taken as its middle value.
    static final float PX = 3.1f, RIM = .45f;
    public static final int MODE_DIM = 3, MODE_SAND = 4, MODE_BLOOD = 5, MODE_STYLE = 10;
    public static final int ST_HEAL = 0, ST_BLEED = 1, ST_INFECT = 2, ST_FRACTURE = 3, ST_GLASS = 4, ST_OPEN = 5;
    private static final float[] NZ = new float[4096];

    static {
        for (int i = 0; i < NZ.length; i++) {
            double v = Math.sin(i * 127.1 + 311.7) * 43758.5453;
            NZ[i] = (float) Math.abs(v % 1);
        }
    }

    // mockup: nz - smooth value noise over a 64-cell table
    static float nz(float x, float y) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        float fx = x - xi, fy = y - yi;
        float a = NZ[(yi & 63) * 64 + (xi & 63)], b = NZ[(yi & 63) * 64 + ((xi + 1) & 63)];
        float c = NZ[((yi + 1) & 63) * 64 + (xi & 63)], d = NZ[((yi + 1) & 63) * 64 + ((xi + 1) & 63)];
        float sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy);
        return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy;
    }

    static float gb(float ph, float c, float w) {
        float d = ph - c;
        return (float) Math.exp(-(d * d) / (2 * w * w));
    }

    /** The time of the paint's animation (seconds). */
    static float clock() {
        return (float) ((System.nanoTime() % 3_600_000_000_000L) / 1e9);
    }

    /** The heart's beat now, 0..1 (the tab's ECG clock, KnoxWoundCanvas.beatPhase). */
    static float beatNow() {
        float ph = (float) KnoxWoundCanvas.beatPhase();
        return Math.min(1f, gb(ph, .30f, .06f) + .55f * gb(ph, .52f, .08f));
    }

    /** A painted model (render thread): its skin and the skin's clean texels. */
    public static final class State {
        public Texture skin;
        public int texId;
        public int w, h;
        public byte[] clean;
        public byte[] work;
        public ByteBuffer upload;
        public long lastSig;
        public boolean painted;
        // the skeleton's texture: its clean texels and what is shown on it
        public int boneTex;
        public byte[] boneClean, boneWork;
        public ByteBuffer boneUpload;
        public long boneSig = -1;
    }

    public static final Map<AnimatedModel, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());
    /** A GL step failed: Lua goes back to the old way (mask clothing). */
    public static volatile boolean FAILED = false;

    private static final Map<String, Mask> MASKS = new HashMap<>();
    private static boolean told, dumped;

    // ------------------------------------------------------------------ masks

    /** A mask picture by its game path, its edge spread outward by `soft` texels (game thread, cached). */
    public static Mask mask(String path, float soft) {
        int sk = Math.round(soft * 2);
        String key = path + "#" + sk;
        if (MASKS.containsKey(key)) return MASKS.get(key);
        Mask m = null;
        try {
            Mask base = sk == 0 ? null : MASKS.get(path + "#0");
            if (base == null) {
                base = load(find(path));
                MASKS.put(path + "#0", base);
            }
            m = sk == 0 ? base : soften(base, sk / 2f);
        } catch (Throwable t) {
            System.out.println("[Knox] health paint: no mask " + path + ": " + t);
        }
        MASKS.put(key, m);
        return m;
    }

    /**
     * A game path's file: the game's own lookup (mods first), else beside this jar
     * (Knox Inventory/42/media/java/client/ - the mod's 42 folder three up).
     */
    static File find(String path) throws Exception {
        String s = ZomboidFileSystem.instance.getString(path);
        File f = s == null ? null : new File(s);
        if (f != null && f.isFile()) return f;
        File jar = new File(KnoxHealthPaint.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File mod = jar.getParentFile().getParentFile().getParentFile().getParentFile();
        File g = new File(mod, path.replace('/', File.separatorChar));
        if (g.isFile()) return g;
        throw new Exception("not found (" + s + ", " + g + ")");
    }

    public static Mask load(File f) throws Exception {
        BufferedImage img = ImageIO.read(f);
        if (img == null) throw new Exception("not a picture");
        int w = img.getWidth(), h = img.getHeight();
        float[] a = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) a[y * w + x] = ((img.getRGB(x, y) >>> 24) & 255) / 255f;
        }
        return new Mask(w, h, a);
    }

    /**
     * A soft edge: the mask grown by the radius, then blurred by it (three box
     * passes, near a gaussian) - full inside the part, falling smoothly to nothing
     * over about twice the radius outside it, no step at the part's edge (the first
     * version kept the mask whole and blurred outside it: a step from 1 to a half
     * showed as a hard line - author, 2026-10-19). Inside the part it stays whole,
     * so no seam of the skin's layout shows there.
     */
    public static Mask soften(Mask m, float radius) {
        int r = Math.max(1, Math.round(radius));
        float[] b = grow(grow(m.a, m.w, m.h, r, true), m.w, m.h, r, false);
        for (int pass = 0; pass < 3; pass++) {
            b = boxBlur(b, m.w, m.h, r, true);
            b = boxBlur(b, m.w, m.h, r, false);
        }
        float[] out = new float[b.length];
        for (int i = 0; i < out.length; i++) out[i] = Math.max(m.a[i], Math.min(1f, b[i]));
        return new Mask(m.w, m.h, out);
    }

    /** The largest value within r along a row or a column. */
    static float[] grow(float[] src, int w, int h, int r, boolean horizontal) {
        float[] dst = new float[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float v = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = horizontal ? x + k : x, yy = horizontal ? y : y + k;
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                    float s = src[yy * w + xx];
                    if (s > v) v = s;
                }
                dst[y * w + x] = v;
            }
        }
        return dst;
    }

    static float[] boxBlur(float[] src, int w, int h, int r, boolean horizontal) {
        float[] dst = new float[src.length];
        float n = 2 * r + 1;
        if (horizontal) {
            for (int y = 0; y < h; y++) {
                float s = 0;
                for (int k = -r; k <= r; k++) s += src[y * w + Math.max(0, Math.min(w - 1, k))];
                for (int x = 0; x < w; x++) {
                    dst[y * w + x] = s / n;
                    s += src[y * w + Math.min(w - 1, x + r + 1)] - src[y * w + Math.max(0, x - r)];
                }
            }
        } else {
            for (int x = 0; x < w; x++) {
                float s = 0;
                for (int k = -r; k <= r; k++) s += src[Math.max(0, Math.min(h - 1, k)) * w + x];
                for (int y = 0; y < h; y++) {
                    dst[y * w + x] = s / n;
                    s += src[Math.min(h - 1, y + r + 1) * w + x] - src[Math.max(0, y - r) * w + x];
                }
            }
        }
        return dst;
    }

    // ------------------------------------------------------------------ mixing

    /**
     * The clean skin (RGBA, w x h, row 0 first) with the layers over it into `out`.
     * A colour: skin towards it by a * mask; light: skin times 1 + rgb * a * mask.
     * The skin's alpha stays.
     */
    public static void paint(byte[] clean, byte[] out, int w, int h, List<Layer> layers) {
        System.arraycopy(clean, 0, out, 0, clean.length);
        float t = clock(), beat = beatNow();
        for (Layer l : layers) {
            Mask m = l.mask;
            if (m == null || m.x1 < 0) continue;
            if (l.mode == MODE_DIM) {
                dim(out, w, h, l);
                continue;
            }
            if (l.mode == MODE_BLOOD) {
                bloodDown(out, w, h, l, t);
                continue;
            }
            if (l.mode == MODE_SAND || l.mode >= MODE_STYLE) {
                styled(out, w, h, l, t, beat);
                continue;
            }
            // (a mask of another size: its texels scaled onto the skin's)
            int sx0 = m.x0 * w / m.w, sx1 = Math.min(w - 1, (m.x1 + 1) * w / m.w);
            int sy0 = m.y0 * h / m.h, sy1 = Math.min(h - 1, (m.y1 + 1) * h / m.h);
            for (int y = sy0; y <= sy1; y++) {
                int my = y * m.h / h;
                for (int x = sx0; x <= sx1; x++) {
                    float k = m.a[my * m.w + x * m.w / w] * l.a;
                    if (k <= 0.002f) continue;
                    if (k > 1f) k = 1f;
                    int i = (y * w + x) * 4;
                    float r = out[i] & 255, g = out[i + 1] & 255, b = out[i + 2] & 255;
                    if (l.see) {
                        // the model shader blends premultiplied (its colour is not times the
                        // texture's alpha): what goes see-through is darkened by as much
                        float keep = 1f - Math.min(0.92f, k);
                        out[i] = (byte) Math.round(r * keep);
                        out[i + 1] = (byte) Math.round(g * keep);
                        out[i + 2] = (byte) Math.round(b * keep);
                        out[i + 3] = (byte) Math.round((out[i + 3] & 255) * keep);
                        continue;
                    }
                    if (l.light) {
                        // screen: towards white by the light's colour - lighter, the skin's
                        // shading kept, never clipped into another hue (a multiply did:
                        // the face went yellow-green - author, 2026-10-19)
                        r += (255f - r) * l.r * k;
                        g += (255f - g) * l.g * k;
                        b += (255f - b) * l.b * k;
                    } else {
                        r += (l.r * 255f - r) * k;
                        g += (l.g * 255f - g) * k;
                        b += (l.b * 255f - b) * k;
                    }
                    out[i] = (byte) Math.min(255, Math.round(r));
                    out[i + 1] = (byte) Math.min(255, Math.round(g));
                    out[i + 2] = (byte) Math.min(255, Math.round(b));
                }
            }
        }
    }

    /**
     * mockup drawBody: a wound's light by its style (mode 10 + style: 0 healing - breathes,
     * 1 bleeding - beats with the heart, 2 infection - creeping noise, 3 fracture - a scan
     * running along the part, 4 glass - glints, 5 open) or the sand of a thing that would
     * treat the part (mode 4). a: its strength (the mockup's glow x hover); rgb: its colour.
     */
    static void styled(byte[] out, int w, int h, Layer l, float t, float beat) {
        Mask m = l.mask;
        m.core();
        int style = l.mode - MODE_STYLE;
        boolean sand = l.mode == MODE_SAND;
        float[] f1 = null, f2 = null;
        if (!sand) {
            switch (style) {
                case ST_BLEED: f1 = m.fall(3200); break;
                case ST_INFECT: f1 = m.fall(9000); f2 = m.fall(2500); break;
                case ST_FRACTURE: f1 = m.fall(3000); break;
                case ST_GLASS: f1 = m.fall(1500); f2 = m.fall(2500); break;
                case ST_OPEN: f1 = m.fall(2000); break;
                default: f1 = m.fall(6000); break;
            }
        }
        float breath = .86f + .14f * (float) Math.sin(t * 1.5);
        float sandP = .72f + .28f * (float) Math.sin(t * 4.2);
        // (the scan: up and down the part's main island in 2.6 s)
        float u = (t / 2.6f) % 1f, tri = u < .5f ? u * 2 : 2 - u * 2;
        float scanY = m.cy0 + (m.cy1 - m.cy0) * tri;
        int glintT = (int) Math.floor(t * 7);
        int sx0 = m.x0 * w / m.w, sx1 = Math.min(w - 1, (m.x1 + 1) * w / m.w);
        int sy0 = m.y0 * h / m.h, sy1 = Math.min(h - 1, (m.y1 + 1) * h / m.h);
        float cr = l.r, cg = l.g, cb = l.b;
        for (int y = sy0; y <= sy1; y++) {
            int my = y * m.h / h;
            float scan = 0;
            if (style == ST_FRACTURE && !sand) {
                float sy = (my - scanY) * PX;
                scan = (float) Math.exp(-sy * sy / 40);
            }
            for (int x = sx0; x <= sx1; x++) {
                int mx = x * m.w / w, mi = my * m.w + mx;
                float k = m.a[mi];
                if (k <= 0.002f) continue;
                int i = (y * w + x) * 4;
                float r = out[i] & 255, g = out[i + 1] & 255, b = out[i + 2] & 255;
                if (sand) {
                    float a = l.a * k * (.2f + .4f * RIM) * sandP, dk2 = a * .5f;
                    b *= 1 - dk2 * .46f;
                    g *= 1 - dk2 * .1f;
                    r += (255 - r) * .83f * a * .6f;
                    g += (255 - g) * .76f * a * .6f;
                    b += (255 - b) * .54f * a * .6f;
                } else {
                    float a;
                    switch (style) {
                        case ST_BLEED:
                            a = (.20f + .55f * RIM) * (.6f + .4f * beat) + f1[mi] * (.5f + .45f * beat);
                            break;
                        case ST_INFECT: {
                            float px = mx * PX, py = my * PX;
                            float q = .6f * nz(px * .045f + t * .33f, py * .045f - t * .21f) + .4f * nz(px * .11f - t * .5f, py * .11f + t * .3f);
                            float bl = q < .42f ? 0 : (q > .7f ? 1 : (q - .42f) / .28f);
                            a = .12f + .45f * RIM + bl * (.25f + .5f * f1[mi]) + .25f * f2[mi];
                            break;
                        }
                        case ST_FRACTURE:
                            a = .22f + .55f * RIM + .7f * scan + .22f * f1[mi];
                            break;
                        case ST_GLASS: {
                            a = .16f + .5f * RIM + .5f * f1[mi];
                            // (the mockup's d2 < 2500: exp(-d2 / 2500) > 1/e)
                            double hs = Math.abs(Math.sin(mx * 12.9898 + my * 78.233 + glintT * 3.7) * 43758.5453) % 1;
                            if (hs > .985 && f2[mi] > .37f) a += .9f;
                            break;
                        }
                        case ST_OPEN:
                            a = .16f + .5f * RIM + .4f * f1[mi];
                            break;
                        default:
                            a = (.16f + .5f * RIM + .22f * f1[mi]) * breath;
                            break;
                    }
                    a *= l.a * k;
                    if (a > 1) a = 1;
                    float dk = a * .8f;
                    r = r * (1 - dk * (1 - cr));
                    g = g * (1 - dk * (1 - cg));
                    b = b * (1 - dk * (1 - cb));
                    float sa = a * .55f;
                    r += (255 - r) * cr * sa;
                    g += (255 - g) * cg * sa;
                    b += (255 - b) * cb * sa;
                }
                out[i] = (byte) Math.min(255, Math.round(r));
                out[i + 1] = (byte) Math.min(255, Math.round(g));
                out[i + 2] = (byte) Math.min(255, Math.round(b));
            }
        }
    }

    /** mockup drawBody's dimAmt: everything but the part on the table darker (a: how far it has come). */
    static void dim(byte[] out, int w, int h, Layer l) {
        Mask m = l.mask;
        for (int y = 0; y < h; y++) {
            int my = y * m.h / h;
            for (int x = 0; x < w; x++) {
                float k = 1 - .38f * l.a * (1 - Math.min(1f, m.a[my * m.w + x * m.w / w]));
                if (k >= .999f) continue;
                int i = (y * w + x) * 4;
                out[i] = (byte) Math.round((out[i] & 255) * k);
                out[i + 1] = (byte) Math.round((out[i + 1] & 255) * k);
                out[i + 2] = (byte) Math.round((out[i + 2] & 255) * k);
            }
        }
    }

    // mockup drawBody's DR: the drips running down from a bleeding {x offset px, speed, phase}
    static final float[][] DRIPS = {{-7, .55f, 0}, {-1, .8f, .37f}, {5, .62f, .71f}, {10, .47f, .19f}};

    /** mockup drawBody: blood running down from a bleeding part's middle (over whatever is below it). */
    static void bloodDown(byte[] out, int w, int h, Layer l, float t) {
        Mask m = l.mask;
        m.core();
        float sxw = (float) w / m.w, syh = (float) h / m.h;
        int x0 = Math.max(0, (int) Math.floor((m.cx - 15 / PX) * sxw)), x1 = Math.min(w - 1, (int) Math.ceil((m.cx + 18 / PX) * sxw));
        int y0 = Math.max(0, (int) Math.floor((m.cy - 2 / PX) * syh)), y1 = Math.min(h - 1, (int) Math.ceil((m.cy + 170 / PX) * syh));
        for (int y = y0; y <= y1; y++) {
            float yy = (y / syh - m.cy) * PX;
            if (yy < -2 || yy > 170) continue;
            for (int x = x0; x <= x1; x++) {
                float ddx = (x / sxw - m.cx) * PX;
                if (ddx < -14 || ddx > 17) continue;
                float dr = 0;
                for (float[] d : DRIPS) {
                    float xx = ddx - d[0] - yy * .05f;
                    if (xx > -4 && xx < 4) {
                        float sv = ((yy - t * 46 * d[1]) / 64 + d[2]) % 1f;
                        if (sv < 0) sv += 1;
                        dr += (float) Math.exp(-xx * xx / 3.2) * sv * sv * sv;
                    }
                }
                if (dr <= 0) continue;
                int i = (y * w + x) * 4;
                if ((out[i + 3] & 255) < 8) continue;   // (not on the skin's empty texels)
                dr = Math.min(1, dr) * l.a * (1 - yy / 170) * .92f;
                float r = out[i] & 255, g = out[i + 1] & 255, b = out[i + 2] & 255;
                r += (190 - r) * dr;
                g += (20 - g) * dr;
                b += (16 - b) * dr;
                out[i] = (byte) Math.round(r);
                out[i + 1] = (byte) Math.round(g);
                out[i + 2] = (byte) Math.round(b);
            }
        }
    }

    // ------------------------------------------------------------------ Lua

    static float num(KahluaTable t, int i) {
        Object o = t.rawget(Double.valueOf(i));
        return o instanceof Double ? ((Double) o).floatValue() : 0f;
    }

    /**
     * This frame's paint of a model: {path, r, g, b, a, soft, mode, path, ...}
     * (soft: how far the edge spreads, in texels; mode 0: a colour over the skin,
     * 1: light on it, 2: the skin see-through by a, 3: all but the mask darker by a, 4: the
     * sand of a thing that treats it, 5: blood running down from it, 10 + style: a wound's
     * light - see styled), and the skeleton's bones
     * shown: {block, alpha, ...} (block: part index * 3 - the bones the whole and the broken share, + 1 only the whole's, + 2 only the broken's). An empty
     * list: the clean skin. False: no paint here (no model yet, or it failed) -
     * Lua then paints the old way.
     */
    @LuaMethod(name = "knoxHealthPaint", global = true)
    public static boolean knoxHealthPaint(UI3DModel ui, KahluaTable layers, KahluaTable bones) {
        if (FAILED || ui == null) return false;
        AnimatedModel model = KnoxAvatar.modelOf(ui);
        ModelInstance root = model == null ? null : model.getModelInstance();
        if (root == null) return false;
        ArrayList<Layer> list = new ArrayList<>();
        long sig = 17;
        boolean animated = false;
        int n = layers == null ? 0 : layers.len();
        for (int i = 1; i + 6 <= n; i += 7) {
            Object p = layers.rawget(Double.valueOf(i));
            if (!(p instanceof String)) continue;
            Layer l = new Layer();
            l.mask = mask((String) p, num(layers, i + 5));
            if (l.mask == null) continue;
            l.r = num(layers, i + 1);
            l.g = num(layers, i + 2);
            l.b = num(layers, i + 3);
            l.a = num(layers, i + 4);
            float mode = num(layers, i + 6);
            l.mode = Math.round(mode);
            l.light = l.mode == 1;
            l.see = l.mode == 2;
            if (l.mode >= MODE_SAND) animated = true;
            if (l.a <= 0.003f) continue;
            list.add(l);
            sig = sig * 31 + System.identityHashCode(l.mask);
            sig = sig * 31 + Math.round(l.r * 255) * 65536L + Math.round(l.g * 255) * 256L + Math.round(l.b * 255);
            sig = sig * 31 + Math.round(l.a * 400) + Math.round(mode) * 1000;
        }
        // (a layer that moves by itself - a beat, a scan, glints: painted again 30 times a second)
        if (animated) sig = sig * 31 + System.nanoTime() / 33_333_333L;
        float[] show = new float[BLOCKS];
        long boneSig = 7;
        int nb = bones == null ? 0 : bones.len();
        for (int i = 1; i + 1 <= nb; i += 2) {
            int b = Math.round(num(bones, i));
            float al = Math.max(0f, Math.min(1f, num(bones, i + 1)));
            if (b >= 0 && b < BLOCKS && al > 0.003f) {
                show[b] = al;
                boneSig = boneSig * 31 + b * 1000L + Math.round(al * 255);
            }
        }
        State st = STATES.get(model);
        if (st == null) {
            st = new State();
            STATES.put(model, st);
        }
        SpriteRenderer.instance.drawGeneric(new Compose(st, root, list, sig, show, boneSig));
        return true;
    }

    // ------------------------------------------------------------------ the skeleton

    /** The skeleton texture's blocks (tools/healthpaint/SkeletonGen.java): 8 x 8 of 32 texels. */
    public static final int BLOCKS = 64, BLOCK = 32;

    /** The skeleton models of painted models: drawn near the camera (DrawCharSingular below). */
    public static final java.util.Set<ModelInstance> SKELETONS =
        Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    public static volatile boolean SKEL_ANY = false;

    public static boolean isSkeleton(ModelInstance mi) {
        return mi != null && SKELETONS.contains(mi);
    }

    /** The skeleton among a model's parts (its model's name), or null. */
    static ModelInstance skeletonOf(ModelInstance root) {
        if (root == null || root.sub == null) return null;
        for (int i = 0; i < root.sub.size(); i++) {
            ModelInstance mi = root.sub.get(i);
            String nm = mi == null || mi.model == null ? null : mi.model.name;
            if (nm != null && nm.toLowerCase().contains("knoxskeleton")) return mi;
        }
        return null;
    }

    /** The skeleton texture's blocks shown by alpha (render thread): colour premultiplied, as the shader blends. */
    static void showBones(State st, ModelInstance root, float[] show, long boneSig) {
        ModelInstance sk = skeletonOf(root);
        if (sk == null) return;
        if (!SKELETONS.contains(sk)) {
            SKELETONS.add(sk);
            SKEL_ANY = true;
            System.out.println("[Knox] health paint: the skeleton is on the model");
        }
        Texture t = sk.tex;
        if (t == null || !t.isReady()) return;
        Texture gl = glTexture(t);
        if (gl == null || gl.getID() <= 0) return;
        int w = gl.getWidthHW(), h = gl.getHeightHW();
        if (w <= 0 || h <= 0) return;
        if (st.boneTex != gl.getID() || st.boneClean == null || st.boneClean.length != w * h * 4) {
            // the bones' colour made here, not read from the file (a picture all but
            // transparent may load without its colour): old ivory, a little darker
            // towards a piece's ends - the rows of its block run along it
            st.boneTex = gl.getID();
            st.boneClean = new byte[w * h * 4];
            st.boneWork = new byte[w * h * 4];
            st.boneUpload = BufferUtils.createByteBuffer(w * h * 4);
            // rows of a block (tools/healthpaint/SkeletonGen.java blockUV): 2..21 bone, a
            // little darker towards a piece's ends; 22..26 dark (sockets, cracks); 27.. cartilage
            int bh = BLOCK * h / 256;
            for (int y = 0; y < h; y++) {
                float row = (y % bh) * (float) BLOCK / bh;
                int r, g, b;
                if (row >= 27) { r = 184; g = 196; b = 204; }
                else if (row >= 22) { r = 42; g = 36; b = 32; }
                else {
                    float bv = (row - 2f) / 18f;
                    float shade = 0.82f + 0.18f * (float) Math.sin(Math.max(0, Math.min(1, bv)) * Math.PI);
                    r = Math.round(240 * shade); g = Math.round(232 * shade); b = Math.round(212 * shade);
                }
                for (int x = 0; x < w; x++) {
                    int i = (y * w + x) * 4;
                    st.boneClean[i] = (byte) r;
                    st.boneClean[i + 1] = (byte) g;
                    st.boneClean[i + 2] = (byte) b;
                }
            }
            st.boneSig = -1;
        }
        if (boneSig == st.boneSig) return;
        byte[] px = st.boneWork;
        java.util.Arrays.fill(px, (byte) 0);
        int bw = BLOCK * w / 256, bh = BLOCK * h / 256;
        for (int b = 0; b < BLOCKS; b++) {
            float al = show[b];
            if (al <= 0) continue;
            int x0 = (b % 8) * bw, y0 = (b / 8) * bh;
            for (int y = y0; y < y0 + bh && y < h; y++) {
                for (int x = x0; x < x0 + bw && x < w; x++) {
                    int i = (y * w + x) * 4;
                    px[i] = (byte) Math.round((st.boneClean[i] & 255) * al);
                    px[i + 1] = (byte) Math.round((st.boneClean[i + 1] & 255) * al);
                    px[i + 2] = (byte) Math.round((st.boneClean[i + 2] & 255) * al);
                    px[i + 3] = (byte) Math.round(255 * al);
                }
            }
        }
        st.boneUpload.clear();
        st.boneUpload.put(px);
        st.boneUpload.flip();
        write(gl, st.boneUpload, w, h);
        st.boneUpload.clear();
        st.boneSig = boneSig;
    }

    /**
     * The skeleton is drawn near the camera (its depth squeezed to the front of the
     * range): its bones show through the see-through skin they lie in. Only the
     * bones Java has made visible are there to see (the rest has alpha 0, which the
     * model shader discards).
     */
    @Patch(className = "zombie.core.skinnedmodel.advancedanimation.AnimatedModel", methodName = "DrawCharSingular")
    public static class SkeletonDepth {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) AnimatedModel.AnimatedModelInstanceRenderData data) {
            if (KnoxHealthPaint.SKEL_ANY && data != null && KnoxHealthPaint.isSkeleton(data.modelInstance)) {
                GL11.glDepthRange(0.0, 0.02);
            }
        }

        @Patch.OnExit
        public static void exit(@Patch.Argument(0) AnimatedModel.AnimatedModelInstanceRenderData data) {
            if (KnoxHealthPaint.SKEL_ANY && data != null && KnoxHealthPaint.isSkeleton(data.modelInstance)) {
                GL11.glDepthRange(0.0, 1.0);
            }
        }
    }

    @LuaMethod(name = "knoxHealthPaintFailed", global = true)
    public static boolean knoxHealthPaintFailed() {
        return FAILED;
    }

    /** The GL texture a skin is drawn with (a baked skin is a SmartTexture holding its result). */
    static Texture glTexture(Texture t) {
        if (t instanceof SmartTexture) {
            Texture r = ((SmartTexture) t).result;
            if (r != null) return r;
        }
        return t;
    }

    // ------------------------------------------------------------------ render thread

    /** Writes a frame's paint into the model's skin (render thread, before the model). */
    public static final class Compose extends TextureDraw.GenericDrawer {
        final State st;
        final ModelInstance root;
        final ArrayList<Layer> layers;
        final long sig;
        final float[] show;
        final long boneSig;

        Compose(State st, ModelInstance root, ArrayList<Layer> layers, long sig, float[] show, long boneSig) {
            this.st = st;
            this.root = root;
            this.layers = layers;
            this.sig = sig;
            this.show = show;
            this.boneSig = boneSig;
        }

        @Override
        public void render() {
            try {
                compose();
                showBones(st, root, show, boneSig);
            } catch (Throwable t) {
                FAILED = true;
                System.out.println("[Knox] health paint failed, back to mask clothing: " + t);
                t.printStackTrace(System.out);
            }
        }

        void compose() {
            Texture skin = root.tex;
            if (skin == null || !skin.isReady()) return;   // the skin is still being baked
            Texture gl = glTexture(skin);
            if (gl == null || gl.getID() <= 0) return;
            int w = gl.getWidthHW(), h = gl.getHeightHW();
            if (w <= 0 || h <= 0) return;
            if (st.skin != skin || st.texId != gl.getID() || st.w != w || st.h != h || st.clean == null) {
                // a skin not seen yet (the look refreshed, the game baked it anew): its clean texels
                st.skin = skin;
                st.texId = gl.getID();
                st.w = w;
                st.h = h;
                st.clean = new byte[w * h * 4];
                st.work = new byte[w * h * 4];
                st.upload = BufferUtils.createByteBuffer(w * h * 4);
                read(gl, st.upload);
                st.upload.get(st.clean);
                st.upload.clear();
                st.painted = false;
                st.lastSig = 0;
            }
            if (layers.isEmpty() && !st.painted) return;   // clean and staying clean
            if (sig == st.lastSig && st.painted == !layers.isEmpty()) return;   // the same as on it
            paint(st.clean, st.work, w, h, layers);
            st.upload.clear();
            st.upload.put(st.work);
            st.upload.flip();
            write(gl, st.upload, w, h);
            st.upload.clear();
            st.lastSig = sig;
            st.painted = !layers.isEmpty();
            if (!told) {
                told = true;
                System.out.println("[Knox] health paint: the model's skin is painted (" + w + "x" + h + ", " + layers.size() + " layers)");
            }
            if (!dumped && !layers.isEmpty()) {
                dumped = true;
                dump(st.work, w, h);
            }
        }
    }

    static void read(Texture t, ByteBuffer buf) {
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t.getID());
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
        Texture.lastTextureID = -1;
    }

    static void write(Texture t, ByteBuffer buf, int w, int h) {
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t.getID());
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        // the smaller levels too, if the skin has them (the model in the box can be drawn small)
        int minFilter = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER);
        if (minFilter != GL11.GL_LINEAR && minFilter != GL11.GL_NEAREST) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
        Texture.lastTextureID = -1;
    }

    /** Once: the painted skin to Zomboid/knox_health_paint.png (row 0 at the top, as the masks). */
    static void dump(byte[] px, int w, int h) {
        try {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = (y * w + x) * 4;
                    img.setRGB(x, y, ((px[i + 3] & 255) << 24) | ((px[i] & 255) << 16) | ((px[i + 1] & 255) << 8) | (px[i + 2] & 255));
                }
            }
            File f = new File(ZomboidFileSystem.instance.getCacheDir(), "knox_health_paint.png");
            ImageIO.write(img, "png", f);
            System.out.println("[Knox] health paint: the painted skin in " + f.getAbsolutePath());
        } catch (Throwable e) {
            System.out.println("[Knox] health paint: dump failed: " + e);
        }
    }
}
