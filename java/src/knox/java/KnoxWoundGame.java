package knox.java;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Shape;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;

/**
 * The health tab's mini-games, played on the wound's picture (the author, 2026-11-10; the
 * mockup's `.claude/mockups/health/tool/mg3.js`): clean, stitch, bandage (variant 1: a pad, the
 * turns a third over each other, the knot), take a bandage off, pull glass out, set a bone and
 * strap the splint; and the undoing ones (the author 2026-11-11: a done step clicked is taken
 * back, with a game too): a clean bandage off (the bandage-off game), stitches out, a splint off. A result changes only the speed - played clean it is done sooner than the
 * game's own time, left alone the character finishes it in that time (the game's timed action
 * runs all the while; KnoxHealthGames ends it early on a win). Moodles shake the hand, the first
 * aid level widens the marks (tolK). Plain Java (no game classes): tools/health plays it with
 * the bot for the pictures beside the mockup's. Units: the picture's (KnoxWoundArt).
 */
public final class KnoxWoundGame {

    public static final int CLEAN = 0, STITCH = 1, BANDAGE = 2, UNB = 3, TW = 4, SPLINT = 5, UNSTITCH = 6, UNSPLINT = 7;
    // (methods, not arrays: a class swapped in the DEV reloader keeps its old arrays - 2026-11-11)
    /** A game's name (its how-to key's tail). */
    public static String name(int kind) {
        switch (kind) {
            case CLEAN: return "clean";
            case STITCH: return "stitch";
            case BANDAGE: return "bandage";
            case UNB: return "unb";
            case TW: return "tw";
            case SPLINT: return "splint";
            case UNSTITCH: return "unstitch";
            default: return "unsplint";
        }
    }

    /** mockup mg3.js: VAN - its "normal time" (the game's own action sets it in a game: `van`). */
    public static double van(int kind) {
        switch (kind) {
            case CLEAN: return 8;
            case STITCH: return 12;
            case BANDAGE: return 14;
            case UNB: return 6;
            case TW: return 9;
            case SPLINT: return 14;
            case UNSTITCH: return 8;
            default: return 9;
        }
    }

    /** mockup mg3.js: HIDE - what the picture leaves out while the game draws it itself. */
    public static int hide(int kind) {
        switch (kind) {
            case CLEAN: return KnoxWoundArt.HIDE_DIRT;
            case STITCH: case UNSTITCH: return KnoxWoundArt.HIDE_WOUND;
            case UNB: return KnoxWoundArt.HIDE_BAND;
            case TW: return KnoxWoundArt.HIDE_GLASS;
            case SPLINT: return KnoxWoundArt.HIDE_BONEFAR;
            case UNSPLINT: return KnoxWoundArt.HIDE_SPLINT;
            default: return 0;
        }
    }

    // unstitch: the stitches where the picture draws them (KnoxWoundArt.section: -.6, 0, .6 of
    // the gash's half-length), a stitch's pull to come out
    static final int OUT_N = 3;

    static double outF(int i) {
        return -.6 + .6 * i;
    }

    static final double OUT_L = 16;
    // mockup mg3.js: STUCK - where an old bandage has dried to the wound
    static final float[][] STUCK = {{-40, -24}, {-8, 12}, {30, 44}};
    static final float CX = KnoxWoundArt.CX, CY = KnoxWoundArt.CY;
    static final int RED = 0xe0483c, DULL = 0x9a4a40, SAND = 0xf1e3b2, MINT = 0x8fd0b0, WARN = 0xc9a24a;

    /** The words with numbers in them (the game: its translations; the tool: the RU file). */
    public interface Words {
        String word(String key, String... args);
    }

    public static Words WORDS = (key, args) -> key;

    // ------------------------------------------------------------------ state

    public int kind;
    public int woundKind;
    public boolean cleaned, lacer;
    public int skill;
    public double shake;            // the hand's tremor (mockup: tremor().a)
    public double van;              // the normal time in seconds (the game's action; the mockup's VAN offline)
    public boolean vanAuto;         // offline: the character finishes by itself at `van` (in a game: autoFinish)
    public double t, stun, pain, prog, end, took;
    public int slips;
    public boolean auto, done, frozen;

    // the mouse (units), as the player moves it; c: where the shaking hand really is
    public double mx = CX, my = 20;
    public boolean down, pressed;
    double cx = CX, cy = 20;

    // clean
    float[][] specks;               // x, y, left 0..1
    // stitch
    int n, k;
    double o = 1;
    boolean grab;                   // (the games that hold one thing)
    // bandage
    float[] bg, tg;
    int stage;
    final ArrayList<Double> turns = new ArrayList<>();
    boolean curOn;
    double curX, curYe, curOff;
    double hold, pw, ph, px, py, gx, gy, gapT, okT, overT, tgt, rollX;
    boolean fit;
    double tailSx, tailSy, tailEx, tailEy;
    int tailN;
    double[][] tailT;
    // unb
    double x0, x1, fx, gap;
    boolean stuckNow, cleanBand;     // cleanBand: a fresh bandage taken off (item "band")
    // unstitch: per stitch {pull, gone seconds}
    double[][] outs;
    int grabOut = -1;
    // tw
    double[][] shards;              // x, y, a, h, dx, dy, p, L, out
    int grabShard = -1;
    double g0x, g0y, g0p, g0ox, g0oy;
    // splint
    double ox, oy, fitT, hi = .82;
    double[][] straps;              // x, t, ok
    int grabStrap = -1;
    // the bot
    double bt, b0, bpx, bpy, bpox, bpoy;

    /** mockup mg3.js: tolK - the first aid level widens the marks. */
    public double tolK() {
        return .8 + skill * .07;
    }

    static double clamp(double v, double a, double b) {
        return Math.max(a, Math.min(b, v));
    }

    static double dist(double ax, double ay, double bx, double by) {
        return Math.hypot(ax - bx, ay - by);
    }

    static boolean inPatch(double x, double y) {
        return x > KnoxWoundArt.X0 && x < KnoxWoundArt.X1 && y > KnoxWoundArt.Y0 && y < KnoxWoundArt.Y1;
    }

