package com.jev.probe.desktop.tools;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the "Jev 聊天助手" app icon as flat vector art with Java2D, and writes the
 * PNG set plus a real multi-size Windows {@code .ico}.
 *
 * <p>Why a generator instead of a checked-in SVG: the packaged app-image wants a
 * {@code .ico} and this project deliberately has no image toolchain (Java only,
 * no new dependencies), so this file is the vector source of truth. Re-run it
 * with the {@code genIcon} Gradle task after changing the geometry.
 *
 * <p>The look is built only from the app's own design tokens — every colour
 * below is a value of {@code ui/Tokens.java}, which in turn mirrors
 * {@code design/demo/assets/css/tokens.css}: a rounded square filled with the
 * brand ramp and a single bold white mark, flat vector, no shadows, no bevels,
 * no outlines, no text. The revision before this one ended its ramp in a
 * yellow-green (#86DA5E) that exists in no token, which is exactly why the icon
 * did not sit with the UI it belongs to.
 *
 * <p>The mark is a chat bubble with a tail carrying the three drafted replies,
 * the first of them punched clean through so the tile's brand green shows in it:
 * "read the message, offer three answers, one of them stands out" in one glyph.
 *
 * <p>Every size is drawn from the shapes <em>at that size</em>, never downscaled
 * from one large bitmap — downscaling is exactly what turns a 16px icon to mush.
 * Below {@link #SIMPLE_UPTO} px the tail switches to a straight-edged wedge: the
 * scooped edge is a fraction of a pixel wide there and mostly eats the tail's
 * body, which is what turned 16px into a thin streak. The draft lines shed the
 * same way — three of them need a wider canvas than two, and two more than one.
 *
 * <p>Usage: {@code java ... IconGenerator [outputDir]}, default {@code assets}
 * relative to the working directory (the Gradle task runs it from the project
 * directory, so the files land in {@code desktop/assets}).
 */
public final class IconGenerator {

    // ---------------------------------------------------------------- palette

    // Tokens only, so the icon and the panel cannot drift apart. The ramp runs
    // along the tile's diagonal: brandHover -> brand -> the dark theme's
    // brighter brand.
    private static final Color TILE_TOP_LEFT = new Color(0x00, 0xAD, 0x8A);      // LIGHT.brandHover
    private static final Color TILE_MID = new Color(0x00, 0xC2, 0x9A);           // LIGHT.brand
    private static final Color TILE_BOTTOM_RIGHT = new Color(0x2E, 0xD3, 0xAE);  // DARK.brand

    /**
     * The mark is not flat white: it carries a soft inner gradient, pure white at
     * the lower-left and faintly mint toward the upper-right. Same diagonal as
     * the tile, so it reads as one light source. The tint is brandWeak at 10%
     * over surface0 — still a token, not a colour invented for the icon.
     */
    private static final Color MARK_LIGHT = Color.WHITE;                  // LIGHT.surface0
    private static final Color MARK_TINT = new Color(0xE6, 0xF9, 0xF5);

    /**
     * The drafts that were not picked, painted onto the white bubble.
     * border2 (rgba(0,0,0,0.14)) over surface0.
     */
    private static final Color MARK_MUTED = new Color(0xDB, 0xDB, 0xDB);

    // ---------------------------------------------------------------- geometry
    //
    // All mark coordinates are normalised to the tile: (0,0) is the tile's
    // top-left corner, (1,1) its bottom-right. Keeping them in one place makes
    // the drawing re-scalable to any pixel size, which is the whole point.

    /** Chat bubble body: 0.70 x 0.57 of the tile, so it is wider than tall. */
    private static final float BUBBLE_LEFT = 0.150f;
    private static final float BUBBLE_TOP = 0.130f;
    private static final float BUBBLE_RIGHT = 0.850f;
    private static final float BUBBLE_BOTTOM = 0.700f;
    private static final float BUBBLE_CORNER = 0.150f;

    /**
     * Tail, at the bubble's lower-left. Its outer edge bows out and its inner
     * edge is scooped in — the standard chat-bubble tail.
     *
     * <p>Deliberately chunky (base 0.195 wide, 0.17 long): an earlier, thinner
     * tail rendered as a sliver and vanished at 16px.
     */
    private static final float TAIL_BASE_LEFT_X = 0.275f;
    private static final float TAIL_BASE_RIGHT_X = 0.470f;
    private static final float TAIL_OUTER_CTRL_X = 0.235f;
    private static final float TAIL_OUTER_CTRL_Y = 0.782f;
    private static final float TAIL_APEX_X = 0.215f;
    private static final float TAIL_APEX_Y = 0.870f;
    private static final float TAIL_INNER_CTRL_X = 0.335f;
    private static final float TAIL_INNER_CTRL_Y = 0.752f;

    /** Where every draft line starts. Left-aligned, the way text is. */
    private static final float BAR_LEFT = 0.300f;

    /** The picked line: the widest, and punched clean through the bubble. */
    private static final float BAR_W_PICKED = 0.400f;

    /** Three lines — the full set, as long as each is wider than a pixel. */
    private static final float BAR_H3 = 0.076f;
    private static final float BAR_GAP3 = 0.076f;
    private static final float BAR_Y3 = 0.225f;
    private static final float BAR_W3B = 0.290f;
    private static final float BAR_W3C = 0.180f;

    /** Two lines, once a third would be thinner than a pixel. */
    private static final float BAR_H2 = 0.115f;
    private static final float BAR_GAP2 = 0.110f;
    private static final float BAR_Y2 = 0.245f;
    private static final float BAR_W2B = 0.270f;

    /** One line at the smallest sizes, where the bubble is barely 8px tall. */
    private static final float BAR_H1 = 0.150f;
    private static final float BAR_Y1 = 0.340f;

    /** Corner radius of the tile, as a fraction of its side. */
    private static final float CORNER_LARGE = 0.22f;
    /** Slightly squarer when tiny, so 16px does not read as a blob. */
    private static final float CORNER_SMALL = 0.18f;

    /** At or below this size the tail uses its simplified (straight-edged) drawing. */
    private static final int SIMPLE_UPTO = 24;

    /** Above these sizes the mark carries three draft lines, then two, then one. */
    private static final int THREE_LINES_ABOVE = 32;
    private static final int TWO_LINES_ABOVE = 20;

    /** Tile inset from the canvas, as a fraction of the canvas. */
    private static final float TILE_INSET = 0.08f;

    /** Every PNG this writes. 24 is in the set for Windows' "medium" views. */
    private static final int[] PNG_SIZES = {16, 24, 32, 48, 64, 128, 256, 512};

    /**
     * The ICO entries. 256 is the largest an ICONDIRENTRY can address (its
     * width/height byte is 0 for 256); the 512 render is for the store/marketing
     * side only, which is why it is in the PNG set but not here.
     */
    private static final int[] ICO_SIZES = {16, 32, 48, 64, 128, 256};

    private IconGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path outDir = Paths.get(args.length > 0 ? args[0] : "assets");
        Files.createDirectories(outDir);

        for (int size : PNG_SIZES) {
            Path file = outDir.resolve("icon-" + size + ".png");
            ImageIO.write(render(size), "png", file.toFile());
            System.out.println("png  " + file.toAbsolutePath() + "  (" + size + "x" + size + ")");
        }

        Path ico = outDir.resolve("icon.ico");
        writeIco(ico);
        System.out.println("ico  " + ico.toAbsolutePath()
                + "  (" + ICO_SIZES.length + " entries, " + Files.size(ico) + " bytes)");
    }

    // ------------------------------------------------------------- rendering

    /**
     * Renders one size from scratch. Coordinates are computed from the target
     * size rather than scaled from a master bitmap, so every size is as sharp as
     * Java2D's antialiaser can make it.
     *
     * <p>Public because this is not only a build-time entry point: the in-app
     * mark ({@code ui/Icons.brand}) paints this very image, so the tray icon and
     * the launcher icon cannot drift apart.
     */
    public static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                    RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING,
                    RenderingHints.VALUE_COLOR_RENDER_QUALITY);

            // The inset is rounded to whole pixels so the tile's outer edge lands
            // on the pixel grid: at 16px an unsnapped edge smears across two rows
            // and the icon looks blurry before anything is even drawn.
            float inset = Math.max(1, Math.round(size * TILE_INSET));
            float tile = size - 2f * inset;
            float x0 = inset;
            float y0 = inset;

            boolean small = size <= SIMPLE_UPTO;
            float cornerFrac = small ? CORNER_SMALL : CORNER_LARGE;

            // Tile: rounded square, diagonal gradient.
            g.setPaint(tilePaint(x0, y0, tile));
            g.fill(new RoundRectangle2D.Float(x0, y0, tile, tile,
                    tile * cornerFrac * 2f, tile * cornerFrac * 2f));

            Mapper m = new Mapper(x0 + tile / 2f, y0 + tile / 2f, tile);

            // A real boolean subtract (rather than the backdrop painted back in)
            // so the picked line is a genuine hole: the PNG stays transparent
            // there, and the tile's brand green is what shows through it.
            Area mark = new Area(bubble(m));
            mark.add(new Area(tail(m, small)));
            mark.subtract(new Area(line(m, 0, size)));
            g.setPaint(markPaint(x0, y0, tile));
            g.fill(mark);

            // The drafts that were not picked sit on top of the white bubble.
            int lines = lineCount(size);
            if (lines > 1) {
                g.setColor(MARK_MUTED);
                for (int i = 1; i < lines; i++) {
                    g.fill(line(m, i, size));
                }
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    /** Maps normalised tile coordinates to canvas pixels. */
    private static final class Mapper {
        private final float cx;
        private final float cy;
        /** Length of one normalised unit, in pixels. */
        private final float unit;

        Mapper(float cx, float cy, float unit) {
            this.cx = cx;
            this.cy = cy;
            this.unit = unit;
        }

        float x(float n) {
            return cx + (n - 0.5f) * unit;
        }

        float y(float n) {
            return cy + (n - 0.5f) * unit;
        }

        /** A length (radius, corner, width) in pixels. */
        float d(float n) {
            return n * unit;
        }
    }

    private static Paint tilePaint(float x0, float y0, float tile) {
        return new LinearGradientPaint(
                new Point2D.Float(x0, y0),
                new Point2D.Float(x0 + tile, y0 + tile),
                new float[]{0f, 0.5f, 1f},
                new Color[]{TILE_TOP_LEFT, TILE_MID, TILE_BOTTOM_RIGHT});
    }

    private static Paint markPaint(float x0, float y0, float tile) {
        // Runs against the tile's diagonal so the white shape darkens toward the
        // same corner the tile does.
        return new LinearGradientPaint(
                new Point2D.Float(x0, y0 + tile),
                new Point2D.Float(x0 + tile, y0),
                new float[]{0f, 1f},
                new Color[]{MARK_LIGHT, MARK_TINT});
    }

    private static java.awt.Shape bubble(Mapper m) {
        return new RoundRectangle2D.Float(
                m.x(BUBBLE_LEFT), m.y(BUBBLE_TOP),
                m.d(BUBBLE_RIGHT - BUBBLE_LEFT), m.d(BUBBLE_BOTTOM - BUBBLE_TOP),
                m.d(BUBBLE_CORNER) * 2f, m.d(BUBBLE_CORNER) * 2f);
    }

    private static java.awt.Shape tail(Mapper m, boolean simple) {
        Path2D.Float p = new Path2D.Float();
        // Both base points sit on the bubble's straight bottom edge (the corner
        // arc ends well before x=0.275), so the union has no ledge or notch at
        // the joint.
        p.moveTo(m.x(TAIL_BASE_LEFT_X), m.y(BUBBLE_BOTTOM));
        if (simple) {
            // Plain straight-edged wedge at the tiny sizes. The scooped inner
            // edge is only a fraction of a pixel wide there and mostly eats the
            // tail's body, which is what turned 16px into a thin streak.
            p.lineTo(m.x(TAIL_APEX_X), m.y(TAIL_APEX_Y));
            p.lineTo(m.x(TAIL_BASE_RIGHT_X), m.y(BUBBLE_BOTTOM));
        } else {
            p.quadTo(m.x(TAIL_OUTER_CTRL_X), m.y(TAIL_OUTER_CTRL_Y),
                    m.x(TAIL_APEX_X), m.y(TAIL_APEX_Y));
            p.quadTo(m.x(TAIL_INNER_CTRL_X), m.y(TAIL_INNER_CTRL_Y),
                    m.x(TAIL_BASE_RIGHT_X), m.y(BUBBLE_BOTTOM));
        }
        p.closePath();
        return p;
    }

    /** How many draft lines survive at [size]. */
    private static int lineCount(int size) {
        return size > THREE_LINES_ABOVE ? 3 : size > TWO_LINES_ABOVE ? 2 : 1;
    }

    /**
     * One draft line, as a rounded bar. Its height, spacing and length follow
     * the line count, and every tier is centred in the bubble rather than
     * anchored to its top, so the mark does not drift off-centre as it sheds
     * lines. The lines get shorter as they go down, so the set reads as a draft
     * rather than as a block.
     */
    private static java.awt.Shape line(Mapper m, int index, int size) {
        int lines = lineCount(size);
        float h;
        float y0;
        float gap = 0f;
        float w;
        if (lines == 1) {
            h = BAR_H1;
            y0 = BAR_Y1;
            w = BAR_W_PICKED;
        } else if (lines == 2) {
            h = BAR_H2;
            gap = BAR_GAP2;
            y0 = BAR_Y2;
            w = index == 0 ? BAR_W_PICKED : BAR_W2B;
        } else {
            h = BAR_H3;
            gap = BAR_GAP3;
            y0 = BAR_Y3;
            w = index == 0 ? BAR_W_PICKED : index == 1 ? BAR_W3B : BAR_W3C;
        }
        return new RoundRectangle2D.Float(m.x(BAR_LEFT),
                m.y(y0 + index * (h + gap)), m.d(w), m.d(h), m.d(h), m.d(h));
    }

    // ------------------------------------------------------------------- ico

    /**
     * Writes a real multi-size Windows ICO: ICONDIR, one 16-byte ICONDIRENTRY per
     * size, then the image payloads back to back. The payloads are PNGs, which
     * Vista and later accept (and which is what modern icon tools emit) — that
     * also means no BMP/AND-mask bookkeeping.
     */
    private static void writeIco(Path file) throws IOException {
        List<byte[]> payloads = new ArrayList<>(ICO_SIZES.length);
        for (int size : ICO_SIZES) {
            payloads.add(pngBytes(render(size)));
        }

        try (OutputStream raw = Files.newOutputStream(file);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw))) {
            le16(out, 0);                     // reserved
            le16(out, 1);                     // type: 1 = icon
            le16(out, ICO_SIZES.length);      // image count

            int offset = 6 + 16 * ICO_SIZES.length;
            for (int i = 0; i < ICO_SIZES.length; i++) {
                int size = ICO_SIZES[i];
                int dim = size >= 256 ? 0 : size;   // 0 encodes 256
                out.writeByte(dim);                 // width
                out.writeByte(dim);                 // height
                out.writeByte(0);                   // palette colours (0 = truecolour)
                out.writeByte(0);                   // reserved
                le16(out, 1);                       // colour planes
                le16(out, 32);                      // bits per pixel
                le32(out, payloads.get(i).length);  // bytes of image data
                le32(out, offset);                  // offset of image data
                offset += payloads.get(i).length;
            }
            for (byte[] payload : payloads) {
                out.write(payload);
            }
        }
    }

    private static byte[] pngBytes(BufferedImage img) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
        ImageIO.write(img, "png", buf);
        return buf.toByteArray();
    }

    /** DataOutputStream is big-endian; the ICO container is little-endian. */
    private static void le16(DataOutputStream out, int v) throws IOException {
        out.writeByte(v & 0xFF);
        out.writeByte((v >>> 8) & 0xFF);
    }

    private static void le32(DataOutputStream out, int v) throws IOException {
        out.writeByte(v & 0xFF);
        out.writeByte((v >>> 8) & 0xFF);
        out.writeByte((v >>> 16) & 0xFF);
        out.writeByte((v >>> 24) & 0xFF);
    }
}
