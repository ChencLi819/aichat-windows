package com.jev.probe.desktop.ui;

import javax.swing.JLabel;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deliberately small markdown → HTML renderer for the panel.
 *
 * <p>The model answers in markdown (bold, lists, code, headers) and the panel
 * shows those answers verbatim, so `**这么写**` and `` `code` `` used to reach
 * the user as literal punctuation. Desktop port of the Android `MdText`.
 *
 * <p>Rendered into a {@link JLabel} rather than a styled {@code JTextPane}. The
 * first version used a JTextPane and it was the wrong tool twice over: its
 * preferred size has to be primed with {@code setSize} before it reports a
 * wrapped height, which fights the layout that is asking for that height — and
 * the thinking pass re-renders on every streamed chunk, so the two kept waking
 * each other up. A label with an HTML body has neither problem: re-rendering is
 * just {@code setText}, and the wrap width is handed to the HTML view itself
 * (see {@link #wrapTo} — NOT stated in the markup, for the reason that method
 * documents).
 *
 * <p>Covers what chat-advice answers actually use and nothing more:
 * <ul>
 *   <li>{@code #}/{@code ##}/{@code ###} headers → bold, slightly larger
 *   <li>{@code - } / {@code * } / {@code • } bullets → 「• 」
 *   <li>{@code 1.} numbered lists → kept as-is
 *   <li>{@code **bold**} → bold
 *   <li>{@code `code`} and fenced blocks → monospace, tinted background
 *   <li>{@code >} quote lines → grey italic behind a 「▎」
 * </ul>
 * Anything unrecognized passes through untouched, so a malformed answer is still
 * readable.
 */
public final class MdText {

    private MdText() {
    }

    /** `` **bold** `` — the inner group is what gets the weight. */
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");

    /** `` `code` `` — likewise. */
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");

    /** Wrap width used before the first layout pass has sized the viewport. */
    private static final int FALLBACK_WRAP_WIDTH = 420;

    /** Client property: the wrap width a label's HTML view holds, see {@link #wrapTo}. */
    private static final String WRAP_WIDTH = "aichat.mdtext.wrapWidth";

    /** Client property: guards against installing {@link #wrapTo}'s listener twice. */
    private static final String WRAP_LISTENING = "aichat.mdtext.wrapListening";

    /**
     * Which token the body text takes. An enum rather than a {@link Color}
     * because the colour has to be resolved against the palette on every render
     * — a `Color` captured at construction would freeze the light palette into
     * an answer that is still on screen after the user switches to dark.
     */
    public enum Tone {
        /** `--text-1`: conclusions, answers. */
        BODY,
        /** `--text-2`: reasoning, evidence, hints. */
        MUTED,
        /** `--danger`: a failed round's message. */
        DANGER,
        /** `--brand`: the model's own words when they are the answer itself. */
        BRAND
    }

    /** A non-editable label holding one markdown answer. */
    public static Area area(String markdown, float size) {
        return area(markdown, size, Tone.BODY);
    }

    public static Area area(String markdown, float size, Tone tone) {
        Area a = new Area(size, tone);
        a.render(markdown);
        return a;
    }

    /**
     * States the wrap width to a label whose text is HTML, which is the only
     * place the renderer reads it from.
     *
     * Writing it into the markup as a `width:` style — `<body style='width:388px'>`
     * — is what this panel used to do, and it silently wrapped at the wrong place.
     * Swing's HTML maps the CSS units through its own table,
     * `javax.swing.text.html.CSS`: `lengthMapping.put("px", 1.3f)`, with the
     * comment "Not sure about 1.3, determined by experimentation". So
     * `width:388px` lays the document out 504 wide, not 388: inside a 388px label
     * every line runs past the right edge and the tail of each line is CLIPPED
     * instead of wrapped — and the height does not expose it either, because the
     * document is measured at the same 504, so the corrective re-wrap in
     * {@link Area} never sees a mismatch and never fires.
     *
     * The view's own width is the number the layout really uses: the root view
     * reports it back as its preferred X span, and the label paints the document
     * into exactly that span. Setting it is therefore how a wrap width is stated
     * in the units the layout uses, whatever Swing's CSS table says a pixel is.
     */
    static void wrapTo(JLabel label, int width) {
        label.putClientProperty(WRAP_WIDTH, Math.max(1, width));
        wrapNow(label);
        // The view is not permanent: Swing rebuilds it when the label first gets a
        // graphics configuration. `addNotify` fires "graphicsConfiguration", which
        // `BasicLabelUI.propertyChange` reads through `SwingUtilities2
        // .isScaleChanged` — null-to-real looks like a scale change — and answers
        // with `BasicHTML.updateRenderer`, i.e. a brand-new view sitting at the
        // document's natural width. Without this listener the stated width is lost
        // with it, and the label reports the wrong preferred height until its next
        // paint happens to fix it up.
        if (!Boolean.TRUE.equals(label.getClientProperty(WRAP_LISTENING))) {
            label.putClientProperty(WRAP_LISTENING, Boolean.TRUE);
            label.addPropertyChangeListener(BasicHTML.propertyKey, e -> wrapNow(label));
        }
    }

    /** Stamps the remembered width onto whatever view the label now holds. */
    private static void wrapNow(JLabel label) {
        Object remembered = label.getClientProperty(WRAP_WIDTH);
        View view = (View) label.getClientProperty(BasicHTML.propertyKey);
        if (remembered instanceof Integer width && view != null) {
            view.setSize(width, Short.MAX_VALUE);
        }
    }

    /**
     * Public so the panel can hold onto one and re-render it in place — the
     * streamed thinking pass updates on every chunk, and rebuilding the whole
     * panel for each of them would throw away the scroll position.
     */
    public static final class Area extends JLabel {

        private final float size;
        private final Tone tone;
        /** The markdown last rendered, so a re-wrap does not need it again. */
        private String markdown = "";
        /** The width the current HTML was wrapped for; -1 before the first render. */
        private int renderedWidth = -1;
        /** The height that wrap needs, measured when the HTML was rendered. */
        private int wrappedHeight;
        /** Guards against queueing the same corrective re-render more than once. */
        private boolean rewrapPending;
        /** Kept so the listener can be withdrawn when the label goes away. */
        private final Runnable onTheme = () -> render(markdown);
        private boolean listening;

        Area(float size, Tone tone) {
            this.size = size;
            this.tone = tone;
            setOpaque(false);
            setVerticalAlignment(TOP);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            // The wrap width is stated to the HTML view, so a window resize has to
            // re-render or the text keeps wrapping at the width it started at.
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    rewrapIfNeeded();
                }
            });
            listen(true);
        }

        /**
         * Theming is registered while the label is on screen and withdrawn when
         * it is not: the panel rebuilds its whole content on every round, and a
         * listener that outlives its label is a slow leak of dead labels.
         */
        private void listen(boolean on) {
            if (on == listening) return;
            listening = on;
            if (on) ThemeManager.addListener(onTheme);
            else ThemeManager.removeListener(onTheme);
        }

        @Override
        public void addNotify() {
            super.addNotify();
            listen(true);
        }

        @Override
        public void removeNotify() {
            listen(false);
            super.removeNotify();
        }

        /**
         * Markdown in, HTML out. Safe to call repeatedly: the streamed reasoning
         * pass calls it on every delta, and a palette change re-renders through
         * the same path so the tinted code background follows the theme.
         */
        public void render(String markdown) {
            this.markdown = markdown == null ? "" : markdown;
            int w = availableWidth();
            renderedWidth = w;
            Color fg = toneColor(Theme.tokens());
            setForeground(fg);
            // The wrap width is deliberately NOT written into the markup: the
            // HTML engine would not honour the number (see wrapTo).
            setText("<html><body>"
                + html(this.markdown, size * ThemeManager.fontScale(), fg)
                + "</body></html>");
            wrappedHeight = measureHeight(w);
            wrapTo(this, w);
        }

        /**
         * The height this document needs at [width], measured on a throwaway label.
         *
         * Not on this component, and that is the whole point. Swing's HTML view
         * lays out against the width the view was last given, not against the
         * component it is painted into, so asking the view directly reports
         * whatever it was last sized to. A detached probe runs the same
         * computation, at the width we are about to use, with nothing to
         * invalidate — measuring on THIS label would change its own size and
         * invalidate its container from inside the layout that is asking, which
         * is the repaint loop that left the window blank.
         */
        private int measureHeight(int width) {
            JLabel probe = new JLabel(getText());
            probe.setFont(getFont());
            probe.setSize(Math.max(1, width), Short.MAX_VALUE);
            wrapTo(probe, width);
            return probe.getPreferredSize().height;
        }

        /**
         * Re-wraps when the width this label actually has is no longer the width it
         * was rendered at — and NEVER inside the paint that noticed.
         *
         * A `setText` from inside `paintComponent` invalidates the component and
         * lays the container out again, which repaints, which notices the same
         * thing: the classic `setSize`/layout feedback loop that leaves the window
         * blank. Queueing the correction lets the current paint finish and turns
         * the loop into a single extra pass.
         */
        @Override
        protected void paintComponent(java.awt.Graphics g) {
            if (availableWidth() != renderedWidth) scheduleRewrap();
            super.paintComponent(g);
        }

        private void scheduleRewrap() {
            if (rewrapPending) return;
            rewrapPending = true;
            SwingUtilities.invokeLater(() -> {
                rewrapPending = false;
                rewrapIfNeeded();
            });
        }

        private Color toneColor(Tokens t) {
            return switch (tone) {
                case BODY -> t.text1();
                case MUTED -> t.text2();
                case DANGER -> t.danger();
                case BRAND -> t.brand();
            };
        }

        /**
         * Re-wraps only when the width it was rendered at is no longer the width
         * it has — the guard is what keeps this from becoming a render loop, since
         * re-wrapping changes the height and therefore fires another resize.
         */
        private void rewrapIfNeeded() {
            if (availableWidth() != renderedWidth) render(markdown);
        }

        /**
         * The width the text may use.
         *
         * When this label IS the view of a scroll pane (the capped reasoning
         * blocks) the viewport's width, because that is what is visible — a view
         * wider than its viewport is simply clipped, and the horizontal bar is
         * disabled.
         *
         * Otherwise the width the label itself has, once the layout has given it
         * one. This is the number that matters, because it is what the renderer
         * wraps at: the label paints the document into min(its own width, the
         * width stated to the view), so a width that is too LARGE wraps the text
         * at the label's width while the height is still measured from the larger
         * one. The last line then has no room and is cut off. The parent's inner
         * width is only an estimate, and it overestimates whenever this label
         * shares its parent with a sibling — the evidence rows are a
         * `BorderLayout` whose WEST cell holds the 我/对方 label, so CENTER gets
         * what is left: 358px inside a 388px parent.
         *
         * Safe as a fixed point, unlike the version this replaced: the preferred
         * size no longer depends on the width. getPreferredSize reports a width of
         * 1 and the height is measured on a detached probe, so the container's
         * layout never asks this label how wide it wants to be — a width change
         * reaches it as a resize, which re-renders once, and cannot be read back
         * as a width that justifies itself.
         */
        private int availableWidth() {
            JViewport viewport =
                    (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
            if (viewport != null && viewport.getView() == this && viewport.getWidth() > 0) {
                Insets in = viewport.getInsets();
                return Math.max(80, viewport.getWidth() - in.left - in.right);
            }
            if (getWidth() > 0) return getWidth();
            Container parent = getParent();
            if (parent != null && parent.getWidth() > 0) {
                // Minus the parent's own insets: the card's padding is part of its
                // width, and wrapping into it would push the line ends outside.
                Insets in = parent.getInsets();
                return Math.max(80, parent.getWidth() - in.left - in.right);
            }
            return FALLBACK_WRAP_WIDTH;
        }

        /**
         * The height the document needs at the column's width — and a minimal width.
         *
         * The reported WIDTH is deliberately 1. This label is stretched to whatever
         * its container gives it (see getMaximumSize), but a preferred width is what
         * the enclosing column reports as its own preferred width — and a viewport
         * whose view asks for more than the viewport has stops tracking the
         * viewport's width, which clips the entire column off at the right edge.
         */
        @Override
        public Dimension getPreferredSize() {
            int h = wrappedHeight > 0 ? wrappedHeight : super.getPreferredSize().height;
            return new Dimension(1, h);
        }

        /** The height the HTML needs at this width, and nothing more. */
        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    // ------------------------------------------------------------- rendering

    /** Markdown → the body of an HTML document. */
    private static String html(String raw, float size, Color color) {
        StringBuilder out = new StringBuilder();
        String[] lines = (raw == null ? "" : raw).replace("\r\n", "\n").split("\n", -1);
        boolean inFence = false;
        for (String line : lines) {
            String trimmed = line.trim();
            // A fence line itself is a marker, not content.
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                out.append(mono(line, size)).append("<br>");
                continue;
            }
            if (trimmed.isEmpty()) {
                out.append("<br>");
                continue;
            }
            if (trimmed.startsWith("#")) {
                int level = 0;
                while (level < trimmed.length() && level < 3 && trimmed.charAt(level) == '#') {
                    level++;
                }
                out.append("<span style='font-size:")
                    .append(Math.round(size * (1f + level * 0.1f)))
                    .append("px;font-weight:bold'>")
                    .append(inline(trimmed.substring(level).trim()))
                    .append("</span><br>");
            } else if (trimmed.startsWith(">")) {
                out.append("<span style='color:").append(mutedHex(color))
                    .append(";font-style:italic'>▎")
                    .append(inline(trimmed.replaceFirst("^>+\\s*", "")))
                    .append("</span><br>");
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")
                    || trimmed.startsWith("• ")) {
                out.append("• ").append(inline(trimmed.substring(2).trim())).append("<br>");
            } else {
                out.append(inline(line)).append("<br>");
            }
        }
        return out.toString();
    }

    /** `**bold**` and `` `code` `` within one line, markers dropped. */
    private static String inline(String text) {
        String escaped = escape(text);
        // The patterns run on the escaped text; the markers themselves survive
        // escaping (& < > are the only things escaped), so the offsets hold.
        escaped = replaceAll(escaped, BOLD, "<b>$1</b>");
        return replaceAll(escaped, CODE, "<code style='" + codeStyle() + "'>$1</code>");
    }

    private static String mono(String line, float size) {
        return "<span style='font-family:monospace;font-size:" + Math.round(size)
            + "px;" + codeStyle() + "'>" + escape(line) + "</span>";
    }

    /**
     * The code tint. A flat colour rather than a token: Swing's HTML renderer
     * does not do alpha, so a translucent token would come out opaque.
     */
    private static String codeStyle() {
        return "background-color:" + (ThemeManager.isDark() ? "#2f3237" : "#eef0f2");
    }

    /** A grey that stays readable on the active palette. */
    private static String mutedHex(Color ignored) {
        return ThemeManager.isDark() ? "#9aa0a6" : "#6b7280";
    }

    private static String replaceAll(String text, Pattern pattern, String replacement) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.replaceAll(replacement) : text;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