    KnoxWoundArt.Geo geo() {
        return KnoxWoundArt.geo(woundKind);
    }

    // mockup mg3.js: stitchPts - the k-th stitch's two points across the gash
    double[] stitchPts(int kk) {
        KnoxWoundArt.Geo G = geo();
        double x = G.cx + G.L * (n > 1 ? -.6 + 1.2 * kk / (n - 1) : 0), yc = G.cyAt(x);
        return new double[]{x, yc - G.hw(x, 0, o, 1) - 5, yc + G.hw(x, 1, o, 1) + 5};
    }

    // mockup mg3.js: shardTip
    static double[] shardTip(double[] sh) {
        return new double[]{sh[0] + sh[4] * (sh[3] * .7 + sh[6]), sh[1] + sh[5] * (sh[3] * .7 + sh[6])};
    }

    /**
     * mockup mg3.js: startGame. item: "suture" (3 stitches, else 5), "rag" (4 turns a side, else
     * 3); skill: the first aid level; van: the normal time in seconds.
     */
    public void start(int kind, int woundKind, boolean cleaned, String item, int skill, double van) {
        this.kind = kind;
        this.woundKind = woundKind;
        this.cleaned = cleaned;
        this.lacer = woundKind == KnoxWoundArt.KIND_LACER || woundKind == KnoxWoundArt.KIND_BITE;
        this.skill = skill;
        this.van = van > 0 ? van : van(kind);
        t = stun = pain = prog = end = took = 0;
        slips = 0;
        auto = done = frozen = false;
        grab = false;
        grabShard = grabStrap = grabOut = -1;
        bt = 0;
        bg = KnoxWoundArt.bandGeo(woundKind);
        if (kind == CLEAN) {
            float[][] sp = KnoxWoundArt.dirtSpecks(geo());
            specks = new float[sp.length][3];
            for (int i = 0; i < sp.length; i++) {
                specks[i][0] = sp[i][0];
                specks[i][1] = sp[i][1];
                specks[i][2] = 1;
            }
        }
        if (kind == STITCH) {
            n = ("suture".equals(item) ? 3 : 5) - (skill >= 6 ? 1 : 0);
            k = 0;
            o = 1;
        }
        if (kind == BANDAGE) {
            tg = KnoxWoundArt.turnGeo(bg, ("rag".equals(item) ? 4 : 3) * 2);
            stage = 0;
            turns.clear();
            curOn = false;
            hold = 0;
            KnoxWoundArt.Geo G = geo();
            pw = 2 * G.L + 14;
            ph = 2 * G.W + 22;
            px = CX - 64;
            py = CY - 50;
            fit = false;
            gapT = okT = overT = 0;
        }
        if (kind == UNB) {
            x0 = bg[0] - 4;
            x1 = bg[0] + bg[1] + 4;
            fx = x0;
            cleanBand = "band".equals(item);
        }
        if (kind == UNSTITCH) {
            outs = new double[OUT_N][2];
        }
        if (kind == UNSPLINT) {
            straps = new double[KnoxWoundArt.FRX_STRAPS.length][];
            for (int i = 0; i < straps.length; i++) straps[i] = new double[]{KnoxWoundArt.FRX_STRAPS[i], 1.5, 0};
        }
        if (kind == TW) {
            shards = new double[KnoxWoundArt.SHARDS.length][];
            for (int i = 0; i < shards.length; i++) {
                float[] s = KnoxWoundArt.SHARDS[i];
                double a = s[2], h = s[3];
                shards[i] = new double[]{CX + s[0], CY + s[1], a, h, Math.sin(Math.toRadians(a)), -Math.cos(Math.toRadians(a)), 0, h * .4 + 5, 0};
            }
        }
        if (kind == SPLINT) {
            stage = 0;
            ox = 5;
            oy = -6;
            fitT = 0;
            straps = new double[KnoxWoundArt.FRX_STRAPS.length][];
            for (int i = 0; i < straps.length; i++) straps[i] = new double[]{KnoxWoundArt.FRX_STRAPS[i], 0, 0};
        }
        down = pressed = false;
    }

    /** The mouse as the player moves it (units); pressedNow: pressed since the last tick. */
    public void input(double x, double y, boolean isDown, boolean pressedNow) {
        mx = x;
        my = y;
        down = isDown;
        if (pressedNow) pressed = true;
    }

    // mockup mg3.js: hurt - a slip: a moment's pause, a red flash, what was held let go
    void hurt() {
        if (stun > 0) return;
        pain = 1;
        stun = .55;
        slips++;
        grab = false;
        grabShard = grabStrap = grabOut = -1;
    }

    // mockup mg3.js: win (the box with the time for 1.6 s, then done)
    void win(boolean byItself) {
        if (end > 0 || done) return;
        auto = byItself;
        end = 1.6;
        took = t;
        prog = 1;
    }

    /** The character finished it by itself (the game's action ran its time). */
    public void autoFinish() {
        win(true);
    }

    /** Won by the player (not by the character's own pace). */
    public boolean won() {
        return (end > 0 || done) && !auto;
    }

