import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import knox.java.KnoxWoundArt;
import knox.java.KnoxWoundGame;

/**
 * The health tab's wound pictures without the game (tools/health/shots.ps1): the game's own
 * drawing (KnoxWoundArt, KnoxWoundGame - the classes of the mod's jar) for the same states as
 * the mockup's shots, each beside the mockup's picture cut from its shot - the sheet
 * "mockup | game" (the author, 2026-11-10: the port is checked this way without the game, and the
 * author's later changes to the mockup are carried over the same way). The words are set here in
 * a plain condensed font (in the game: Knox's own, through the layout kit).
 * Usage: WoundShots <mockup shots dir> <out dir> <RU IG_UI.json>
 */
public class WoundShots {

    static final int W = 980, H = 346;           // the table's picture inside its frame (#pic 982 x 348)
    static final int PIC_X = 901, PIC_Y = 143;   // where it is in the mockup's 1920 x 1080 shot
    static final Map<String, String> WORDS = new HashMap<>();

    /** One state: its name, the mockup's query, the scene, the game (if one is played). */
    static final class State {
        String name, query, title;
        KnoxWoundArt.Scene scene = new KnoxWoundArt.Scene();
        int game = -1, woundKind;
        String item = "";
        boolean cleaned;
        double bot;

        State(String name, String query, String title) {
            this.name = name;
            this.query = query;
            this.title = title;
        }
    }

    static final int BLEED = 0xe0483c, INFECT = 0xb9c24a, FRACTURE = 0x8ec5e0, GLASS = 0xb03a4a, SAND = 0xd4c38a;

