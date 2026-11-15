package knox.java;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjglx.BufferUtils;

import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.core.SpriteRenderer;
import zombie.core.Translator;
import zombie.core.opengl.RenderThread;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureID;

/**
 * The health tab's pictures in the game (the author, 2026-11-10: the tab as the mockup 3): the
 * wounds seen from above (KnoxWoundArt) on the table, in the list and the hover card, the mini-game
 * over the table's (KnoxWoundGame) and the cardiogram. A worker thread draws them (Java2D, off
 * the game's thread: the frame pays nothing for them) - a still picture once when it changes, a
 * moving one (the glow beats, the blood runs, a game) up to 30 times a second - and the render
 * thread takes each new frame into the picture's texture just before the tab draws it. The
 * words on a picture come out as a list (knoxWoundTexts) for the layout kit to set.
 * Not on a Mac: AWT there in a GLFW game is a risk not taken; the tab then draws plain boxes.
 */
public final class KnoxWoundCanvas {

    static final boolean MAC = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("mac");
    static final int KIND_WOUND = 0, KIND_ECG = 1;
    static final long FRAME_NS = 33_000_000L, IDLE_NS = 2_000_000_000L, DROP_NS = 20_000_000_000L;

    // what happened to the pictures, for knoxWoundDebug (the author's game 2026-11-11: the words came,
    // the pictures did not - where they stop)
    static volatile long dbgRendered, dbgTexCalls, dbgQueued, dbgUploads, dbgSkipped, dbgMade;
    static volatile String dbgLast = "";
    static volatile int dbgPbo;
    static volatile int dbgFormat, dbgRespec;

    // ------------------------------------------------------------------ the heart's clock

    private static double bpm = 86, phase;
    private static long phaseNs;

    /** The heart's phase 0..1 now (the cardiogram, the beating glow, the model's light share it). */
    public static synchronized double beatPhase() {
        long now = System.nanoTime();
        if (phaseNs != 0) phase = KnoxWoundArt.frac(phase + (now - phaseNs) / 1e9 * bpm / 60.0);
        phaseNs = now;
        return phase;
    }

    public static synchronized double period() {
        return 60.0 / bpm;
    }

    static synchronized void setBpm(double b) {
        if (b > 20 && b < 250 && b != bpm) {
            beatPhase();
            bpm = b;
        }
    }

    // ------------------------------------------------------------------ a picture

    /** A picture of the tab: what it shows, its pixels, its texture. */
    static final class Canvas {
        final int id;
        int kind;
        volatile int w, h;
        final KnoxWoundArt.Scene scene = new KnoxWoundArt.Scene();   // (under `this`)
        long sceneSig = Long.MIN_VALUE;
        int ecgRgb = 0xd4c38a;
        volatile boolean dirty = true;
        volatile long touchedNs = System.nanoTime(), renderedNs;
        KnoxWoundGame game;                                        // (under `this`)
        // the worker's
        BufferedImage img;
        final KnoxWoundArt art = new KnoxWoundArt();
        double s = 1, tx, ty;                                        // units -> pixels (the slice of the viewBox)
        // three frames: 0 free, 1 ready, 2 being uploaded
        ByteBuffer[] bufs;
        final AtomicInteger[] state = {new AtomicInteger(), new AtomicInteger(), new AtomicInteger()};
        final long[] stateNs = new long[3];
        long[] frameNo = new long[3];
        long frames;
        int bufW, bufH;
        // the game thread's
        Texture tex;
        int texW, texH;
        boolean uploaded;
        // the words of the last frame (Lua copies them into `table` when `textVer` moved)
        final ArrayList<Object[]> words = new ArrayList<>();
        long wordsVer, tableVer = -1;
        KahluaTable table;

        Canvas(int id) {
            this.id = id;
        }
    }

    private static final HashMap<Integer, Canvas> CANVASES = new HashMap<>();
    private static Thread worker;