    /** mockup mg3.js: mgTick - one step of the game (dt seconds). */
    public void tick(double dt) {
        if (done || frozen) return;
        if (end > 0) {
            end -= dt;
            if (end <= 0) {
                end = 0;
                done = true;
            }
            pressed = false;
            return;
        }
        t += dt;
        pain = Math.max(0, pain - dt * 2);
        if (vanAuto && t >= van) {
            win(true);
            return;
        }
        double T = tolK(), a = shake;
        cx = mx + a * (Math.sin(t * 31) * .6 + Math.sin(t * 17.3 + 1) * .4);
        cy = my + a * (Math.sin(t * 27 + 2) * .6 + Math.sin(t * 13.7) * .4);
        boolean live = stun <= 0;
        if (!live) stun -= dt;
        boolean press = live && pressed, dn = live && down;
        KnoxWoundArt.Geo G = geo();
        if (kind == CLEAN) {
            if (dn) {
                if (inWound(G, .3, 1)) hurt();
                else for (float[] sp : specks) {
                    if (sp[2] > 0 && Math.abs(sp[0] - cx) < 8 + 2 * T && Math.abs(sp[1] - cy) < 5.5 + 2 * T) sp[2] -= dt * 3.4;
                }
            }
            double left = 0;
            for (float[] sp : specks) left += Math.max(0, sp[2]);
            prog = 1 - left / specks.length;
            if (prog >= .999) win(false);
        } else if (kind == STITCH) {
            o += ((1 - .9 * k / n) - o) * Math.min(1, dt * 8);
            double[] p = stitchPts(k);
            double r = 5.5 * T;
            if (press) {
                if (dist(cx, cy, p[0], p[1]) < r) grab = true;
                else if (inWound(G, .8, o)) hurt();
            }
            if (grab && !down) {
                grab = false;
                if (dist(cx, cy, p[0], p[2]) < r) {
                    k++;
                    if (k >= n) win(false);
                } else hurt();
            }
            prog = (double) k / n;
        } else if (kind == BANDAGE) {
            bandageTick(dt, T, press, dn);
        } else if (kind == UNB) {
            if (press && Math.abs(cx - fx) < 10 * T && inPatch(cx, cy)) grab = true;
            if (!down) grab = false;
            gap = 0;
            stuckNow = false;
            for (float[] z : STUCK) if (fx >= CX + z[0] && fx <= CX + z[1]) stuckNow = true;
            if (grab && dn) {
                double gp = cx - fx;
                gap = gp / (16 * T);
                if (stuckNow) {
                    if (gp > 16 * T) {
                        hurt();
                        fx += 5;
                    } else if (gp > 0) fx += Math.min(gp, dt * 40);
                } else if (gp > 0) fx += Math.min(gp, dt * 240);
                if (fx >= x1) win(false);
            }
            prog = clamp((fx - x0) / (x1 - x0), 0, 1);
        } else if (kind == TW) {
            if (!down) grabShard = -1;
            if (press) {
                for (int i = 0; i < shards.length; i++) {
                    double[] sh = shards[i], tp = shardTip(sh);
                    if (sh[8] == 0 && dist(cx, cy, tp[0], tp[1]) < 8 * T) {
                        grabShard = i;
                        g0x = cx;
                        g0y = cy;
                        g0p = sh[6];
                        break;
                    }
                }
            }
            if (grabShard >= 0 && dn) {
                double[] sh = shards[grabShard];
                double vx = cx - g0x, vy = cy - g0y, al = vx * sh[4] + vy * sh[5], lat = Math.abs(vx * sh[5] - vy * sh[4]);
                if (lat > 5.5 * T) {
                    sh[6] = Math.max(0, sh[6] - 4);
                    hurt();
                } else {
                    sh[6] = clamp(g0p + al, 0, sh[7] + 1);
                    if (sh[6] >= sh[7]) {
                        sh[8] = .001;
                        grabShard = -1;
                    }
                }
            }
            double q = 0;
            int left = 0;
            for (double[] sh : shards) {
                if (sh[8] > 0) sh[8] += dt;
                else left++;
                q += Math.min(1, sh[6] / sh[7]);
            }
            prog = q / shards.length;
            if (left == 0) win(false);
        } else if (kind == SPLINT) {
            if (stage == 0) {
                if (!down) grab = false;
                if (press && cx > KnoxWoundArt.FRX_FX + 4 && cx < KnoxWoundArt.FRX_WR && cy > KnoxWoundArt.FRX_WT && cy < KnoxWoundArt.FRX_WB) {
                    grab = true;
                    g0x = cx;
                    g0y = cy;
                    g0ox = ox;
                    g0oy = oy;
                }
                if (grab && dn) {
                    ox = clamp(g0ox + cx - g0x, -12, 14);
                    oy = clamp(g0oy + cy - g0y, -12, 12);
                    if (Math.abs(ox) < 2.6 * T && Math.abs(oy) < 2.6 * T) {
                        fitT += dt;
                        if (fitT > .3) {
                            ox = oy = 0;
                            stage = 1;
                            grab = false;
                        }
                    } else fitT = Math.max(0, fitT - dt * .5);
                }
                prog = .4 * Math.max(0, 1 - (Math.abs(ox) + Math.abs(oy)) / 11);
            } else {
                hi = .6 + .24 * T;
                if (press) {
                    for (int i = 0; i < straps.length; i++) {
                        double[] st = straps[i];
                        if (st[2] == 0 && Math.abs(cx - st[0]) < 10 * T && inPatch(cx, cy)) {
                            grabStrap = i;
                            break;
                        }
                    }
                }
                for (int i = 0; i < straps.length; i++) {
                    double[] st = straps[i];
                    if (st[2] == 0 && i != grabStrap) st[1] = Math.max(0, st[1] - dt * .25);
                }
                if (grabStrap >= 0) {
                    double[] st = straps[grabStrap];
                    if (down) {
                        if (live) {
                            st[1] += dt * .8;
                            if (st[1] >= 1) {
                                st[1] = 0;
                                hurt();
                            }
                        }
                    } else {
                        if (st[1] >= .6 && st[1] <= hi) st[2] = 1;
                        else if (st[1] > hi) {
                            st[1] = 0;
                            hurt();
                        }
                        grabStrap = -1;
                    }
                }
                int nOk = 0;
                for (double[] st : straps) if (st[2] != 0) nOk++;
                prog = .4 + .2 * nOk;
                if (nOk == straps.length) win(false);
            }
        } else if (kind == UNSTITCH) {
            // a stitch's knot gripped, its thread drawn straight up; a pull aside tears the skin
            if (!down) {
                if (grabOut >= 0 && outs[grabOut][0] < OUT_L) outs[grabOut][0] = 0;
                grabOut = -1;
            }
            if (press) {
                for (int i = 0; i < outs.length; i++) {
                    double[] k0 = knot(i);
                    if (outs[i][1] == 0 && dist(cx, cy, k0[0], k0[1]) < 6 * T) {
                        grabOut = i;
                        g0x = cx;
                        g0y = cy;
                        break;
                    }
                }
            }
            if (grabOut >= 0 && dn) {
                double[] o2 = outs[grabOut];
                if (Math.abs(cx - g0x) > 5.5 * T) {
                    o2[0] = 0;
                    hurt();
                } else {
                    o2[0] = clamp(g0y - cy, 0, OUT_L);
                    if (o2[0] >= OUT_L) {
                        o2[1] = .001;
                        grabOut = -1;
                    }
                }
            }
            double q = 0;
            int left = 0;
            for (double[] o2 : outs) {
                if (o2[1] > 0) o2[1] += dt;
                else left++;
                q += o2[1] > 0 ? 1 : o2[0] / OUT_L * .8;
            }
            prog = q / outs.length;
            if (left == 0) win(false);
        } else if (kind == UNSPLINT) {
            // a strap held still loosens; a jerk of the hand jolts the bone
            if (press) {
                for (int i = 0; i < straps.length; i++) {
                    double[] st = straps[i];
                    if (st[2] == 0 && Math.abs(cx - st[0]) < 10 * T && inPatch(cx, cy)) {
                        grabStrap = i;
                        g0x = cx;
                        g0y = cy;
                        break;
                    }
                }
            }
            if (!down) grabStrap = -1;
            if (grabStrap >= 0 && dn) {
                double[] st = straps[grabStrap];
                if (dist(cx, cy, g0x, g0y) > 7 * T) hurt();
                else {
                    st[1] = Math.max(0, st[1] - dt * 1.4);
                    if (st[1] <= 0) {
                        st[2] = 1;
                        grabStrap = -1;
                    }
                }
            }
            double q = 0;
            int nOk = 0;
            for (double[] st : straps) {
                if (st[2] != 0) nOk++;
                q += 1 - st[1] / 1.5;
            }
            prog = q / straps.length;
            if (nOk == straps.length) win(false);
        }
        pressed = false;
    }