    static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    static ArrayList<State> states() {
        ArrayList<State> out = new ArrayList<>();
        State s;
        // the mockup's PARTS: 3 a deep wound (bleeding, dirt), 7 a cut (dirty bandage, infected),
        // 13 a fracture, 0 glass, 6 a scratch healing under a bandage
        s = new State("t3", "sel=3", "deep wound: bleeding, dirt, not cleaned");
        s.scene.kind = KnoxWoundArt.KIND_DEEP; s.scene.bleed = true; s.scene.dirt = true; s.scene.lookRgb = BLEED;
        out.add(s);
        s = new State("t3_stitched", "sel=3&treat=2", "deep wound: cleaned and stitched, still oozing");
        s.scene.kind = KnoxWoundArt.KIND_DEEP; s.scene.bleed = true; s.scene.dirt = true; s.scene.cleaned = true; s.scene.stitched = true; s.scene.lookRgb = BLEED;
        out.add(s);
        s = new State("t3_bandaged", "sel=3&treat=3", "deep wound: bandaged");
        s.scene.kind = KnoxWoundArt.KIND_DEEP; s.scene.wetBand = true; s.scene.dirt = true; s.scene.cleaned = true; s.scene.stitched = true; s.scene.band = true; s.scene.lookRgb = SAND;
        out.add(s);
        s = new State("t7", "sel=7", "cut: dirty bandage, infected");
        s.scene.kind = KnoxWoundArt.KIND_LACER; s.scene.dirtyBand = true; s.scene.band = true; s.scene.infected = true; s.scene.lookRgb = INFECT;
        out.add(s);
        s = new State("t7_open", "sel=7&treat=1", "cut: bandage off, infected");
        s.scene.kind = KnoxWoundArt.KIND_LACER; s.scene.infected = true; s.scene.lookRgb = INFECT;
        out.add(s);
        s = new State("t13", "sel=13", "fracture");
        s.scene.kind = KnoxWoundArt.KIND_FRACTURE; s.scene.lookRgb = FRACTURE;
        out.add(s);
        s = new State("t13_splint", "sel=13&treat=1", "fracture in a splint");
        s.scene.kind = KnoxWoundArt.KIND_FRACTURE; s.scene.splinted = true; s.scene.lookRgb = FRACTURE;
        out.add(s);
        s = new State("t0", "sel=0", "glass");
        s.scene.kind = KnoxWoundArt.KIND_GLASS; s.scene.lookRgb = GLASS;
        out.add(s);
        s = new State("t6", "sel=6", "scratch healing under a bandage");
        s.scene.kind = KnoxWoundArt.KIND_SCRATCH; s.scene.cleaned = true; s.scene.band = true; s.scene.progress = .62f; s.scene.lookRgb = mix(SAND, 0x8fd0b0, .62);
        out.add(s);
        // the games, the bot playing to a share of the way (the mockup's ?bot=)
        s = new State("g_clean", "sel=3&play=1&bot=0.5", "game: clean (half)");
        s.scene.kind = KnoxWoundArt.KIND_DEEP; s.scene.bleed = true; s.scene.dirt = true; s.scene.lookRgb = BLEED;
        s.game = KnoxWoundGame.CLEAN; s.woundKind = KnoxWoundArt.KIND_DEEP; s.item = "wipes"; s.bot = .5;
        out.add(s);
        s = new State("g_stitch", "sel=3&play=1&done=clean&bot=0.4", "game: stitch");
        s.scene.kind = KnoxWoundArt.KIND_DEEP; s.scene.bleed = true; s.scene.dirt = true; s.scene.cleaned = true; s.scene.lookRgb = BLEED;
        s.game = KnoxWoundGame.STITCH; s.woundKind = KnoxWoundArt.KIND_DEEP; s.item = "suture"; s.cleaned = true; s.bot = .4;
        out.add(s);
        for (double b : new double[]{.12, .5, .9}) {
            s = new State("g_band" + (int) Math.round(b * 100), "sel=3&play=1&done=clean,stitch&bot=" + b, "game: bandage to " + b);
            s.scene.kind = KnoxWoundArt.KIND_DEEP; s.scene.bleed = true; s.scene.dirt = true; s.scene.cleaned = true; s.scene.stitched = true; s.scene.lookRgb = BLEED;
            s.game = KnoxWoundGame.BANDAGE; s.woundKind = KnoxWoundArt.KIND_DEEP; s.item = "bandage"; s.cleaned = true; s.bot = b;
            out.add(s);
        }
        s = new State("g_unb", "sel=7&play=1&bot=0.5", "game: bandage off");
        s.scene.kind = KnoxWoundArt.KIND_LACER; s.scene.dirtyBand = true; s.scene.band = true; s.scene.infected = true; s.scene.lookRgb = INFECT;
        s.game = KnoxWoundGame.UNB; s.woundKind = KnoxWoundArt.KIND_LACER; s.bot = .5;
        out.add(s);
        s = new State("g_tw", "sel=0&play=1&bot=0.5", "game: glass out");
        s.scene.kind = KnoxWoundArt.KIND_GLASS; s.scene.lookRgb = GLASS;
        s.game = KnoxWoundGame.TW; s.woundKind = KnoxWoundArt.KIND_GLASS; s.item = "tweezers"; s.bot = .5;
        out.add(s);
        for (double b : new double[]{.2, .7}) {
            s = new State("g_splint" + (int) Math.round(b * 100), "sel=13&play=1&bot=" + b, "game: splint to " + b);
            s.scene.kind = KnoxWoundArt.KIND_FRACTURE; s.scene.lookRgb = FRACTURE;
            s.game = KnoxWoundGame.SPLINT; s.woundKind = KnoxWoundArt.KIND_FRACTURE; s.item = "splint"; s.bot = b;
            out.add(s);
        }
        // the undoing games (the author 2026-11-11: a done step clicked is taken back) - no mockup yet
        s = new State("g_unband", "sel=3&play=1&bot=0.5", "game: a clean bandage off");
        s.scene.kind = KnoxWoundArt.KIND_DEEP; s.scene.cleaned = true; s.scene.stitched = true; s.scene.band = true; s.scene.lookRgb = SAND;
        s.game = KnoxWoundGame.UNB; s.woundKind = KnoxWoundArt.KIND_DEEP; s.item = "band"; s.cleaned = true; s.bot = .5;
        out.add(s);
        for (double b : new double[]{.4, 1.01}) {
            s = new State("g_unstitch" + (int) Math.round(b * 100), "sel=3&play=1&bot=" + b, "game: stitches out to " + b);
            s.scene.kind = KnoxWoundArt.KIND_DEEP; s.scene.cleaned = true; s.scene.stitched = true; s.scene.lookRgb = BLEED;
            s.game = KnoxWoundGame.UNSTITCH; s.woundKind = KnoxWoundArt.KIND_DEEP; s.cleaned = true; s.bot = b;
            out.add(s);
        }
        for (double b : new double[]{.5, 1.01}) {
            s = new State("g_unsplint" + (int) Math.round(b * 100), "sel=13&play=1&bot=" + b, "game: splint off to " + b);
            s.scene.kind = KnoxWoundArt.KIND_FRACTURE; s.scene.splinted = true; s.scene.lookRgb = FRACTURE;
            s.game = KnoxWoundGame.UNSPLINT; s.woundKind = KnoxWoundArt.KIND_FRACTURE; s.bot = b;
            out.add(s);
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        File shots = new File(args[0]), outDir = new File(args[1]);
        outDir.mkdirs();
        readWords(new File(args[2]));
        KnoxWoundGame.WORDS = (key, a) -> {
            String s = WORDS.getOrDefault(key, key);
            for (int i = 0; i < a.length; i++) s = s.replace("%" + (i + 1), a[i]);
            return s;
        };
        if (args.length > 3) {
            // only the list: the names and the mockup's queries (shots.ps1 takes the mockup's shots by it)
            StringBuilder sb = new StringBuilder();
            for (State s : states()) sb.append(s.name).append('\t').append(s.query).append('\n');
            Files.write(new File(args[3]).toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }
        ArrayList<State> list = states();
        int labelH = 30, gap = 12;
        BufferedImage sheet = new BufferedImage(W * 2 + gap * 3, list.size() * (H + labelH + gap) + gap, BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = sheet.createGraphics();
        sg.setColor(new Color(0x050606));
        sg.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        sg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int y = gap;
        for (State st : list) {
            BufferedImage game = render(st);
            ImageIO.write(game, "png", new File(outDir, st.name + "_game.png"));
            File mf = new File(shots, "hs_" + st.name + ".png");
            BufferedImage mock = null;
            if (mf.exists()) {
                BufferedImage full = ImageIO.read(mf);
                if (full.getWidth() >= PIC_X + W && full.getHeight() >= PIC_Y + H) mock = full.getSubimage(PIC_X, PIC_Y, W, H);
            }
            sg.setColor(new Color(0xd9d6c8));
            sg.setFont(new Font("SansSerif", Font.BOLD, 16));
            sg.drawString(st.name + "  -  " + st.title + "   (mockup | game)", gap, y + 20);
            if (mock != null) sg.drawImage(mock, gap, y + labelH, null);
            else {
                sg.setColor(new Color(0x8f9289));
                sg.drawString("no mockup shot (" + mf.getName() + ")", gap + 20, y + labelH + 40);
            }
            sg.drawImage(game, gap * 2 + W, y + labelH, null);
            if (mock != null) {
                BufferedImage pair = new BufferedImage(W * 2 + gap, H, BufferedImage.TYPE_INT_RGB);
                Graphics2D pg = pair.createGraphics();
                pg.drawImage(mock, 0, 0, null);
                pg.drawImage(game, W + gap, 0, null);
                pg.dispose();
                ImageIO.write(pair, "png", new File(outDir, st.name + "_pair.png"));
            }
            y += H + labelH + gap;
        }
        sg.dispose();
        ImageIO.write(sheet, "png", new File(outDir, "sheet_mockup_game.png"));
        System.out.println("written " + list.size() + " states to " + outDir);
    }

    static BufferedImage render(State st) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x0b0c0c));
        g.fillRect(0, 0, W, H);
        double s = Math.max(W / 480.0, H / 170.0), tx = (W - 480 * s) / 2, ty = (H - 170 * s) / 2;
        java.awt.geom.AffineTransform base = g.getTransform();
        g.translate(tx, ty);
        g.scale(s, s);
        KnoxWoundArt art = new KnoxWoundArt();
        // (the beat at its swell's top, as the mockup's still often catches it; time 1 s)
        art.begin(g, s, 1.0, .30);
        KnoxWoundGame game = null;
        if (st.game >= 0) {
            game = new KnoxWoundGame();
            game.start(st.game, st.woundKind, st.cleaned, st.item, 2, 0);
            game.vanAuto = true;
            game.shake = .9 * 2 + .5 * 1;   // the mockup's pain (level 2) and stress (level 1)
            game.runBot(st.bot);
            st.scene.hide |= KnoxWoundGame.hide(st.game);
            st.scene.noNotes = true;
        }
        art.section(st.scene);
        if (game != null) game.draw(art);
        g.setTransform(base);
        // the words, at their places
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        for (int i = 0; i < art.count(); i++) {
            KnoxWoundArt.Text t = art.texts.get(i);
            String str = t.raw ? t.key : WORDS.getOrDefault(t.key, t.key);
            float px = (float) (t.x * s + tx), py = (float) (t.y * s + ty), size = (float) (t.size * s);
            Map<TextAttribute, Object> at = new HashMap<>();
            at.put(TextAttribute.FAMILY, FONT);
            at.put(TextAttribute.SIZE, size);
            at.put(TextAttribute.WEIGHT, t.weight >= 700 ? TextAttribute.WEIGHT_BOLD : t.weight >= 600 ? TextAttribute.WEIGHT_DEMIBOLD : TextAttribute.WEIGHT_REGULAR);
            at.put(TextAttribute.TRACKING, t.spacing / Math.max(1f, t.size));
            Font f = Font.getFont(at);
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            float w = fm.stringWidth(str);
            float x = t.anchor < 0 ? px - w : t.anchor == 0 ? px - w / 2 : px;
            g.setColor(new Color((t.rgb >> 16) & 255, (t.rgb >> 8) & 255, t.rgb & 255, Math.round(255 * Math.max(0, Math.min(1, t.a)))));
            g.drawString(str, x, py);
        }
        g.dispose();
        BufferedImage rgb = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D rg = rgb.createGraphics();
        rg.drawImage(img, 0, 0, null);
        rg.dispose();
        return rgb;
    }

    static final String FONT = pickFont();

    static String pickFont() {
        String[] want = {"Fira Sans Extra Condensed", "Fira Sans Condensed", "Arial Narrow", "Bahnschrift SemiCondensed", "SansSerif"};
        java.util.Set<String> have = new java.util.HashSet<>(java.util.Arrays.asList(java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String f : want) if (have.contains(f)) return f;
        return "SansSerif";
    }

    static void readWords(File json) throws Exception {
        String s = new String(Files.readAllBytes(json.toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"IGUI_KnoxHP_([A-Za-z0-9_]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(s);
        while (m.find()) WORDS.put(m.group(1), m.group(2).replace("\\\"", "\""));
    }
}