    static Canvas canvas(int id) {
        synchronized (CANVASES) {
            Canvas c = CANVASES.get(id);
            if (c == null) {
                c = new Canvas(id);
                CANVASES.put(id, c);
            }
            c.touchedNs = System.nanoTime();
            return c;
        }
    }

    static void startWorker() {
        if (worker != null || MAC) return;
        worker = new Thread(KnoxWoundCanvas::work, "Knox wound pictures");
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
    }

    // ------------------------------------------------------------------ the worker

    private static void work() {
        ArrayList<Canvas> list = new ArrayList<>();
        while (true) {
            try {
                long now = System.nanoTime();
                list.clear();
                synchronized (CANVASES) {
                    CANVASES.values().removeIf(c -> now - c.touchedNs > DROP_NS);
                    list.addAll(CANVASES.values());
                }
                boolean any = false;
                for (Canvas c : list) {
                    if (now - c.touchedNs > IDLE_NS || c.w <= 0 || c.h <= 0) continue;
                    boolean moving;
                    synchronized (c) {
                        moving = c.kind == KIND_ECG || c.game != null || c.scene.animated();
                    }
                    if (!c.dirty && !(moving && now - c.renderedNs >= FRAME_NS)) continue;
                    if (render(c, now)) any = true;
                }
                if (!any) Thread.sleep(4);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                System.out.println("[Knox] wound pictures: " + t);
                t.printStackTrace(System.out);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** One frame of a picture into a free buffer. */
    static boolean render(Canvas c, long now) {
        int w = c.w, h = c.h;
        if (c.bufs == null || c.bufW != w || c.bufH != h) {
            // (a new size: new frames; the old ones are dropped once nothing uploads them)
            for (AtomicInteger st : c.state) if (st.get() == 2) return false;
            c.bufs = new ByteBuffer[]{BufferUtils.createByteBuffer(w * h * 4), BufferUtils.createByteBuffer(w * h * 4), BufferUtils.createByteBuffer(w * h * 4)};
            for (AtomicInteger st : c.state) st.set(0);
            c.bufW = w;
            c.bufH = h;
            c.img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        }
        int free = -1;
        for (int i = 0; i < 3; i++) {
            int st = c.state[i].get();
            // (a frame given to the render thread that never came back: free again)
            if (st == 2 && now - c.stateNs[i] > 2_000_000_000L) c.state[i].set(0);
            if (c.state[i].get() == 0) {
                free = i;
                break;
            }
        }
        if (free < 0) return false;
        c.dirty = false;
        c.renderedNs = now;
        Graphics2D g = c.img.createGraphics();
        try {
            double ph = beatPhase();
            if (c.kind == KIND_ECG) {
                double k = h / 84.0;
                KnoxWoundArt.ecg(g, w, h, ph, period(), c.ecgRgb, k);
                synchronized (c.words) {
                    c.words.clear();
                }
            } else {
                drawWound(c, g, now, ph);
            }
        } finally {
            g.dispose();
        }
        int[] px = ((DataBufferInt) c.img.getRaster().getDataBuffer()).getData();
        ByteBuffer b = c.bufs[free];
        b.clear();
        for (int p : px) {
            int a = p >>> 24, r = (p >> 16) & 255, gg = (p >> 8) & 255, bb = p & 255;
            if (a != 255 && a != 0) {
                r = Math.min(255, r * 255 / a);
                gg = Math.min(255, gg * 255 / a);
                bb = Math.min(255, bb * 255 / a);
            }
            b.put((byte) r).put((byte) gg).put((byte) bb).put((byte) a);
        }
        b.flip();
        c.frameNo[free] = ++c.frames;
        dbgRendered++;
        // the new frame ready; an older ready one free again
        for (int i = 0; i < 3; i++) if (i != free) c.state[i].compareAndSet(1, 0);
        c.state[free].set(1);
        return true;
    }

    static final double VB_TABLE[] = {0, 0, 480, 170}, VB_MINI[] = {132, 14, 280, 164}, VB_THUMB[] = {142, 48, 260, 95};

    static void drawWound(Canvas c, Graphics2D g, long now, double ph) {
        KnoxWoundArt.Scene sc = new KnoxWoundArt.Scene();
        KnoxWoundGame game;
        synchronized (c) {
            sc.set(c.scene);
            game = c.game;
        }
        double[] vb = sc.view == KnoxWoundArt.VIEW_MINI ? VB_MINI : sc.view == KnoxWoundArt.VIEW_THUMB ? VB_THUMB : VB_TABLE;
        int w = c.w, h = c.h;
        // (the SVG's preserveAspectRatio="xMidYMid slice")
        double s = Math.max(w / vb[2], h / vb[3]);
        double tx = (w - vb[2] * s) / 2 - vb[0] * s, ty = (h - vb[3] * s) / 2 - vb[1] * s;
        c.s = s;
        c.tx = tx;
        c.ty = ty;
        g.setColor(new java.awt.Color(0x0b0c0c));
        g.fillRect(0, 0, w, h);
        g.translate(tx, ty);
        g.scale(s, s);
        KnoxWoundArt art = c.art;
        double t = (now % 3_600_000_000_000L) / 1e9;
        art.begin(g, s, t, ph);
        if (game != null) {
            synchronized (game) {
                sc.hide |= KnoxWoundGame.hide(game.kind);
                sc.noNotes = true;
                art.section(sc);
                game.draw(art);
            }
        } else {
            art.section(sc);
        }
        synchronized (c.words) {
            c.words.clear();
            for (int i = 0; i < art.count(); i++) {
                KnoxWoundArt.Text tx0 = art.texts.get(i);
                String str = tx0.raw ? tx0.key : word(tx0.key);
                c.words.add(new Object[]{str, tx0.x * s + tx, tx0.y * s + ty, (double) tx0.rgb, (double) tx0.a,
                        tx0.size * s, (double) tx0.anchor, (double) tx0.weight, tx0.spacing * s});
            }
            c.wordsVer++;
        }
    }

    // ------------------------------------------------------------------ the words

    private static final HashMap<String, String> WORDS = new HashMap<>();

    /** A word of the pictures: IGUI_KnoxHP_<key> (cached; the translations reloaded: knoxWoundWords). */
    static String word(String key) {
        synchronized (WORDS) {
            String s = WORDS.get(key);
            if (s == null) {
                try {
                    s = Translator.getTextOrNull("IGUI_KnoxHP_" + key);
                } catch (Throwable t) {
                    s = null;
                }
                if (s == null) s = key;
                WORDS.put(key, s);
            }
            return s;
        }
    }

    static {
        KnoxWoundGame.WORDS = (key, args) -> {
            String s;
            try {
                s = args.length == 0 ? Translator.getTextOrNull("IGUI_KnoxHP_" + key) : Translator.getTextOrNull("IGUI_KnoxHP_" + key, (Object[]) args);
            } catch (Throwable t) {
                s = null;
            }
            return s == null ? key : s;
        };
    }

    /** The translations changed (the DEV reloader): the pictures' words looked up again. */
    @LuaMethod(name = "knoxWoundWords", global = true)
    public static void knoxWoundWords() {
        synchronized (WORDS) {
            WORDS.clear();
        }
        synchronized (CANVASES) {
            for (Canvas c : CANVASES.values()) c.dirty = true;
        }
    }

    // ------------------------------------------------------------------ Lua

    /** Can the pictures be drawn here (not on a Mac)? */
    @LuaMethod(name = "knoxWoundOk", global = true)
    public static boolean knoxWoundOk() {
        return !MAC;
    }

    /**
     * A wound's picture `id` of w x h px: view (0 the table, 1 the hover card, 2 the list), kind
     * (KnoxWoundArt.KIND_*), flags (the sum of: 1 bleeding, 2 bleeding under a bandage, 4 dirt,
     * 8 infected, 16 cleaned, 32 stitched, 64 a bandage, 128 a dirty bandage, 256 splinted,
     * 512 the glass out, 1024 a bullet in), progress (a scratch's healing), its look's colour.
     */
    @LuaMethod(name = "knoxWoundScene", global = true)
    public static void knoxWoundScene(double id, double w, double h, double view, double kind, double flags, double progress, double rgb) {
        if (MAC) return;
        startWorker();
        Canvas c = canvas((int) id);
        int f = (int) flags;
        synchronized (c) {
            c.kind = KIND_WOUND;
            KnoxWoundArt.Scene s = c.scene;
            s.view = (int) view;
            s.kind = (int) kind;
            s.bleed = (f & 1) != 0;
            s.wetBand = (f & 2) != 0;
            s.dirt = (f & 4) != 0;
            s.infected = (f & 8) != 0;
            s.cleaned = (f & 16) != 0;
            s.stitched = (f & 32) != 0;
            s.band = (f & 64) != 0;
            s.dirtyBand = (f & 128) != 0;
            s.splinted = (f & 256) != 0;
            s.glassOut = (f & 512) != 0;
            s.bullet = (f & 1024) != 0;
            s.progress = (float) progress;
            s.lookRgb = (int) rgb;
            s.hide = 0;
            s.noNotes = false;
            long sig = s.sig();
            if (sig != c.sceneSig) {
                c.sceneSig = sig;
                c.dirty = true;
            }
        }
        size(c, (int) Math.round(w), (int) Math.round(h));
    }

    static void size(Canvas c, int w, int h) {
        w = Math.max(8, Math.min(4096, w));
        h = Math.max(8, Math.min(4096, h));
        if (c.w != w || c.h != h) {
            c.w = w;
            c.h = h;
            c.dirty = true;
        }
    }

    /** The cardiogram `id` of w x h px at bpm beats a minute, in rgb. */
    @LuaMethod(name = "knoxWoundEcg", global = true)
    public static void knoxWoundEcg(double id, double w, double h, double bpm, double rgb) {
        setBpm(bpm);
        if (MAC) return;
        startWorker();
        Canvas c = canvas((int) id);
        synchronized (c) {
            c.kind = KIND_ECG;
            c.ecgRgb = (int) rgb;
        }
        size(c, (int) Math.round(w), (int) Math.round(h));
    }

    /** The heart's beats a minute (the model's light beats with it too). */
    @LuaMethod(name = "knoxWoundBpm", global = true)
    public static void knoxWoundBpm(double b) {
        setBpm(b);
    }

    /**
     * The picture's texture to draw now (its newest frame goes up into it first, on the render
     * thread, before the tab's drawing of it); nil until the first frame.
     */
    @LuaMethod(name = "knoxWoundTex", global = true)
    public static Texture knoxWoundTex(double id) {
        if (MAC) return null;
        Canvas c;
        synchronized (CANVASES) {
            c = CANVASES.get((int) id);
        }
        if (c == null) return null;
        c.touchedNs = System.nanoTime();
        dbgTexCalls++;
        int ready = -1;
        for (int i = 0; i < 3; i++) if (c.state[i].get() == 1) ready = i;
        if (ready >= 0) {
            int w = c.bufW, h = c.bufH;
            if (c.tex == null || c.texW != w || c.texH != h) {
                if (c.tex != null) {
                    final Texture old = c.tex;
                    try {
                        RenderThread.queueInvokeOnRenderContext(old::destroy);
                    } catch (Throwable t) {
                        // (an old picture's texture left: only when the screen's size changed)
                    }
                }
                c.tex = make(w, h);
                dbgMade++;
                c.texW = w;
                c.texH = h;
                c.uploaded = false;
            }
            if (c.state[ready].compareAndSet(1, 2)) {
                c.stateNs[ready] = System.nanoTime();
                SpriteRenderer.instance.drawGeneric(new Upload(c, ready, c.tex, w, h));
                dbgQueued++;
                c.uploaded = true;
            }
        }
        return c.uploaded ? c.tex : null;
    }

    /** A picture of our own: its size and place on the texture set here, not by the game. */
    private static final class Made extends Texture {
        Made(TextureID id, int w, int h, int hwW, int hwH) {
            super(id, null);
            this.width = w;
            this.height = h;
            this.widthOrig = w;
            this.heightOrig = h;
            this.xStart = 0;
            this.yStart = 0;
            this.xEnd = (float) w / hwW;
            this.yEnd = (float) h / hwH;
            this.solid = false;
        }
    }

    static int pow2(int v) {
        int p = 1;
        while (p < v) p <<= 1;
        return p;
    }

    static Texture make(int w, int h) {
        int pw = pow2(w), ph = pow2(h);
        ByteBuffer empty = BufferUtils.createByteBuffer(pw * ph * 4);
        final TextureID id = new TextureID(pw, ph, empty);
        RenderThread.invokeOnRenderContext(() -> {
            int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id.getID());
            // (the author's game 2026-11-11, confirmed: every upload GL_INVALID_OPERATION - the texture made
            // for us came out DXT5 (0x83F3: Viewpoint's texture compression);
            // its storage set again as plain RGBA8, which glTexSubImage2D can fill)
            dbgFormat = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, pw, ph, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            dbgRespec = GL11.glGetError();
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
            Texture.lastTextureID = -1;
        });
        return new Made(id, w, h, pw, ph);
    }

    /** Takes a frame into the picture's texture (render thread). */
    static final class Upload extends TextureDraw.GenericDrawer {
        final Canvas c;
        final int buf;
        final Texture tex;
        final int w, h;

        Upload(Canvas c, int buf, Texture tex, int w, int h) {
            this.c = c;
            this.buf = buf;
            this.tex = tex;
            this.w = w;
            this.h = h;
        }

        @Override
        public void render() {
            try {
                ByteBuffer b = c.bufs != null && c.bufW == w && c.bufH == h ? c.bufs[buf] : null;
                if (b != null && tex.getID() > 0) {
                    while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* (others' errors: not ours) */ }
                    int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                    // (the author's game 2026-11-11: GL_INVALID_OPERATION on every upload - a pixel
                    // unpack buffer left bound by others takes the pointer for an offset; and the
                    // unpack rows / skips others set: all off for ours, then back as they were)
                    int pbo = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
                    int rowLen = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
                    int skipP = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS), skipR = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
                    int align = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
                    if (pbo != 0) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                    if (rowLen != 0) GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
                    if (skipP != 0) GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
                    if (skipR != 0) GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex.getID());
                    GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
                    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, b);
                    int err = GL11.glGetError();
                    GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, align);
                    if (rowLen != 0) GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, rowLen);
                    if (skipP != 0) GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, skipP);
                    if (skipR != 0) GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, skipR);
                    if (pbo != 0) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
                    dbgPbo = pbo;
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
                    Texture.lastTextureID = -1;
                    dbgUploads++;
                    dbgLast = String.format("tex %d %dx%d glError %d pbo %d madeFormat 0x%x respec %d pixel %x", tex.getID(), w, h, err,
                        dbgPbo, dbgFormat, dbgRespec, b.getInt((h / 2 * w + w / 2) * 4));
                } else {
                    dbgSkipped++;
                    dbgLast = "skipped: buffer " + (b != null) + ", tex id " + tex.getID();
                }
            } catch (Throwable t) {
                System.out.println("[Knox] wound picture upload: " + t);
            } finally {
                c.state[buf].compareAndSet(2, 0);
            }
        }
    }

    /** A word's fields in knoxWoundTexts: text, x, y, rgb, alpha, size px, anchor, weight, letter-spacing px. */
    public static final int WORD_FIELDS = 9;

    /**
     * The words on the picture's last frame, in its pixels: a table kept for the picture
     * {n = count, text, x, y (the baseline), rgb, a, size, anchor (-1 ends at x, 0 middle, 1 starts),
     * weight, letter-spacing, ...} (WORD_FIELDS a word; filled anew only when they changed).
     */
    @LuaMethod(name = "knoxWoundTexts", global = true)
    public static KahluaTable knoxWoundTexts(double id) {
        Canvas c;
        synchronized (CANVASES) {
            c = CANVASES.get((int) id);
        }
        if (c == null) return null;
        if (c.table == null) {
            c.table = LuaManager.platform.newTable();
            c.table.rawset("n", 0.0);
        }
        synchronized (c.words) {
            if (c.tableVer != c.wordsVer) {
                c.tableVer = c.wordsVer;
                KahluaTable t = c.table;
                int n = c.words.size();
                for (int i = 0; i < n; i++) {
                    Object[] wd = c.words.get(i);
                    for (int k = 0; k < WORD_FIELDS; k++) t.rawset(i * WORD_FIELDS + k + 1, wd[k]);
                }
                t.rawset("n", (double) n);
            }
        }
        return c.table;
    }

    /**
     * What happened to the pictures (DEV, the remote): frames drawn, textures asked, made, uploads
     * queued / done / skipped, the last upload's GL error and middle pixel; the picture's own state.
     */
    @LuaMethod(name = "knoxWoundDebug", global = true)
    public static String knoxWoundDebug(double id) {
        Canvas c;
        synchronized (CANVASES) {
            c = CANVASES.get((int) id);
        }
        // (String.format, not "+": a "+" keeps its first recipe through a class swapped in the DEV reloader)
        String own = c == null ? "no canvas" : String.format("kind %d %dx%d buf %dx%d tex %s uploaded %s", c.kind, c.w, c.h, c.bufW, c.bufH,
            c.tex == null ? "none" : String.valueOf(c.tex.getID()), String.valueOf(c.uploaded));
        return String.format("worker %s rendered %d texCalls %d made %d queued %d uploads %d skipped %d | %s | %s | format 0x%x respec %d",
            String.valueOf(worker != null && worker.isAlive()), dbgRendered, dbgTexCalls, dbgMade, dbgQueued, dbgUploads, dbgSkipped,
            dbgLast, own, dbgFormat, dbgRespec);
    }

    /** Let a picture go (its texture stays until the tab draws it again). */
    @LuaMethod(name = "knoxWoundRelease", global = true)
    public static void knoxWoundRelease(double id) {
        synchronized (CANVASES) {
            Canvas c = CANVASES.get((int) id);
            if (c != null) c.touchedNs = 0;
        }
    }

    // ------------------------------------------------------------------ the mini-game

    /**
     * A mini-game on the picture `id` (its scene set first): kind (KnoxWoundGame.CLEAN..SPLINT),
     * woundKind, cleaned (the wound washed: its outline dull), item ("suture", "rag" or ""),
     * skill (first aid), van (the normal time in seconds).
     */
    @LuaMethod(name = "knoxWoundGameStart", global = true)
    public static boolean knoxWoundGameStart(double id, double kind, double woundKind, boolean cleaned, String item, double skill, double van) {
        if (MAC) return false;
        Canvas c = canvas((int) id);
        KnoxWoundGame g = new KnoxWoundGame();
        g.start((int) kind, (int) woundKind, cleaned, item == null ? "" : item, (int) skill, van);
        synchronized (c) {
            c.game = g;
        }
        c.dirty = true;
        return true;
    }

    static KnoxWoundGame game(double id) {
        Canvas c;
        synchronized (CANVASES) {
            c = CANVASES.get((int) id);
        }
        if (c == null) return null;
        synchronized (c) {
            return c.game;
        }
    }

    /**
     * One step of the game: the mouse in the picture's pixels (down: held, pressed: pressed since
     * the last step), dt seconds, the hand's tremor (units). Returns false with no game.
     */
    @LuaMethod(name = "knoxWoundGameTick", global = true)
    public static boolean knoxWoundGameTick(double id, double mxPx, double myPx, boolean down, boolean pressed, double dt, double shake) {
        Canvas c;
        synchronized (CANVASES) {
            c = CANVASES.get((int) id);
        }
        if (c == null) return false;
        KnoxWoundGame g;
        synchronized (c) {
            g = c.game;
        }
        if (g == null) return false;
        double s = c.s > 0 ? c.s : 1;
        synchronized (g) {
            g.shake = shake;
            g.input((mxPx - c.tx) / s, (myPx - c.ty) / s, down, pressed);
            g.tick(Math.max(0, Math.min(.1, dt)));
        }
        return true;
    }

    /**
     * The game's state: "prog" 0..1, "t" seconds played, "slips", "end" (1: showing its box),
     * "done" (1: over), "won" (1: by the player), "auto" (1: the character finished it),
     * "stage", "kind", "van", "took".
     */
    @LuaMethod(name = "knoxWoundGameGet", global = true)
    public static double knoxWoundGameGet(double id, String what) {
        KnoxWoundGame g = game(id);
        if (g == null) return -1;
        synchronized (g) {
            switch (what) {
                case "prog": return g.prog;
                case "t": return g.t;
                case "slips": return g.slips;
                case "end": return g.end > 0 ? 1 : 0;
                case "done": return g.done ? 1 : 0;
                case "won": return g.won() ? 1 : 0;
                case "auto": return g.auto ? 1 : 0;
                case "stage": return g.stage;
                case "kind": return g.kind;
                case "van": return g.van;
                case "took": return g.took;
                default: return -1;
            }
        }
    }

    /** The how-to line's translation key's tail for the game now ("HowClean"...). */
    @LuaMethod(name = "knoxWoundGameHow", global = true)
    public static String knoxWoundGameHow(double id) {
        KnoxWoundGame g = game(id);
        if (g == null) return null;
        synchronized (g) {
            return g.howKey();
        }
    }

    /** The normal time now known (the game's action measured): its seconds. */
    @LuaMethod(name = "knoxWoundGameVan", global = true)
    public static void knoxWoundGameVan(double id, double van) {
        KnoxWoundGame g = game(id);
        if (g == null || van <= 0) return;
        synchronized (g) {
            g.van = van;
        }
    }

    /** The character finished the step by itself (the game's action ran its time). */
    @LuaMethod(name = "knoxWoundGameAuto", global = true)
    public static void knoxWoundGameAuto(double id) {
        KnoxWoundGame g = game(id);
        if (g == null) return;
        synchronized (g) {
            g.autoFinish();
        }
    }

    /** The game off the picture. */
    @LuaMethod(name = "knoxWoundGameStop", global = true)
    public static void knoxWoundGameStop(double id) {
        Canvas c;
        synchronized (CANVASES) {
            c = CANVASES.get((int) id);
        }
        if (c == null) return;
        synchronized (c) {
            c.game = null;
        }
        c.dirty = true;
    }

    /** Sets a timed action's whole time (ticks): a mini-game's step takes the mockup's time. */
    @LuaMethod(name = "knoxActionMaxTime", global = true)
    public static boolean knoxActionMaxTime(Object action, double maxTime) {
        if (!(action instanceof zombie.characters.CharacterTimedActions.BaseAction)) return false;
        zombie.characters.CharacterTimedActions.BaseAction a = (zombie.characters.CharacterTimedActions.BaseAction) action;
        a.maxTime = Math.max(1, (int) Math.round(maxTime));
        return true;
    }

    /** A timed action's time so far (ticks) - with its whole, how fast the game's clock runs. */
    @LuaMethod(name = "knoxActionTime", global = true)
    public static double knoxActionTime(Object action) {
        if (!(action instanceof zombie.characters.CharacterTimedActions.BaseAction)) return -1;
        return ((zombie.characters.CharacterTimedActions.BaseAction) action).currentTime;
    }
}