    // unstitch: the i-th stitch's knot (KnoxWoundArt.stitch: its top cross)
    double[] knot(int i) {
        KnoxWoundArt.Geo G = geo();
        double x = CX + outF(i) * G.L;
        return new double[]{x, G.cyAt(x) - 7};
    }

    boolean inWound(KnoxWoundArt.Geo G, double f, double open) {
        double yc = G.cyAt(cx);
        return Math.abs(cx - G.cx) < G.L * .92 && Math.abs(cy - yc) < G.hw(cx, cy > yc ? 1 : 0, open, f);
    }

    // mockup mg3.js: the bandage's three stages
    void bandageTick(double dt, double T, boolean press, boolean dn) {
        double yt = KnoxWoundArt.TURN_YT, yb = KnoxWoundArt.TURN_YB;
        gapT = Math.max(0, gapT - dt);
        okT = Math.max(0, okT - dt);
        overT = Math.max(0, overT - dt);
        if (stage == 0) {
            double tol = 7 * T;
            if (press && Math.abs(cx - px) < pw / 2 && Math.abs(cy - py) < ph / 2) {
                grab = true;
                gx = cx - px;
                gy = cy - py;
            }
            if (!down) grab = false;
            if (grab && dn) {
                px = cx - gx;
                py = cy - gy;
            }
            fit = Math.hypot(px - CX, py - CY) < tol;
            if (grab && dn && fit) {
                hold += dt;
                if (hold >= .7) {
                    px = CX;
                    py = CY;
                    stage = 1;
                    grab = false;
                }
            } else hold = Math.max(0, hold - dt * 1.5);
            prog = .2 * (fit ? .5 + .5 * hold / .7 : 0);
        } else if (stage == 1) {
            double tol = 6 * T;
            int nt = turns.size();
            tgt = nt > 0 ? turns.get(nt - 1) + tg[0] : KnoxWoundArt.turnLeft(bg, tg, 0);
            rollX = nt > 0 ? turns.get(nt - 1) + tg[0] * .6 : tgt - 10;
            if (!curOn) {
                if (press && Math.abs(cx - (rollX + tg[1] / 2)) < tg[1] / 2 + 6 && Math.abs(cy - yt - 4) < 14) {
                    curOn = true;
                    curX = rollX;
                    curYe = yt + 4;
                    curOff = cx - rollX;
                    grab = true;
                }
            } else {
                if (!grab && press && Math.abs(cy - curYe) < 12 && cx > curX - 8 && cx < curX + tg[1] + KnoxWoundArt.TURN_SLANT + 8) {
                    grab = true;
                    curOff = cx - curX;
                }
                if (!down) grab = false;
                if (grab && dn) {
                    curX = cx - curOff;
                    if (cy - curYe > 34) {   // jerked the roll: too tight
                        hurt();
                        curYe = Math.max(yt + 4, curYe - 16);
                    } else curYe = Math.min(yb, Math.max(curYe, Math.min(cy, curYe + 240 * dt)));
                    if (curYe >= yb - .5) {
                        double err = curX - tgt;
                        if (err > tol) {   // a gap - the turn slides off
                            gapT = 1.4;
                            curOn = false;
                            grab = false;
                        } else {
                            turns.add(curX);
                            if (err < -tol) overT = 1.4;
                            else okT = 1;
                            curOn = false;
                            grab = false;
                            double right = turns.get(turns.size() - 1) + tg[1] + KnoxWoundArt.TURN_SLANT / 2;
                            if (right >= bg[0] + bg[1] - 2 || turns.size() >= tg[2] + 3) {
                                stage = 2;
                                tailSx = right;
                                tailSy = CY + 30;
                                tailEx = tailSx;
                                tailEy = tailSy;
                                tailN = 0;
                                tailT = new double[][]{{Math.min(KnoxWoundArt.X1 - 12, tailSx + 24), CY - 36}, {Math.min(KnoxWoundArt.X1 - 12, tailSx + 30), CY + 8}};
                            }
                        }
                    }
                }
            }
            int n2 = turns.size();
            double right = n2 > 0 ? turns.get(n2 - 1) + tg[1] + KnoxWoundArt.TURN_SLANT / 2 : bg[0];
            double part = curOn ? (curYe - yt) / (yb - yt) * tg[0] : 0;
            prog = .2 + .65 * clamp((right - bg[0] + part) / bg[1], 0, 1);
        } else {
            double[] to = tailT[tailN];
            double fromX = tailN > 0 ? tailT[tailN - 1][0] : tailSx, fromY = tailN > 0 ? tailT[tailN - 1][1] : tailSy;
            if (press && dist(cx, cy, tailEx, tailEy) < 9 * T) grab = true;
            if (grab && !down) {
                grab = false;
                if (dist(cx, cy, to[0], to[1]) < 7 * T) {
                    tailN++;
                    tailEx = to[0];
                    tailEy = to[1];
                    if (tailN >= 2) win(false);
                } else {
                    tailEx = fromX;
                    tailEy = fromY;
                }
            } else if (grab && dn) {
                tailEx = cx;
                tailEy = cy;
            }
            if (!down) grab = false;
            prog = .85 + .075 * tailN;
        }
    }

    // ------------------------------------------------------------------ drawing

    /**
     * mockup mg3.js: drawGame - the game over the picture (drawn first by KnoxWoundArt.section
     * with hide(kind) and no notes): what is left to do, the marks, the tool in the hand, the
     * messages, the red flash of a slip, the box with the time at the end.
     */
    public void draw(KnoxWoundArt a) {
        double T = tolK(), tt = t;
        KnoxWoundArt.Geo G = geo();
        double x = cx, y = cy;
        if (kind == CLEAN) {
            for (float[] sp : specks) {
                if (sp[2] > 0) a.fill(KnoxWoundArt.rect(sp[0] - 1.4, sp[1] - 1.4, 2.8, 2.8), lacer ? 0xb9c24a : 0xc9aa4a, clamp(sp[2], 0, 1));
            }
        } else if (kind == STITCH) {
            a.inkWound(G, o, cleaned ? DULL : RED, true);
            for (int j = 0; j < k; j++) {
                double[] q = stitchPts(j);
                a.stroke(KnoxWoundArt.quad(q[0], q[1] + 2, q[0] + 2.5, (q[1] + q[2]) / 2, q[0], q[2] - 2), 0xd9d6c8, 1, 1.2);
                a.cross(q[0], q[1]);
                a.cross(q[0], q[2] - 4);
            }
            if (k < n) {
                double[] q = stitchPts(k);
                if (grab) {
                    a.fill(KnoxWoundArt.ellipse(q[0], q[1], 2, 2), SAND, 1);
                    a.stroke(KnoxWoundArt.line(q[0], q[1], x, y), 0xd9d6c8, 1, 1.1);
                    a.ring(q[0], q[2], 5.5 * T, SAND, tt);
                    a.fill(KnoxWoundArt.ellipse(q[0], q[2], 2, 2), SAND, 1);
                } else {
                    a.ring(q[0], q[1], 5.5 * T, SAND, tt);
                    a.fill(KnoxWoundArt.ellipse(q[0], q[1], 2, 2), SAND, 1);
                    a.dashed(KnoxWoundArt.ellipse(q[0], q[2], 5.5 * T, 5.5 * T), 0x8f9289, 1, 1, 2, 2);
                }
            }
        } else if (kind == BANDAGE) {
            drawBandage(a, T, tt);
        } else if (kind == UNB) {
            Shape clip = a.g.getClip();
            a.g.clip(KnoxWoundArt.rect(fx, 0, 480, 170));
            a.bandage(woundKind, 3, !cleanBand);
            a.g.setClip(clip);
            boolean named = false;
            for (float[] z : STUCK) {
                if (fx > CX + z[1]) continue;
                boolean on = stuckNow && fx >= CX + z[0];
                int c = on ? 0xe0a84a : 0x8a7a5a;
                double xa = Math.max(CX + z[0], fx);
                for (double xx = xa; xx <= CX + z[1]; xx += 4) a.dashed(KnoxWoundArt.line(xx, CY - 20, xx, CY + 20), c, .8, 1, 2, 3);
                if (!named) {
                    named = true;
                    a.text("GameStuck", CX + z[1] + 4, CY - 25, c, 1, 7, 1, 600, .5f);
                }
            }
            double ten = clamp(gap, 0, 1);
            int c = ten < .5 ? 0x9fb36a : ten < .85 ? WARN : RED;
            double y0 = KnoxWoundArt.Y0, y1 = KnoxWoundArt.Y1;
            if (grab) {
                Path2D.Float p = new Path2D.Float();
                p.moveTo(fx, y0);
                p.lineTo(Math.max(fx + 2, x), y0 + 7);
                p.lineTo(Math.max(fx + 2, x), y1 - 7);
                p.lineTo(fx, y1);
                p.closePath();
                a.fill(p, c, .22);
                a.stroke(p, c, 1, 1.4);
            } else {
                Rectangle2D.Float r = KnoxWoundArt.rect(fx - 4, y0, 5, y1 - y0);
                a.fill(r, 0x4a4230, 1);
                a.stroke(r, 0x8a7a5a, 1, 1);
                a.ring(fx - 1, CY - 44, 6, SAND, tt);
                a.ring(fx - 1, CY + 44, 6, SAND, tt + 1);
            }
        } else if (kind == TW) {
            for (int i = 0; i < shards.length; i++) {
                double[] sh = shards[i];
                double p = sh[8] > 0 ? sh[7] + sh[8] * 46 : sh[6], op = sh[8] > 0 ? Math.max(0, 1 - sh[8] * 2.4) : 1;
                if (op <= 0) continue;
                java.awt.geom.AffineTransform at = a.g.getTransform();
                Composite was = a.g.getComposite();
                a.g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) op));
                a.g.translate(sh[0] + sh[4] * p, sh[1] + sh[5] * p);
                a.g.rotate(Math.toRadians(sh[2]));
                Path2D.Float s = KnoxWoundArt.shardPath(sh[3]);
                a.fill(s, 0x8ec5e0, .2);
                a.stroke(s, 0x8ec5e0, 1, 1.2, true, false);
                a.g.setTransform(at);
                a.g.setComposite(was);
                double[] tp = shardTip(sh);
                if (grabShard == i) {
                    double w = 5.5 * T, nx = -sh[5], ny = sh[4];
                    for (int sg = -1; sg <= 1; sg += 2) {
                        double ax = tp[0] + nx * w * sg - sh[4] * 8, ay = tp[1] + ny * w * sg - sh[5] * 8;
                        a.dashed(KnoxWoundArt.line(ax, ay, ax + sh[4] * 40, ay + sh[5] * 40), 0x8ec5e0, .6, .8, 3, 3);
                    }
                } else if (sh[8] == 0 && grabShard < 0) a.ring(tp[0], tp[1], 6, SAND, tt + sh[2]);
            }
        } else if (kind == SPLINT) {
            boolean near = stage == 0 && Math.abs(ox) < 2.6 * T && Math.abs(oy) < 2.6 * T;
            if (stage == 1) {
                a.boards();
                for (int i = 0; i < straps.length; i++) {
                    double[] st = straps[i];
                    a.strap(st[0], st[2] != 0 ? MINT : (grabStrap == i ? SAND : 0xcfc9b4), st[2] != 0 ? 1.5 : st[1] * 1.5);
                }
            }
            a.xrayOpen(stage == 1);
            a.bone(KnoxWoundArt.boneL(), 0x8ec5e0);
            if (stage == 0) a.dashed(KnoxWoundArt.boneR(0, 0), 0x8ec5e0, .55, 1, 3, 3);
            a.bone(KnoxWoundArt.boneR(ox, oy), near || stage > 0 ? MINT : 0x8ec5e0);
            a.xrayClose();
            if (stage == 0 && !grab) a.ring(KnoxWoundArt.FRX_FX + 44 + ox, CY + oy, 7, SAND, tt);
            if (stage == 1) {
                for (int i = 0; i < straps.length; i++) {
                    double[] st = straps[i];
                    double gy = 14, GH = 14, h = hi;
                    a.fill(KnoxWoundArt.rect(st[0] - 3, gy, 6, GH), 0x0b0c0c, 1);
                    a.stroke(KnoxWoundArt.rect(st[0] - 3, gy, 6, GH), 0x3c4141, 1, .8);
                    a.fill(KnoxWoundArt.rect(st[0] - 3, gy + GH * (1 - h), 6, GH * (h - .6)), 0x6ca050, .55);
                    a.fill(KnoxWoundArt.rect(st[0] - 2, gy + GH * (1 - st[1]), 4, GH * st[1]), st[2] != 0 ? MINT : st[1] > h ? RED : SAND, 1);
                    if (st[2] == 0 && grabStrap < 0) a.ring(st[0], CY + 52, 7, SAND, tt + st[0]);
                }
            }
        } else if (kind == UNSTITCH) {
            Path2D.Float sl = G.ring(1, .1);
            a.fill(sl, 0x170a09, 1);
            a.stroke(sl, DULL, 1, 1.1, true, false);
            for (int i = 0; i < outs.length; i++) {
                double[] o2 = outs[i], k0 = knot(i);
                double p = o2[1] > 0 ? OUT_L + o2[1] * 40 : o2[0], op = o2[1] > 0 ? Math.max(0, 1 - o2[1] * 2.4) : 1;
                if (op <= 0) continue;
                double kx = k0[0], ky = k0[1] - p, by = k0[1] + 14 - Math.min(14, p);
                // the thread from the knot down through the skin, shorter as it comes out
                a.stroke(KnoxWoundArt.quad(kx, ky + 2, kx + 2.5, (ky + by) / 2, kx, by), 0xd9d6c8, op, 1.2);
                a.cross(kx, ky - 2);
                if (p == 0) a.cross(kx, k0[1] + 12);
                if (grabOut == i) {
                    for (int sg = -1; sg <= 1; sg += 2) {
                        double ax = g0x + sg * 5.5 * T;
                        a.dashed(KnoxWoundArt.line(ax, k0[1] + 4, ax, k0[1] - OUT_L - 8), SAND, .6, .8, 3, 3);
                    }
                } else if (o2[1] == 0 && grabOut < 0) a.ring(kx, k0[1], 6, SAND, tt + i);
            }
        } else if (kind == UNSPLINT) {
            a.boards();
            for (int i = 0; i < straps.length; i++) {
                double[] st = straps[i];
                if (st[2] != 0) continue;
                a.strap(st[0], grabStrap == i ? SAND : 0xcfc9b4, st[1]);
            }
            a.xrayOpen(true);
            a.bone(KnoxWoundArt.boneL(), 0x8ec5e0);
            a.bone(KnoxWoundArt.boneR(0, 0), stun > 0 ? RED : 0x8ec5e0);
            a.xrayClose();
            for (int i = 0; i < straps.length; i++) {
                double[] st = straps[i];
                if (st[2] != 0) continue;
                if (grabStrap == i) a.dashed(KnoxWoundArt.ellipse(g0x, g0y, 7 * T, 7 * T), SAND, .6, .8, 3, 3);
                else if (grabStrap < 0) a.ring(st[0], CY + 52, 7, SAND, tt + st[0]);
            }
        }
        if (pain > 0) {
            a.fill(KnoxWoundArt.rect(0, 0, 480, 170), 0xbe463c, .24 * pain);
            if (stun > 0) a.text("GamePain", CX, 26, RED, 1, 10, 0, 700, 1);
        }
        if (end > 0 || done) {
            a.fill(KnoxWoundArt.rect(CX - 92, CY - 22, 184, 44), 0x121414, 1);
            a.stroke(KnoxWoundArt.rect(CX - 92, CY - 22, 184, 44), 0xd4c38a, 1, 1);
            String l1, l2;
            if (auto) {
                l1 = WORDS.word("GameAuto");
                l2 = WORDS.word("GameSelf");
            } else {
                double kk = van / Math.max(.1, took);
                l1 = WORDS.word("GameDoneIn", num(took));
                l2 = WORDS.word("GameFaster", num(van), num(kk));
            }
            KnoxWoundArt.Text a1 = a.text(l1, CX, CY - 3, 0xece8d8, 1, 11, 0, 700, .8f);
            a1.raw = true;
            KnoxWoundArt.Text a2 = a.text(l2, CX, CY + 12, 0x8f9289, 1, 8, 0, 500, 0);
            a2.raw = true;
        } else tool(a, x, y);
    }

    static String num(double v) {
        String s = String.format(java.util.Locale.ROOT, "%.1f", v);
        String dec = WORDS.word("Decimal");
        return dec == null || dec.isEmpty() || dec.equals("Decimal") ? s : s.replace(".", dec);
    }

    // the thing in the hand: a wipe, a needle, tweezers, a square
    void tool(KnoxWoundArt a, double x, double y) {
        if (kind == CLEAN) {
            java.awt.geom.RoundRectangle2D.Double r = new java.awt.geom.RoundRectangle2D.Double(x - 8, y - 5.5, 16, 11, 3, 3);
            a.fill(r, 0xcfc9b4, down ? .92 : .6);
            a.stroke(r, 0xece8d8, 1, 1);
            a.fill(r, 0x8a7a3a, prog * .5);
            Path2D.Float p = new Path2D.Float();
            p.moveTo(x - 4, y - 5.5);
            p.lineTo(x - 4, y + 5.5);
            p.moveTo(x + 4, y - 5.5);
            p.lineTo(x + 4, y + 5.5);
            a.stroke(p, 0x8f9289, 1, .6);
        } else if (kind == STITCH) {
            a.stroke(KnoxWoundArt.quad(x, y, x + 5, y - 9, x + 12, y - 6), 0xece8d8, 1, 1.4, false, true);
            a.fill(KnoxWoundArt.ellipse(x, y, 1.2, 1.2), 0xece8d8, 1);
        } else if (kind == TW || kind == UNSTITCH) {
            Path2D.Float p = new Path2D.Float();
            p.moveTo(x, y);
            p.lineTo(x + 6, y - 20);
            p.moveTo(x + 1, y);
            p.lineTo(x + 14, y - 16);
            a.stroke(p, 0xcfd3cf, 1, 1.5, false, true);
        } else {
            a.stroke(KnoxWoundArt.rect(x - 2.2, y - 2.2, 4.4, 4.4), 0xece8d8, 1, 1.1);
        }
    }

    void drawBandage(KnoxWoundArt a, double T, double tt) {
        double yt = KnoxWoundArt.TURN_YT, yb = KnoxWoundArt.TURN_YB;
        double x = cx, y = cy;
        if (stage == 0) {
            a.dashed(KnoxWoundArt.rect(CX - pw / 2, CY - ph / 2, pw, ph), fit ? MINT : SAND, 1, 1, 4, 3);
            a.pad(px, py, pw, ph);
            if (grab && fit) {
                double ang = Math.min(1, hold / .7) * 360, r = 9;
                a.stroke(new Arc2D.Double(x - r, y - r, 2 * r, 2 * r, 90, -ang, Arc2D.OPEN), MINT, 1, 2);
            }
            if (!grab) a.ring(px, py, 9, SAND, tt);
            return;
        }
        a.pad(CX, CY, pw, ph);
        Shape clip = a.g.getClip();
        a.g.clip(KnoxWoundArt.arm());
        for (int j = 0; j < turns.size(); j++) a.turn(tg, j, yb, false, turns.get(j));
        if (stage == 1 && curOn) a.turn(tg, turns.size(), curYe, false, curX);
        a.g.setClip(clip);
        if (stage == 1) {
            double tol = 6 * T, err = curOn ? curX - tgt : 0;
            int c = !curOn ? SAND : err > tol ? RED : err < -tol ? WARN : MINT;
            Path2D.Float zone = new Path2D.Float();
            zone.moveTo(tgt + 0, yt + 4);
            zone.lineTo(tgt + tg[1], yt + 4);
            zone.lineTo(tgt + KnoxWoundArt.TURN_SLANT + tg[1], yb - 4);
            zone.lineTo(tgt + KnoxWoundArt.TURN_SLANT, yb - 4);
            zone.closePath();
            a.fill(zone, c, .08);
            a.dashed(zone, c, 1, 1, 4, 3);
            if (!turns.isEmpty()) {
                double pl = turns.get(turns.size() - 1) + tg[1], xc = tgt + KnoxWoundArt.TURN_SLANT * (CY - yt) / (yb - yt);
                a.stroke(KnoxWoundArt.line(xc, CY, pl + KnoxWoundArt.TURN_SLANT * (CY - yt) / (yb - yt), CY), c, 1, 1.4);
                a.text("GameThird", (float) (xc + 2), CY - 4, c, 1, 6.5f, 1, 600, 0);
            }
            double rx = curOn ? curX : rollX, ry = curOn ? curYe : yt + 4;
            a.roll(tg, turns.size(), Math.max(yt + 5, ry), false, rx);
            if (!grab) a.ring(rx + tg[1] / 2 + KnoxWoundArt.TURN_SLANT * (ry - yt) / (yb - yt), ry, 8, SAND, tt);
            String msg = gapT > 0 ? "GameGap" : overT > 0 ? "GameOver" : okT > 0 ? "GameEven" : null;
            int mc = gapT > 0 ? RED : overT > 0 ? WARN : MINT;
            if (msg != null && stun <= 0) a.text(msg, CX, 26, mc, 1, 8, 0, 600, .6f);
        } else {
            Path2D.Float d = new Path2D.Float();
            d.moveTo(tailSx, tailSy);
            for (int j = 0; j < tailN; j++) d.lineTo(tailT[j][0], tailT[j][1]);
            d.lineTo(tailEx, tailEy);
            a.stroke(d, 0xe6e0d0, .85, 7, true, true);
            if (tailN > 0) {
                a.fill(KnoxWoundArt.ellipse(tailT[0][0], tailT[0][1], 5, 5), 0xddd6c6, 1);
                a.stroke(KnoxWoundArt.ellipse(tailT[0][0], tailT[0][1], 5, 5), 0x8d8676, 1, .7);
            }
            if (tailN < 2) {
                double[] to = tailT[tailN];
                a.ring(to[0], to[1], 7 * T, SAND, tt);
                a.fill(KnoxWoundArt.ellipse(to[0], to[1], 2, 2), SAND, 1);
                if (!grab) a.ring(tailEx, tailEy, 6, 0xece8d8, tt + 1);
            }
        }
    }

    /** The how-to line over the game (mockup mg3.js: HOW): its translation key's tail. */
    public String howKey() {
        if (kind == SPLINT && stage > 0) return "HowSplint2";
        if (kind == BANDAGE && stage > 0) return "HowBandage" + stage;
        String nm = name(kind);
        return "How" + Character.toUpperCase(nm.charAt(0)) + nm.substring(1);
    }

    // ------------------------------------------------------------------ the bot

    /** mockup mg3.js: bot - a steady hand that plays by the rules (the checks' own player). */
    public void bot() {
        bt += 1 / 60.0;
        if (stun > 0) {
            down = false;
            return;
        }
        boolean holding = grab || grabShard >= 0 || grabStrap >= 0 || grabOut >= 0;
        if (!holding && down && kind != CLEAN) {
            down = false;
            return;
        }
        KnoxWoundArt.Geo G = geo();
        if (kind == CLEAN) {
            for (float[] sp : specks) {
                if (sp[2] > 0) {
                    mx = sp[0];
                    my = sp[1] + (sp[1] > G.cyAt(sp[0]) ? 4 : -4);
                    if (!down) pressAt(mx, my);
                    break;
                }
            }
        } else if (kind == STITCH) {
            double[] p = stitchPts(k);
            if (!grab) {
                pressAt(p[0], p[1]);
                b0 = bt;
                bpy = p[1];
            } else {
                double f = Math.min(1, (bt - b0) / .3);
                mx = p[0];
                my = bpy + (p[2] - bpy) * f;
                if (f >= 1) down = false;
            }
        } else if (kind == BANDAGE) {
            if (stage == 0) {
                if (!grab) pressAt(px, py);
                else {
                    double dx = CX - px, dy = CY - py, l = Math.hypot(dx, dy), s = Math.min(l, 5);
                    if (l > .5) {
                        mx += dx / l * s;
                        my += dy / l * s;
                    }
                }
            } else if (stage == 1) {
                double yt = KnoxWoundArt.TURN_YT, yb = KnoxWoundArt.TURN_YB;
                if (!grab) {
                    if (curOn) pressAt(curX + tg[1] / 2 + KnoxWoundArt.TURN_SLANT * (curYe - yt) / (yb - yt), curYe);
                    else pressAt(rollX + tg[1] / 2, yt + 4);
                } else {
                    mx = tgt + curOff;
                    my = curYe + 10;
                }
            } else {
                double[] to = tailT[tailN];
                if (!grab) {
                    pressAt(tailEx, tailEy);
                    b0 = bt;
                    bpx = tailEx;
                    bpy = tailEy;
                } else {
                    double f = Math.min(1, (bt - b0) / .3);
                    mx = bpx + (to[0] - bpx) * f;
                    my = bpy + (to[1] - bpy) * f;
                    if (f >= 1) down = false;
                }
            }
        } else if (kind == UNB) {
            if (!grab) pressAt(fx, CY);
            else {
                mx = fx + 8;
                my = CY;
            }
        } else if (kind == TW) {
            for (int i = 0; i < shards.length; i++) {
                double[] sh = shards[i];
                if (sh[8] != 0) continue;
                if (grabShard < 0) {
                    double[] tp = shardTip(sh);
                    pressAt(tp[0], tp[1]);
                    b0 = bt;
                    bpx = mx;
                    bpy = my;
                } else {
                    double q = (bt - b0) * 45;
                    mx = bpx + sh[4] * q;
                    my = bpy + sh[5] * q;
                }
                break;
            }
        } else if (kind == SPLINT) {
            if (stage == 0) {
                if (!grab) {
                    pressAt(KnoxWoundArt.FRX_FX + 44, CY);
                    b0 = bt;
                    bpx = mx;
                    bpy = my;
                    bpox = ox;
                    bpoy = oy;
                } else {
                    double f = Math.min(1, (bt - b0) / .6);
                    mx = bpx - bpox * f;
                    my = bpy - bpoy * f;
                }
            } else {
                for (int i = 0; i < straps.length; i++) {
                    double[] st = straps[i];
                    if (st[2] != 0) continue;
                    if (grabStrap < 0) pressAt(st[0], CY + 52);
                    else if (straps[grabStrap][1] >= .7) down = false;
                    break;
                }
            }
        } else if (kind == UNSTITCH) {
            for (int i = 0; i < outs.length; i++) {
                if (outs[i][1] != 0) continue;
                if (grabOut < 0) {
                    double[] k0 = knot(i);
                    pressAt(k0[0], k0[1]);
                    b0 = bt;
                    bpy = my;
                } else my = bpy - (bt - b0) * 45;
                break;
            }
        } else if (kind == UNSPLINT) {
            for (double[] st : straps) {
                if (st[2] != 0) continue;
                if (grabStrap < 0) pressAt(st[0], CY + 52);
                break;
            }
        }
    }

    void pressAt(double x, double y) {
        mx = x;
        my = y;
        down = true;
        pressed = true;
    }

    /** mockup mg3.js: runBot - the bot plays until `stop` of the way (1: to the end), then holds still. */
    public void runBot(double stop) {
        int guard = 0;
        while (!done && end <= 0 && prog < stop && guard++ < 60 * 40) {
            bot();
            tick(1 / 60.0);
        }
        if (stop <= 1) frozen = true;
    }
}
