package com.jev.probe.desktop.ui;

import com.jev.probe.desktop.kb.Relations;
import com.jev.probe.desktop.model.Analysis;
import com.jev.probe.desktop.model.Msg;
import com.jev.probe.desktop.model.RankedReply;
import com.jev.probe.desktop.ui.kit.Area;
import com.jev.probe.desktop.ui.kit.Bar;
import com.jev.probe.desktop.ui.kit.Btn;
import com.jev.probe.desktop.ui.kit.Card;
import com.jev.probe.desktop.ui.kit.Chip;
import com.jev.probe.desktop.ui.kit.Collapse;
import com.jev.probe.desktop.ui.kit.EmptyState;
import com.jev.probe.desktop.ui.kit.Glyphs;
import com.jev.probe.desktop.ui.kit.Kit;
import com.jev.probe.desktop.ui.kit.Menu;
import com.jev.probe.desktop.ui.kit.Pill;
import com.jev.probe.desktop.ui.kit.RelPill;
import com.jev.probe.desktop.ui.kit.RelationMenu;
import com.jev.probe.desktop.ui.kit.Segmented;
import com.jev.probe.desktop.ui.kit.Surface;
import com.jev.probe.desktop.ui.kit.Toast;
import com.jev.probe.desktop.ui.kit.Wrap;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The floating panel — the desktop port of the Android overlay, laid out to the
 * v1.1 design (FR-PANEL-01…18).
 *
 * <p>The structure is `design/demo/index.html`'s `<section class="panel">`, one
 * region per region, and the numbers are `screens.css`:
 *
 * <pre>
 *   panel              rounded 16, hairline, no outer padding — each region pads itself
 *    ├ panel__head     12/12/8, gap 8, bottom hairline   mark 24 · title+capsule · chip · wins
 *    ├ panel__tabs     8/12/0                            the segment, full width
 *    ├ panel__body     flex:1                            two panes stacked, one visible
 *    │   ├ pane__scroll  8/12/12                         round cards, gap 8
 *    │   └ ask-list 8/12 + ask-input 8/12                bubble history, input pinned below
 *    └ panel__foot     8/12, gap 6, top hairline, surface-1   primary · secondary · ⋯
 * </pre>
 *
 * <p>Three things are worth knowing before editing:
 *
 * <ul>
 *   <li><b>The root has no padding.</b> Padding on the root is what the old
 *       layout used, and it is why the header and the footer could never reach
 *       the panel's edges the way the design has them. Each region carries its
 *       own padding instead; the footer rounds its own bottom corners, because
 *       Swing — unlike the CSS `overflow: hidden` — does not clip a child to its
 *       parent's radius.
 *   <li><b>Both panes are built from state, not from events.</b> {@link #render}
 *       and {@link #renderAsk} are the only two places that touch the lists, and
 *       every disclosure's open/closed flag lives on the model ({@code Round},
 *       {@code Ask}) so a re-render cannot shut a block the user opened.
 *   <li><b>The thinking pass is never re-laid-out while it streams.</b>
 *       {@link #onThinkingDelta} writes into one {@link MdText.Area}
 *       ({@code liveThink}) in place (FR-PANEL-07); rebuilding the list once per
 *       chunk would drop the scroll position once per chunk.
 * </ul>
 *
 * <p>Every colour is read from {@link Theme#tokens()} while painting. The few
 * things that cannot be — the header's labels and the atoms built inside the two
 * render methods, which hold a `Color` field — are rebuilt by the single
 * {@link ThemeManager} listener installed here, so a palette switch repaints the
 * whole panel in one pass (NFR-04).
 *
 * <p>Every public method must be called on the event dispatch thread; the
 * pipeline marshals its background work through {@link SwingUtilities#invokeLater}.
 */
public final class AssistantPanel {

    private static final int DEFAULT_WIDTH = 460;
    private static final int DEFAULT_HEIGHT = 720;
    /** FR-PANEL-16: the resizer clamps to this, the footer folds below it. */
    private static final int MIN_WIDTH = 360;
    private static final int MIN_HEIGHT = 470;
    /** FR-PANEL-11: below this width the secondary button folds into the menu. */
    private static final int NARROW_WIDTH = 380;

    private static final int MAX_ROUNDS = 20;
    private static final int MAX_ASKS = 20;
    /** Live thinking is a tail, not the whole transcript, while it streams. */
    private static final int LIVE_THINK_CHARS = 600;
    /** `.think{max-height:240}` on a round, `160` on the analysing card. */
    private static final int THINK_MAX = 240;
    private static final int THINK_LIVE_MAX = 160;
    /** Lines the 「问 AI」 box shows before it starts scrolling instead. */
    private static final int ASK_MAX_LINES = 4;
    /** `.qa__q{max-width:84%}` / `.qa__a{max-width:92%}`. */
    private static final double QUESTION_RATIO = 0.84;
    private static final double ANSWER_RATIO = 0.92;
    /** Used to size a bubble before its row has ever been laid out. */
    private static final int FALLBACK_ROW_WIDTH = 300;

    private static final SimpleDateFormat STAMP = new SimpleDateFormat("HH:mm:ss");

    /**
     * §8.1: the model's enum values in the user's words. A key that is not here
     * must never reach the screen — an English token in the conclusion is the
     * single loudest way to break the illusion that this is advice, not a dump.
     */
    private static final String UNKNOWN = "（未识别）";

    private static final Map<String, String> INTENT = Map.of(
        "confirm_you_care", "在确认你还在不在乎", "vent_anger", "在发泄情绪",
        "request_action", "想要你做点什么", "seek_explanation", "想要一个解释",
        "casual_chat", "只是随便聊聊", "close_topic", "这事儿已经过去了");

    private static final Map<String, String> NEEDS = Map.of(
        "apology", "道歉", "action", "具体行动", "explanation", "解释",
        "care", "你的在乎", "nothing", "（不用做什么）");

    private static final Map<String, String> ACTION = Map.of(
        "check_history", "翻聊天记录", "apologize", "先道歉", "give_commitment", "给承诺",
        "explain", "解释清楚", "acknowledge", "接住情绪", "say_less", "少说两句",
        "make_plan", "定个安排");

    /** One finished analysis round, kept so the panel can be scrolled back. */
    private static final class Round {

        final long at;
        Analysis analysis;
        boolean thinkOpen;
        boolean evidenceOpen;
        /** 1-based rank being edited before copying; 0 when nothing is edited. */
        int editing;
        /** The half-typed edit, kept so a re-render does not throw the typing away. */
        String editDraft = "";

        Round(long at, Analysis analysis) {
            this.at = at;
            this.analysis = analysis;
        }
    }

    /** One 「问 AI」 exchange, kept so the pane reads as a conversation (FR-PANEL-14). */
    private static final class Ask {

        final String question;
        String answer;
        String reasoning;
        String error;
        boolean thinkOpen;

        Ask(String question) {
            this.question = question;
        }

        boolean pending() {
            return answer == null && error == null;
        }
    }

    // ------------------------------------------------------------------ frame

    private final JFrame frame;
    /**
     * Edge and corner resizing. Kept as a field because it is also the source of
     * the resize hint (FR-PANEL-16): the root surface asks it which zone is hot
     * while painting.
     */
    private WindowResizer resizer;
    /** False when the platform refused per-pixel translucency; then the surface
     *  is fully opaque and the opacity setting has nothing to act on. */
    private final boolean translucent;
    private final Surface root;
    private int opacityPercent = 96;

    private final Ellipsis title = new Ellipsis(Font.BOLD, Tokens.FS_TITLE, false);
    private final Ellipsis subtitle = new Ellipsis(Font.PLAIN, Tokens.FS_CAPTION, true);
    private final RelPill relPill = new RelPill();
    private final Chip chip = new Chip(Chip.Kind.LISTENING, "监听中");
    private final Segmented tabs = new Segmented("AI 分析", "问 AI");
    private final JPanel panes = new JPanel(new CardLayout());
    private final JPanel roundsBox = Theme.column();
    private final JPanel askBox = Theme.column();
    private final JTextArea askInput = new Area(1, 12)
        .placeholder("就当前对话问点什么…（Enter 发送，Shift+Enter 换行）");
    /** Line count the ask box's height was last laid out for (see [syncAskBox]). */
    private int askBoxLines = 1;
    private final Btn askSend = new Btn("发送", Btn.Variant.PRIMARY);
    private final Btn analyzeBtn = new Btn("分析当前对话", Btn.Variant.PRIMARY).block();
    private final Btn pauseBtn = new Btn("暂停", Btn.Variant.SECONDARY);
    private final Btn moreBtn = new Btn(Glyphs.more(), Btn.Variant.ICON);
    private final JScrollPane roundsScroll;
    private final JScrollPane askScroll;
    private final Timer thinkTimer;
    /** The header; the window's drag handle and the resizer's owner. */
    private JComponent header;

    // ------------------------------------------------------------------ state

    private final List<Round> rounds = new ArrayList<>();
    private Round current;
    private boolean loading;
    private String lastError;
    private String notice;
    private boolean awaitingReplies;
    private List<RankedReply> pendingReplies;
    private String replyError;
    private String convKey = "";
    private String captureInfo;
    private boolean userPaused;
    private boolean sessionActive;
    private int ctxNotes;
    private int ctxHistory;
    private boolean evidenceVisible = true;
    /** What the vision reader saw this round, shown in 「本轮依据」. */
    private List<Msg> nextEvidence = List.of();
    private Relations.Resolved relation =
        new Relations.Resolved("", Relations.Source.FALLBACK_UNKNOWN, null);
    private boolean hasContact;

    private final List<Ask> asks = new ArrayList<>();
    private boolean askBusy;
    /** Mirrors what the send button currently says, so it is relabelled once. */
    private boolean sendShowsBusy;

    /** Which pane the streamed reasoning belongs to — one delta stream per turn. */
    private boolean thinkForAsk;
    private final StringBuilder think = new StringBuilder();
    private final StringBuilder askThink = new StringBuilder();
    private long thinkSince;
    /** Re-rendered in place while a thinking pass streams in (FR-PANEL-07). */
    private MdText.Area liveThink;
    /** The analysing / pending card's own chip, so its timer ticks without a rebuild. */
    private Chip liveChip;

    // -------------------------------------------------------------- callbacks

    private Runnable onManualAnalyze = () -> { };
    private Runnable onReanalyze = () -> { };
    private Runnable onChooseRegion = () -> { };
    private Runnable onTogglePause = () -> { };
    private Runnable onToggleSession = () -> { };
    private Runnable onSaveContact = () -> { };
    private Runnable onOpenSettings = () -> { };
    private Runnable onOpenKnowledge = () -> { };
    private Runnable onMinimize = () -> { };
    private Runnable onClose = () -> { };
    private Consumer<String> onAskSubmitted = q -> { };
    private Consumer<String> onRelationApply = v -> { };
    private Runnable onRelationClear = () -> { };
    private Consumer<String> onSaveContactWithRelation = v -> { };
    private IntConsumer onRegenerateReply = r -> { };

    public AssistantPanel() {
        frame = new JFrame("AIChat");
        frame.setUndecorated(true);
        frame.setAlwaysOnTop(true);
        // The window background must be fully clear: the root paints its own
        // rounded surface, and a square opaque background behind it would show
        // at the corners (the classic Swing rounding mistake).
        boolean ok = true;
        try {
            frame.setBackground(new Color(0, 0, 0, 0));
        } catch (Exception e) {
            ok = false;
        }
        translucent = ok;

        // `.pane__scroll{padding:8px 12px 12px}` and `.ask-list{padding:8px 12px}`.
        //
        // The padding sits on the column rather than on the scroll pane: padded
        // scroll panes push the 8px scrollbar 12px off the pane's edge, and the
        // design has it flush against it (an overlay bar).
        roundsBox.setBorder(BorderFactory.createEmptyBorder(
            Tokens.SP3, Tokens.SP4, Tokens.SP4, Tokens.SP4));
        askBox.setBorder(BorderFactory.createEmptyBorder(
            Tokens.SP3, Tokens.SP4, Tokens.SP3, Tokens.SP4));

        // Every final field the builders read must exist before they run.
        roundsScroll = scroll(roundsBox);
        askScroll = scroll(askBox);
        // A question bubble is a plain label wrapped at a width baked in when it
        // was built (a label cannot re-wrap itself the way MdText does), so a
        // change of width has to rebuild the pane. The guard is the viewport's own
        // width, so this fires on a real resize and never on a re-render.
        int[] lastAskWidth = {0};
        askScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int w = askScroll.getViewport().getWidth();
                if (w == lastAskWidth[0]) return;
                lastAskWidth[0] = w;
                // Deferred: this fires from inside the layout pass that resized
                // the viewport, and rebuilding the list from there re-enters it.
                // It is also more accurate — the new width is only readable once
                // that pass has finished.
                SwingUtilities.invokeLater(AssistantPanel.this::renderAsk);
            }
        });

        root = new Surface(panelSkin(), new BorderLayout());
        root.setWindowSurface(true);

        header = buildHeader();
        root.add(header, BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));
        frame.setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        placeTopRight();

        // FR-PANEL-11: the secondary button is the first thing to go when there
        // is no room for it; it reappears the moment there is.
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                applyNarrowLayout();
            }
        });

        thinkTimer = new Timer(1000, e -> tick());

        // Installed last: the controller listens on the event queue, so it must
        // not be live while the tree is still being assembled.
        resizer = new WindowResizer(frame, header, MIN_WIDTH, MIN_HEIGHT);
        // The root surface paints the hint but knows nothing about the
        // controller, so it has to be told when the zone changes.
        resizer.onHoverChange(() -> root.repaint());
        resizer.install();

        // One listener for the whole panel. The kit's widgets read the palette
        // while painting and need nothing, but the header's labels and the two
        // panes hold colours and fonts, so a switch re-applies them (FR-SET-08:
        // no restart, one frame, panel and dialogs together).
        ThemeManager.addListener(() -> {
            styleHeader();
            render();
            renderAsk();
        });

        applyNarrowLayout();
        syncHeader();
        render();
        renderAsk();
    }

    /** The window's own surface: the thing the opacity slider repaints. */
    private Surface.Skin panelSkin() {
        return (g, w, h, t) -> {
            Kit.roundRect(g, 0, 0, w, h, Tokens.R_XL,
                translucent ? Theme.alpha(t.surface0(), opacityAlpha()) : t.surface0(),
                t.border1());
            // FR-PANEL-16: light up the edge or corner the pointer can grab. The
            // band is exactly GRAB wide, so what lights up is what is draggable.
            if (resizer != null) {
                int z = resizer.hotZone();
                if (z != 0) {
                    Kit.resizeHint(g, w, h, Tokens.R_XL, WindowResizer.GRAB,
                        (z & WindowResizer.N) != 0, (z & WindowResizer.S) != 0,
                        (z & WindowResizer.W) != 0, (z & WindowResizer.E) != 0,
                        Kit.tint(t.brand(), 90));
                }
            }
        };
    }

    private int opacityAlpha() {
        return Math.max(0, Math.min(255, Math.round(opacityPercent * 255f / 100f)));
    }

    private void placeTopRight() {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setLocation(Math.max(0, screen.width - frame.getWidth() - 48), 64);
    }

    // ----------------------------------------------------------------- layout

    /**
     * `.panel__head` — 12/12/8, gap 8, one hairline under it, and transparent.
     *
     * Transparent matters: the panel's own rounded surface is what shows behind
     * it, so the top corners stay round. A flat fill here would square them off
     * (Swing clips nothing to the parent's radius).
     *
     * It doubles as the window's drag handle, but the gesture itself lives in
     * {@link WindowResizer} — one controller has to arbitrate between "drag the
     * window" and "resize it" for every press.
     */
    private JComponent buildHeader() {
        Surface bar = new Surface((g2, w, h, t) -> {
            g2.setColor(t.border1());
            g2.fillRect(0, h - 1, w, 1);
        });
        bar.setLayout(new BorderLayout(Tokens.SP3, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(
            Tokens.SP4, Tokens.SP4, Tokens.SP3, Tokens.SP4));
        bar.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));

        // FR-PANEL-02: 出错 is the one chip state that leads somewhere — it takes
        // the user to the round that carries the full error, which is what a
        // 430px-wide status line never could.
        chip.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (chip.kind() != Chip.Kind.ERROR || lastError == null) return;
                showTab(0);
                scrollTop();
            }
        });

        bar.add(new Mark(), BorderLayout.WEST);
        bar.add(buildTitle(), BorderLayout.CENTER);
        bar.add(buildWindows(), BorderLayout.EAST);
        return bar;
    }

    /** The status chip and the three window buttons, 8px apart (`gap: 8`). */
    private JComponent buildWindows() {
        JPanel wins = new JPanel();
        wins.setOpaque(false);
        wins.setLayout(new BoxLayout(wins, BoxLayout.X_AXIS));
        wins.add(new Btn(Glyphs.gear(), Btn.Variant.ICON).onClick(() -> onOpenSettings.run()));
        wins.add(Box.createHorizontalStrut(2));
        wins.add(new Btn(Glyphs.minimize(), Btn.Variant.ICON).onClick(() -> onMinimize.run()));
        wins.add(Box.createHorizontalStrut(2));
        wins.add(new Btn(Glyphs.close(), Btn.Variant.ICON).iconDanger(true)
            .onClick(() -> onClose.run()));

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
        right.add(chip);
        right.add(Box.createHorizontalStrut(Tokens.SP3));
        right.add(wins);
        return right;
    }

    /** `.panel__titlewrap` — the name with its capsule, over the sub-line. */
    private JComponent buildTitle() {
        // `title` yields to the capsule rather than pushing it off: its
        // preferred width is a few pixels and its maximum is unbounded, so
        // BoxLayout hands it exactly the slack the capsule leaves (FR-PANEL-01
        // wants the name truncated, not the capsule lost).
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.add(title);
        row.add(Box.createHorizontalStrut(Tokens.SP2));
        row.add(relPill);

        subtitle.applyFont();

        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.add(row);
        box.add(subtitle);

        relPill.onClick(this::openRelationMenu);
        return box;
    }

    /** Re-applies the header's fonts — the one part of the panel not rebuilt. */
    private void styleHeader() {
        title.applyFont();
        subtitle.applyFont();
        header.repaint();
    }

    /** `.panel__tabs` + `.panel__body`: the segment over the two stacked panes. */
    private JComponent buildBody() {
        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);

        JPanel tabsHolder = new JPanel(new BorderLayout());
        tabsHolder.setOpaque(false);
        tabsHolder.setBorder(BorderFactory.createEmptyBorder(
            Tokens.SP3, Tokens.SP4, 0, Tokens.SP4));
        tabsHolder.add(tabs.fullWidth(), BorderLayout.CENTER);

        body.add(tabsHolder, BorderLayout.NORTH);
        body.add(panes, BorderLayout.CENTER);

        panes.setOpaque(false);
        panes.add(analyzePane(), "analyze");
        panes.add(askPane(), "ask");
        tabs.onChange(i -> showTab(i));
        return body;
    }

    private JComponent analyzePane() {
        JPanel pane = new JPanel(new BorderLayout());
        pane.setOpaque(false);
        pane.add(roundsScroll, BorderLayout.CENTER);
        return pane;
    }

    /**
     * 「问 AI」: the history scrolls, the input stays pinned to the bottom
     * (FR-PANEL-14) — the old layout had the box on top, which is the wrong way
     * round for anything that reads as a conversation.
     */
    private JComponent askPane() {
        JPanel pane = new JPanel(new BorderLayout());
        pane.setOpaque(false);

        askInput.setToolTipText("Enter 发送，Shift+Enter 换行");
        // Key bindings rather than a KeyListener: the binding wins over the text
        // area's own insert-break, and Shift+Enter keeps it.
        askInput.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ask-send");
        askInput.getActionMap().put("ask-send", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                submitAsk();
            }
        });
        JScrollPane box = askBox();
        // Both a keystroke and the post-send `setText("")` change the line count,
        // and the box's height is the line count — but only the CHANGES may
        // re-layout: revalidating on every keystroke would run a layout pass per
        // character for no reason.
        askInput.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                syncAskSend();
                syncAskBox(box);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                syncAskSend();
                syncAskBox(box);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                syncAskSend();
                syncAskBox(box);
            }
        });

        // `.ask-input{gap:6;padding:8px 12px;border-top:1px border-1;background:surface-1}`.
        Surface bar = new Surface(Surface.flatEdge(t -> t.surface1(), t -> t.border1(), true));
        bar.setLayout(new BorderLayout(Tokens.SP2, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(
            Tokens.SP3, Tokens.SP4, Tokens.SP3, Tokens.SP4));
        bar.add(box, BorderLayout.CENTER);
        JPanel sendWrap = new JPanel(new BorderLayout());
        sendWrap.setOpaque(false);
        // `.ask-input{align-items:flex-end}`: the send button sits on the box's
        // bottom line, not stretched to the bar's full height.
        sendWrap.add(askSend, BorderLayout.SOUTH);
        bar.add(sendWrap, BorderLayout.EAST);

        askSend.onClick(this::submitAsk);
        pane.add(bar, BorderLayout.SOUTH);
        pane.add(askScroll, BorderLayout.CENTER);
        return pane;
    }

    /**
     * The 「问 AI」 box, in the scroll pane it cannot do without.
     *
     * <p>`askInput` wraps its lines, and a wrapping `JTextArea` that is not
     * inside a viewport has nowhere to put the second line: the bar gives it one
     * line's worth of height, everything past that is clipped, and the caret is
     * free to sit on a line that is not on screen at all — the user then types
     * into a box that does not show what they typed. The viewport both grows to
     * [ASK_MAX_LINES] lines and, past that, keeps the caret in view the way
     * `scrollRectToVisible` is supposed to.
     */
    private JScrollPane askBox() {
        JScrollPane box = new JScrollPane(askInput) {
            @Override
            public Dimension getPreferredSize() {
                // Height comes from the LOGICAL line count — the ones Shift+Enter
                // makes — which `JTextArea` knows without being measured. A long
                // line that merely wraps is scrolled instead of growing the box:
                // measuring the wrapped height means sizing a text view from
                // inside the layout pass that is asking for that height, which is
                // the setSize/layout feedback loop that left this window blank
                // once already (the whole reason MdText renders into a JLabel).
                int lines = Math.min(ASK_MAX_LINES, Math.max(1, askInput.getLineCount()));
                java.awt.FontMetrics fm = askInput.getFontMetrics(askInput.getFont());
                Insets in = askInput.getInsets();
                Insets out = getInsets();
                return new Dimension(1, lines * fm.getHeight()
                    + in.top + in.bottom + out.top + out.bottom);
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        box.setOpaque(false);
        box.getViewport().setOpaque(false);
        box.setBorder(BorderFactory.createEmptyBorder());
        box.setViewportBorder(BorderFactory.createEmptyBorder());
        box.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        box.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        Theme.slimScrollBar(box);
        return box;
    }

    /** Re-lays the ask box out, but only when its line count really changed. */
    private void syncAskBox(JScrollPane box) {
        int lines = Math.min(ASK_MAX_LINES, Math.max(1, askInput.getLineCount()));
        if (lines == askBoxLines) return;
        askBoxLines = lines;
        box.revalidate();
        box.repaint();
    }

    /** FR-PANEL-11/13: one primary, one secondary, one overflow — not 3×2. */
    private JComponent buildFooter() {
        analyzeBtn.onClick(() -> onManualAnalyze.run());
        pauseBtn.onClick(() -> onTogglePause.run());
        moreBtn.onClick(this::openOverflow);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
        right.add(pauseBtn);
        right.add(Box.createHorizontalStrut(Tokens.SP2));
        right.add(moreBtn);

        // `.panel__foot{gap:6;padding:8px 12px;border-top;background:surface-1}`.
        //
        // The fill squares the panel's rounded bottom corners unless it rounds
        // them itself: `overflow: hidden` does the clipping in the prototype and
        // Swing has no equivalent, so the two lower corners are cut here.
        Surface foot = new Surface((g2, w, h, t) -> {
            int r = Math.min(Tokens.R_XL, Math.min(w, h) / 2);
            Path2D p = new Path2D.Float();
            p.moveTo(0, 0);
            p.lineTo(w, 0);
            p.lineTo(w, h - r);
            p.quadTo(w, h, w - r, h);
            p.lineTo(r, h);
            p.quadTo(0, h, 0, h - r);
            p.closePath();
            g2.setColor(t.surface1());
            g2.fill(p);
            g2.setColor(t.border1());
            g2.fillRect(0, 0, w, 1);
        });
        foot.setLayout(new BorderLayout(Tokens.SP2, 0));
        foot.setBorder(BorderFactory.createEmptyBorder(
            Tokens.SP3, Tokens.SP4, Tokens.SP3, Tokens.SP4));
        foot.add(analyzeBtn, BorderLayout.CENTER);
        foot.add(right, BorderLayout.EAST);
        return foot;
    }

    /**
     * A borderless, slim-scrolled viewport over a column of blocks.
     *
     * The resize listener is what makes text re-wrap: wrapped heights are
     * derived from the available width, but Swing measures children before it
     * assigns them their bounds, so the pass after a resize still measures
     * against the old width.
     */
    private static JScrollPane scroll(JComponent inner) {
        JScrollPane sp = new JScrollPane(inner);
        sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        // ALWAYS, not AS_NEEDED: a bar that appears and disappears changes the
        // content width by 8px, which re-wraps every HTML block, which changes the
        // content height, which toggles the bar again. That ping-pong is a repaint
        // loop, and a repaint loop is the blank window. The bar is 8px wide with an
        // unpainted track, so reserving it costs nothing visible.
        sp.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        Theme.slimScrollBar(sp);

        int[] lastWidth = {0};
        sp.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int w = sp.getViewport().getWidth();
                if (w == lastWidth[0]) return;
                lastWidth[0] = w;
                inner.revalidate();
                inner.repaint();
            }
        });
        return sp;
    }

    private void applyNarrowLayout() {
        boolean narrow = frame.getWidth() < NARROW_WIDTH;
        if (pauseBtn.isVisible() == !narrow) return;
        pauseBtn.setVisible(!narrow);
        pauseBtn.getParent().revalidate();
    }

    // --------------------------------------------------------------- public API

    public JFrame frame() {
        return frame;
    }

    public void setVisible(boolean v) {
        frame.setVisible(v);
    }

    public void toFront() {
        frame.toFront();
    }

    /**
     * Takes the window down for good — the close button and the tray's 退出, not
     * 最小化 (which is {@link #setVisible(boolean) setVisible(false)} and keeps
     * the assistant running).
     *
     * <p>Disposing rather than just exiting is what keeps Windows from leaving
     * the panel's pixels behind: this frame is undecorated, always on top and
     * per-pixel translucent, so a process that dies with it still mapped leaves
     * a rectangle on the desktop that nothing is going to repaint. Safe to call
     * twice, and from a shutdown-hook thread as well as from the event thread —
     * the timer stops either way and the window teardown is the same call the
     * window manager would have made.
     */
    public void dispose() {
        thinkTimer.stop();
        frame.setAlwaysOnTop(false);
        frame.setVisible(false);
        frame.dispose();
    }

    public void setOnManualAnalyze(Runnable r) {
        this.onManualAnalyze = r;
    }

    public void setOnReanalyze(Runnable r) {
        this.onReanalyze = r;
    }

    public void setOnChooseRegion(Runnable r) {
        this.onChooseRegion = r;
    }

    public void setOnTogglePause(Runnable r) {
        this.onTogglePause = r;
    }

    public void setOnToggleSession(Runnable r) {
        this.onToggleSession = r;
    }

    public void setOnSaveContact(Runnable r) {
        this.onSaveContact = r;
    }

    public void setOnOpenSettings(Runnable r) {
        this.onOpenSettings = r;
    }

    public void setOnMinimize(Runnable r) {
        this.onMinimize = r;
    }

    public void setOnClose(Runnable r) {
        this.onClose = r;
    }

    public void setOnAskSubmitted(Consumer<String> c) {
        this.onAskSubmitted = c;
    }

    /** 「⋯ → 存为联系人」 on a person who is not in the knowledge base yet. */
    public void setOnSaveContactWithRelation(Consumer<String> c) {
        this.onSaveContactWithRelation = c;
    }

    /** Writes the chosen relationship onto the contact (FR-REL-01/03). */
    public void setOnRelationApply(Consumer<String> c) {
        this.onRelationApply = c;
    }

    public void setOnRelationClear(Runnable r) {
        this.onRelationClear = r;
    }

    /** The knowledge base, opened on its contacts tab. */
    public void setOnOpenKnowledge(Runnable r) {
        this.onOpenKnowledge = r;
    }

    /** 「重新生成」 on one candidate: 1-based rank. */
    public void setOnRegenerateReply(IntConsumer c) {
        this.onRegenerateReply = c;
    }

    /**
     * Whether the panel floats above other windows.
     *
     * It must be switched off while a dialog of ours is open: an always-on-top
     * window sits above every ordinary one, so the settings and knowledge
     * dialogs — which are centred on the panel and therefore overlap it almost
     * exactly — would be covered by it and their buttons unclickable (P-19).
     */
    public void setAlwaysOnTop(boolean v) {
        frame.setAlwaysOnTop(v);
    }

    /**
     * The panel surface's opacity, in percent. The settings slider previews with
     * this, so it repaints the surface immediately rather than on the next launch.
     */
    public void setOpacity(int percent) {
        this.opacityPercent = percent;
        root.repaint();
    }

    /** The conversation being read — its title and where the region sits. */
    public void setCaptureInfo(String info) {
        this.captureInfo = info == null || info.isBlank() ? null : info;
        syncHeader();
    }

    /** The user's pause switch: repaints the button and the status chip. */
    public void setPaused(boolean paused) {
        userPaused = paused;
        pauseBtn.label(paused ? "继续" : "暂停");
        if (!loading) syncStatus();
        render();
    }

    /** Whether this conversation's own context buffer is being recorded. */
    public void setSessionActive(boolean active) {
        this.sessionActive = active;
    }

    public void setContextInfo(int notes, int history, boolean session) {
        this.ctxNotes = notes;
        this.ctxHistory = history;
        this.sessionActive = session;
    }

    /**
     * The relationship in force for the conversation on screen, and whether a
     * contact exists to store a change on.
     *
     * It is resolved once per round by the pipeline and pushed here, because the
     * capsule has to say where the value came from: showing 「伴侣」 for someone
     * who merely inherited the default hides the fact that editing that person
     * would change the analysis (FR-REL-02/06).
     */
    public void setRelationship(Relations.Resolved resolved, boolean contactExists) {
        this.relation = resolved == null
            ? new Relations.Resolved("", Relations.Source.FALLBACK_UNKNOWN, null) : resolved;
        this.hasContact = contactExists;
        syncHeader();
    }

    /**
     * The messages the vision reader saw this round (FR-PANEL-06), attached to
     * the next round that lands.
     *
     * @param visible the user's privacy preference; when off the disclosure is
     *        not rendered at all rather than rendered empty.
     */
    public void setEvidence(List<Msg> messages, boolean visible) {
        this.nextEvidence = messages == null ? List.of() : List.copyOf(messages);
        this.evidenceVisible = visible;
    }

    /**
     * The user's 「本轮依据」 privacy preference (Q-3), re-read after the settings
     * dialog. Off hides the disclosure entirely rather than rendering it empty.
     */
    public void setEvidenceVisible(boolean visible) {
        this.evidenceVisible = visible;
        render();
    }

    public void showAnalyzing() {
        loading = true;
        lastError = null;
        notice = null;
        replyError = null;
        pendingReplies = null;
        think.setLength(0);
        thinkSince = System.currentTimeMillis();
        thinkForAsk = false;
        liveThink = null;
        liveChip = null;
        syncStatus();
        render();
        scrollTop();
    }

    public void showError(String msg) {
        loading = false;
        liveThink = null;
        liveChip = null;
        lastError = msg;
        notice = null;
        thinkTimer.stop();
        // The sub-line says 接口异常 while the chip says 出错 — the header carries
        // the state as well as the badge, which is where the prototype puts it.
        syncHeader();
        render();
    }

    public void showNotice(String msg) {
        notice = msg;
        render();
    }

    /**
     * A round that ended without a judgment — skipped, not failed.
     *
     * It has to clear the loading state, which [showNotice] deliberately does
     * not: the round is over, and leaving it set would park the panel on
     * 「分析中…」 with its timer running for the rest of the session.
     */
    public void showSkipped(String msg) {
        loading = false;
        liveThink = null;
        liveChip = null;
        thinkTimer.stop();
        showNotice(msg);
    }

    /**
     * Switch which conversation the analysis page shows.
     *
     * Records are NOT erased when the title is unknown (blank) — that would wipe
     * a real conversation's history the moment the vision reader failed to read
     * the header.
     */
    public void switchConversation(String key) {
        String k = key == null ? "" : key.trim();
        if (k.isBlank() || k.equals(convKey)) return;
        boolean moved = !convKey.isBlank();
        convKey = k;
        rounds.clear();
        current = null;
        pendingReplies = null;
        awaitingReplies = false;
        lastError = null;
        notice = null;
        nextEvidence = List.of();
        if (moved) Toast.show(frame, "已切换到「" + k + "」");
        syncHeader();
        render();
        scrollTop();
    }

    /** A finished judgment lands as a new round at the top of the stack. */
    public void showJudgment(Analysis a) {
        loading = false;
        liveThink = null;
        liveChip = null;
        thinkTimer.stop();
        List<RankedReply> merged = pendingReplies;
        pendingReplies = null;
        awaitingReplies = merged == null;
        Analysis full = merged != null ? a.withRankedReplies(merged) : a;
        current = new Round(System.currentTimeMillis(), full);
        rounds.add(0, current);
        while (rounds.size() > MAX_ROUNDS) rounds.remove(rounds.size() - 1);
        lastError = null;
        notice = null;
        syncStatus();
        render();
        scrollTop();
    }

    /**
     * Candidate replies. They arrive on a second parallel call, so they may land
     * before the judgment (then they are held and merged into it) or after it
     * (then they are folded into the current round).
     */
    public void showReplies(List<RankedReply> ranked, String error) {
        if (current == null || !awaitingReplies) {
            pendingReplies = ranked;
            return;
        }
        awaitingReplies = false;
        replyError = error;
        current.analysis = current.analysis.withRankedReplies(ranked);
        render();
    }

    /**
     * Swaps one candidate for a freshly drafted one, leaving its neighbours alone.
     *
     * The drafting route answers with a set of three, so the replacement is the
     * first line the user is not already reading — anything else would either
     * duplicate a card or reshuffle the whole round under the cursor.
     */
    public void replaceReply(int rank, List<RankedReply> fresh) {
        if (current == null || rank < 1 || fresh == null || fresh.isEmpty()) return;
        List<RankedReply> ranked = current.analysis.rankedReplies();
        if (ranked == null || rank > ranked.size()) return;
        Set<String> shown = new HashSet<>();
        for (RankedReply r : ranked) shown.add(r.text().trim());
        RankedReply pick = fresh.get(0);
        for (RankedReply r : fresh) {
            if (!shown.contains(r.text().trim())) {
                pick = r;
                break;
            }
        }
        List<RankedReply> next = new ArrayList<>(ranked);
        next.set(rank - 1, pick);
        current.analysis = current.analysis.withRankedReplies(next);
        render();
        Toast.show(frame, "已重新生成第 " + rank + " 条");
    }

    /** One streamed chunk of the reasoning pass. */
    public void onThinkingDelta(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        if (thinkForAsk) {
            askThink.append(chunk);
            if (liveThink != null) {
                liveThink.render(tail(askThink));
            } else {
                renderAsk();
            }
            return;
        }
        think.append(chunk);
        if (liveThink != null) {
            // FR-PANEL-07: append in place. Rebuilding the list here would drop
            // the user's scroll position once per streamed token.
            liveThink.render(tail(think));
        } else {
            render();
        }
    }

    public void showAsking(String question) {
        asks.add(new Ask(question));
        while (asks.size() > MAX_ASKS) asks.remove(0);
        askBusy = true;
        askThink.setLength(0);
        thinkSince = System.currentTimeMillis();
        thinkForAsk = true;
        liveThink = null;
        liveChip = null;
        thinkTimer.restart();
        showTab(1);
        renderAsk();
        scrollAskBottom();
    }

    public void showAnswer(String question, String answer, String reasoning) {
        Ask last = pending();
        if (last == null) {
            last = new Ask(question == null ? "" : question);
            asks.add(last);
        }
        last.answer = answer;
        last.reasoning = reasoning;
        askBusy = false;
        liveThink = null;
        liveChip = null;
        thinkTimer.stop();
        showTab(1);
        renderAsk();
        scrollAskBottom();
    }

    public void showAskError(String msg) {
        Ask last = pending();
        if (last == null) {
            // A refusal before the question was ever sent — 「先输入问题」 — is not
            // a failed turn, so it does not get a card.
            showNotice(msg);
            return;
        }
        last.error = msg;
        askBusy = false;
        liveThink = null;
        liveChip = null;
        thinkTimer.stop();
        showTab(1);
        renderAsk();
        scrollAskBottom();
    }

    private Ask pending() {
        for (int i = asks.size() - 1; i >= 0; i--) {
            if (asks.get(i).pending()) return asks.get(i);
        }
        return null;
    }

    // ---------------------------------------------------------------- actions

    private void showTab(int idx) {
        ((CardLayout) panes.getLayout()).show(panes, idx == 1 ? "ask" : "analyze");
        tabs.setSelected(idx);
        if (idx == 1) SwingUtilities.invokeLater(() -> askInput.requestFocusInWindow());
    }

    private void submitAsk() {
        if (askBusy) return;
        String q = askInput.getText().trim();
        if (q.isEmpty()) {
            Toast.show(frame, "先输入问题");
            return;
        }
        askInput.setText("");
        onAskSubmitted.accept(q);
    }

    private void syncAskSend() {
        if (askBusy != sendShowsBusy) {
            sendShowsBusy = askBusy;
            askSend.label(askBusy ? "发送中…" : "发送");
        }
        askSend.setEnabled(!askBusy && !askInput.getText().trim().isEmpty());
    }

    /** 「⋯」: the five per-conversation actions that used to be footer buttons. */
    private void openOverflow() {
        boolean bound = !convKey.isBlank();
        List<Menu.Item> items = new ArrayList<>();
        items.add(Menu.Item.of("选择区域", Glyphs.crop(), () -> onChooseRegion.run()));
        // FR-PANEL-12: the three that need a recognised conversation are greyed
        // rather than silently doing nothing. The kit's menu has no tooltips, so
        // the reason is spelled out on a row of its own instead of hiding behind
        // a hover — and greying them costs nothing, because the pipeline refuses
        // all three with the same sentence when there is no title yet.
        items.add(bound
            ? Menu.Item.of("存为联系人", () -> onSaveContact.run())
            : Menu.Item.disabled("存为联系人"));
        items.add(bound
            ? Menu.Item.of(sessionActive ? "清除本会话" : "记录本会话", () -> onToggleSession.run())
            : Menu.Item.disabled(sessionActive ? "清除本会话" : "记录本会话"));
        items.add(Menu.Item.separator());
        items.add(bound
            ? Menu.Item.of("重新分析", () -> onReanalyze.run())
            : Menu.Item.disabled("重新分析"));
        items.add(Menu.Item.of("设置", Glyphs.gear(), () -> onOpenSettings.run()));
        // Folded away below 380px, so it has to be reachable from here.
        if (!pauseBtn.isVisible()) {
            items.add(Menu.Item.separator());
            items.add(Menu.Item.of(userPaused ? "继续监听" : "暂停监听", () -> onTogglePause.run()));
        }
        if (ctxNotes > 0 || ctxHistory > 0) {
            items.add(Menu.Item.separator());
            items.add(Menu.Item.disabled("知识库 " + ctxNotes + " 条 · 历史 " + ctxHistory + " 条"));
        }
        if (!bound) {
            items.add(Menu.Item.separator());
            items.add(Menu.Item.disabled("先分析一次，认出会话人后才能用这几项"));
        }
        Menu.show(moreBtn, items, Tokens.SP5);
    }

    /**
     * FR-REL-03: change the relationship where the problem was noticed.
     *
     * A relationship is stored on the contact, so a conversation the knowledge
     * base does not know cannot hold one — the menu then offers 「存为联系人」
     * instead of accepting a value it has nowhere to put.
     */
    private void openRelationMenu() {
        if (convKey.isBlank()) {
            Toast.show(frame, "先分析一次，认出会话人后才能设置关系");
            return;
        }
        RelationMenu.show(relPill, convKey, relation.value(), hasContact,
            v -> onRelationApply.accept(v),
            () -> onRelationClear.run(),
            () -> onOpenKnowledge.run(),
            v -> onSaveContactWithRelation.accept(v));
    }

    // -------------------------------------------------------------- rendering

    private void tick() {
        long secs = Math.max(0, System.currentTimeMillis() - thinkSince) / 1000;
        if (loading) {
            if (liveChip != null) liveChip.setText("分析中…（已用 " + secs + "s）");
            chip.setText("分析中… " + secs + "s");
        } else if (askBusy) {
            if (liveChip != null) liveChip.setText("思考中…（已用 " + secs + "s）");
        }
    }

    private void syncStatus() {
        if (loading) {
            long secs = Math.max(0, System.currentTimeMillis() - thinkSince) / 1000;
            chip.setState(Chip.Kind.ANALYZING, "分析中… " + secs + "s");
        } else if (lastError != null) {
            chip.setState(Chip.Kind.ERROR, "出错，点开看详情");
        } else if (userPaused) {
            chip.setState(Chip.Kind.PAUSED, "已暂停");
        } else {
            chip.setState(Chip.Kind.LISTENING, "监听中");
        }
        // The chip is a button only in the error state; everywhere else it must
        // not swallow the header's move cursor, so the override is cleared.
        chip.setCursor(chip.kind() == Chip.Kind.ERROR
            ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
    }

    private void syncHeader() {
        String name = convKey.isBlank() ? "未绑定会话" : convKey;
        title.setText(name);
        title.setToolTipText(name);
        String where = captureInfo == null ? "还没框选监听区域" : "已绑定区域 · " + captureInfo;
        if (lastError != null) where = "接口异常 · " + where;
        subtitle.setText(where);
        subtitle.setToolTipText(where);

        boolean fallback = relation.source() != Relations.Source.CONTACT;
        relPill.set(relationLabel(), fallback);
        relPill.setToolTipText(relationTip());
        pauseBtn.label(userPaused ? "继续" : "暂停");
        syncStatus();
        syncAskSend();
    }

    private String relationLabel() {
        String v = relation.value() == null ? "" : relation.value().trim();
        if (v.isEmpty()) return "未设关系，点这里设置";
        return switch (relation.source()) {
            case CONTACT -> v;
            case FALLBACK_CONTACT -> v + "（未设置，用默认）";
            case FALLBACK_UNKNOWN -> v + "（未识别此人，用默认）";
        };
    }

    private String relationTip() {
        String who = relation.contactName() == null ? convKey : relation.contactName();
        String head = switch (relation.source()) {
            case CONTACT -> "「" + who + "」的关系，点击可修改";
            case FALLBACK_CONTACT -> "「" + who + "」还没设置专属关系，当前用的是默认关系。点击可设置。";
            case FALLBACK_UNKNOWN -> "没认出这个人，当前用的是默认关系。点击可设置。";
        };
        return relation.source() == Relations.Source.CONTACT
            ? head : head + "（关系跟着人走，设置一次之后一直用它）";
    }

    /**
     * The one place the analysis list is built.
     *
     * Order matters and matches `renderAnalyze()`: at most ONE of the four
     * banners (loading / error / notice / paused) sits on top, the rounds follow
     * whatever happened, and the empty state only appears when there is genuinely
     * nothing else to show — so a paused panel with no history still explains
     * itself twice rather than showing a blank column.
     */
    private void render() {
        roundsBox.removeAll();
        if (loading) {
            roundsBox.add(analyzingCard());
            roundsBox.add(strut(Tokens.SP3));
        } else if (lastError != null) {
            roundsBox.add(errorCard(lastError));
            roundsBox.add(strut(Tokens.SP3));
        } else if (notice != null) {
            roundsBox.add(noticeCard(notice));
            roundsBox.add(strut(Tokens.SP3));
        } else if (userPaused) {
            roundsBox.add(pausedBanner());
            roundsBox.add(strut(Tokens.SP3));
        }

        for (int i = 0; i < rounds.size(); i++) {
            roundsBox.add(roundCard(rounds.get(i), i == 0, rounds.size() - i));
            roundsBox.add(strut(Tokens.SP3));
        }
        if (rounds.isEmpty() && !loading && lastError == null && notice == null) {
            roundsBox.add(emptyState());
        }
        roundsBox.add(Box.createVerticalGlue());
        roundsBox.revalidate();
        roundsBox.repaint();
    }

    // ---------------------------------------------------------------- states

    /** FR-PANEL-10: an invitation with a button, never one grey line. */
    private JComponent emptyState() {
        EmptyState empty = new EmptyState(Glyphs.sparkle(), "还没有分析",
            "它会在对方发来新消息时自动分析，也可以现在就手动跑一次。");
        empty.action(new Btn("分析当前对话", Btn.Variant.PRIMARY)
            .onClick(() -> onManualAnalyze.run()));
        // It fills the width and centres its own contents; the alignment is
        // pinned so it cannot drag its siblings off the left edge.
        empty.setAlignmentX(Component.LEFT_ALIGNMENT);
        return empty;
    }

    /** FR-PANEL-17: the wait, made inspectable — chip, headline, live reasoning. */
    private JComponent analyzingCard() {
        Card card = Card.plain();
        long secs = Math.max(0, System.currentTimeMillis() - thinkSince) / 1000;
        liveChip = new Chip(Chip.Kind.ANALYZING, "分析中…（已用 " + secs + "s）");
        card.add(row(Tokens.SP3, liveChip));
        card.with(headline("正在读消息、判断意图，并起草候选回复"), Tokens.SP3);
        if (think.length() > 0) {
            // FR-PANEL-07: one label, appended to in place. The tail keeps the
            // newest words in view without letting a long transcript push the
            // footer off the panel.
            liveThink = MdText.area(tail(think), Tokens.FS_NOTE, MdText.Tone.MUTED);
            card.with(thinkBlock(liveThink, THINK_LIVE_MAX), Tokens.SP3);
        } else {
            liveThink = null;
        }
        card.with(hint("模型思考通常 10–30 秒，先前的记录留在下面。"), Tokens.SP3);
        return card;
    }

    /**
     * FR-PANEL-18: the whole error, at whatever length it needs.
     *
     * The card is the `.round` shape in danger colours; the advice sits in a
     * `.think` box, and the actions are the two the prototype offers plus 复制错误
     * — a long stack trace is exactly the thing a user wants to paste somewhere.
     */
    private JComponent errorCard(String msg) {
        Card card = Card.danger();
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(new Pill("分析失败", Pill.Kind.SOLID_DANGER), BorderLayout.WEST);
        top.add(timeLabel(System.currentTimeMillis()), BorderLayout.EAST);
        card.add(top);
        card.with(MdText.area(msg, Tokens.FS_SUBTITLE, MdText.Tone.DANGER), Tokens.SP1);
        card.with(thinkBlock(MdText.area(
            "请检查设置 → 接口 → 判断路由的密钥是否正确。\n"
                + "常见原因：密钥复制时带了空格、服务商余额为 0、或该模型未在你的账号下开通。",
            Tokens.FS_NOTE, MdText.Tone.MUTED), THINK_MAX), Tokens.SP3);
        card.with(row(Tokens.SP2,
            new Btn("重试", Btn.Variant.PRIMARY, Btn.Size.SM).onClick(() -> onManualAnalyze.run()),
            new Btn("去设置", Btn.Variant.SECONDARY, Btn.Size.SM)
                .onClick(() -> onOpenSettings.run()),
            new Btn("复制错误", Btn.Variant.GHOST, Btn.Size.SM).onClick(() -> copy(msg))),
            Tokens.SP4);
        return card;
    }

    private JComponent noticeCard(String msg) {
        Card card = Card.subtle();
        card.add(row(Tokens.SP3, new Pill("提示", Pill.Kind.BRAND)));
        card.with(MdText.area(msg, Tokens.FS_BODY, MdText.Tone.MUTED), Tokens.SP2);
        return card;
    }

    /** The `card--subtle` banner: a paused chip and one line of explanation. */
    private JComponent pausedBanner() {
        Card card = Card.subtle();
        JPanel line = new JPanel(new BorderLayout(Tokens.SP3, 0));
        line.setOpaque(false);
        line.add(new Chip(Chip.Kind.PAUSED, "已暂停监听"), BorderLayout.WEST);
        line.add(label("暂停期间不会自动分析，手动「分析当前对话」仍可用。",
            Font.PLAIN, Tokens.FS_NOTE, Theme.tokens().text2()), BorderLayout.CENTER);
        card.add(line);
        return card;
    }

    // ----------------------------------------------------------- round cards

    /**
     * One round as `roundCard()` draws it: a header row, the conclusion, the tag
     * row, and then everything else is folded away so the headline keeps the
     * screen.
     *
     * The gaps are the CSS margins, spelled out: `.round__top`,
     * `.round__conclusion` and `.round__tags` each carry `margin-bottom: 8px`,
     * `.round__divider` adds its own `6px 0`, and `.section-label` an `8px 0 6px`.
     * They do not collapse, so the struts stack.
     */
    private JComponent roundCard(Round r, boolean newest, int no) {
        Analysis a = r.analysis;
        Card card = Card.plain();

        JPanel top = new JPanel(new BorderLayout(Tokens.SP3, 0));
        top.setOpaque(false);
        // FR-PANEL-09: the newest round is 「最新」; everything below counts up.
        top.add(row(Tokens.SP3, timeLabel(r.at), newest
            ? new Pill("最新", Pill.Kind.BRAND)
            : new Pill("第 " + no + " 轮", Pill.Kind.NEUTRAL)), BorderLayout.CENTER);
        JComponent danger = dangerPill(a);
        if (danger != null) top.add(danger, BorderLayout.EAST);
        card.add(top);

        card.add(strut(Tokens.SP3));
        card.add(headline(conclusion(a)));

        JComponent tags = tagRow(a);
        if (tags != null) {
            card.add(strut(Tokens.SP3));
            card.add(tags);
            card.add(strut(Tokens.SP3));
        }
        card.add(strut(Tokens.SP2));
        card.add(divider());
        card.add(strut(Tokens.SP2));

        JComponent evidence = evidenceBlock(r);
        if (evidence != null) card.add(evidence);
        if (a.reasoning() != null && !a.reasoning().isBlank()) {
            card.add(collapse("思考过程", r.thinkOpen, () -> {
                r.thinkOpen = !r.thinkOpen;
                render();
            }, thinkBlock(MdText.area(a.reasoning(), Tokens.FS_NOTE, MdText.Tone.MUTED),
                THINK_MAX)));
        }

        card.add(strut(Tokens.SP3));
        card.add(sectionLabel("候选回复"));
        card.add(strut(Tokens.SP2));
        card.add(repliesBlock(r, newest));
        return card;
    }

    private JComponent repliesBlock(Round r, boolean newest) {
        JPanel box = Theme.column();
        if (newest && awaitingReplies) {
            box.add(note("生成中…"));
            return box;
        }
        List<RankedReply> ranked = r.analysis.rankedReplies();
        if (ranked == null || ranked.isEmpty()) {
            box.add(newest && replyError != null
                ? MdText.area("回复接口出错：" + replyError, Tokens.FS_BODY, MdText.Tone.DANGER)
                : note("（未生成候选回复）"));
            return box;
        }
        // `.replies{gap:6}`
        for (int i = 0; i < ranked.size(); i++) {
            if (i > 0) box.add(strut(Tokens.SP2));
            box.add(replyCard(r, i + 1, ranked.get(i), i == 0, newest));
        }
        return box;
    }

    /** FR-PANEL-08: rank, hit probability, the text, copy / edit / regenerate. */
    private JComponent replyCard(Round r, int rank, RankedReply reply, boolean top,
                                 boolean newest) {
        Block card = new Block(top
            ? Surface.of(Tokens.R_MD, Tokens::brandWeak, null)
            : Surface.of(Tokens.R_MD, Tokens::surface0, Tokens::border1), Tokens.SP3);

        // `.reply__head{gap:8;margin-bottom:6}`: the rank, the `.bar` taking all
        // the slack, the percentage, and 推荐 on the first card.
        JPanel head = new JPanel(new BorderLayout(Tokens.SP3, 0));
        head.setOpaque(false);
        head.add(label("#" + rank, Font.BOLD, Tokens.FS_NOTE, Theme.tokens().brand()),
            BorderLayout.WEST);
        head.add(stretch(new Bar().setFraction(reply.prob())), BorderLayout.CENTER);
        JLabel prob = label(Math.round(reply.prob() * 100) + "%", Font.PLAIN,
            Tokens.FS_NOTE, Theme.tokens().text2());
        head.add(hrow(Tokens.SP3, prob, top ? new Pill("推荐", Pill.Kind.BRAND) : null),
            BorderLayout.EAST);
        card.add(head);

        if (r.editing == rank) {
            // Q-4's 「编辑后再复制」: the half-typed draft lives on the round, so a
            // re-render — a theme switch, the next streamed chunk — cannot throw
            // the typing away.
            Area editor = new Area(3, 12).text(r.editDraft);
            editor.grow();
            editor.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    r.editDraft = editor.getText();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    r.editDraft = editor.getText();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    r.editDraft = editor.getText();
                }
            });
            card.with(editor, Tokens.SP2);
            card.with(row(Tokens.SP2,
                new Btn("复制", Btn.Variant.PRIMARY, Btn.Size.SM)
                    .onClick(() -> copy(r.editDraft)),
                new Btn("取消", Btn.Variant.GHOST, Btn.Size.SM).onClick(() -> {
                    r.editing = 0;
                    render();
                })), Tokens.SP3);
            return card;
        }

        // `.reply__text{font-size:13;line-height:20;margin-bottom:8}`
        card.with(MdText.area(reply.text(), Tokens.FS_BODY), Tokens.SP2);
        card.with(row(Tokens.SP2,
            new Btn("复制", top ? Btn.Variant.PRIMARY : Btn.Variant.SECONDARY, Btn.Size.SM)
                .onClick(() -> copy(reply.text())),
            new Btn("编辑后再复制", Btn.Variant.GHOST, Btn.Size.SM).onClick(() -> {
                r.editing = rank;
                r.editDraft = reply.text();
                render();
            }),
            newest ? new Btn("重新生成", Btn.Variant.GHOST, Btn.Size.SM)
                .onClick(() -> onRegenerateReply.accept(rank)) : null), Tokens.SP3);
        return card;
    }

    /**
     * FR-PANEL-06: the messages the reader saw, so the advice is not floating in
     * mid-air. The user can switch the whole block off (privacy, Q-3) — off means
     * the disclosure is not built at all, not built empty.
     */
    private JComponent evidenceBlock(Round r) {
        if (!evidenceVisible || nextEvidence.isEmpty()) return null;
        JPanel body = Theme.column();
        for (int i = 0; i < nextEvidence.size(); i++) {
            Msg m = nextEvidence.get(i);
            boolean me = "me".equals(m.side());
            // `.evidence__item{gap:6;padding:6px 8px;r 6;background:surface-1}` and
            // `--me` in the brand wash: the side is told apart by the wash and by
            // the 我/对方 label, and the messages stay in time order.
            Surface line = new Surface(Surface.of(Tokens.R_SM,
                t -> me ? t.brandWeak() : t.surface1(), null), new BorderLayout(Tokens.SP2, 0));
            line.setBorder(BorderFactory.createEmptyBorder(
                Tokens.SP2, Tokens.SP3, Tokens.SP2, Tokens.SP3));
            line.setAlignmentX(Component.LEFT_ALIGNMENT);
            line.add(label(me ? "我" : "对方", Font.PLAIN, Tokens.FS_NOTE,
                Theme.tokens().text3()), BorderLayout.WEST);
            line.add(MdText.area(m.text(), Tokens.FS_NOTE), BorderLayout.CENTER);
            body.add(line);
            if (i < nextEvidence.size() - 1) body.add(strut(Tokens.SP2));
        }
        return collapse("本轮依据 · " + nextEvidence.size() + " 条消息", r.evidenceOpen, () -> {
            r.evidenceOpen = !r.evidenceOpen;
            render();
        }, body);
    }

    /**
     * `.collapse` — the disclosure, with the body insets the CSS gives it.
     *
     * The open flag is passed in and written back through [onToggle], never read
     * from the widget: the block is rebuilt by every render, so the model is the
     * only thing that can remember whether the user had opened it.
     */
    private JComponent collapse(String title, boolean open, Runnable onToggle,
                                JComponent body) {
        JPanel padded = new JPanel(new BorderLayout());
        padded.setOpaque(false);
        padded.setAlignmentX(Component.LEFT_ALIGNMENT);
        padded.setBorder(BorderFactory.createEmptyBorder(Tokens.SP2, 0, Tokens.SP3, 0));
        padded.add(body, BorderLayout.CENTER);
        Collapse c = new Collapse(title).setBody(padded).setOpen(open).onToggle(onToggle);
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private JComponent dangerPill(Analysis a) {
        if (a.dangerLevel() == null) return null;
        int level = (int) Math.round(a.dangerLevel().score());
        int max = a.dangerLevel().maxLevel() > 0 ? a.dangerLevel().maxLevel() : 9;
        Pill.Kind kind = level >= 6 ? Pill.Kind.SOLID_DANGER
            : level >= 3 ? Pill.Kind.SOLID_WARNING : Pill.Kind.SOLID_SUCCESS;
        return new Pill("危险 " + level + "/" + max + " " + dangerWord(level), kind);
    }

    /** FR-PANEL-04: the tag row. Pills only — an enum key is never printed. */
    private JComponent tagRow(Analysis a) {
        Wrap wrap = new Wrap().span();
        if (a.sheNeeds() != null) {
            wrap.add(new Pill("她要：" + mapped(NEEDS, a.sheNeeds().choice()),
                Pill.Kind.NEUTRAL));
        }
        if (a.bestAction() != null) {
            wrap.add(new Pill("建议动作：" + mapped(ACTION, a.bestAction().choice()),
                Pill.Kind.NEUTRAL));
        }
        if (a.shouldReplyNow() != null) {
            wrap.add(new Pill("回应尺度：" + (a.shouldReplyNow() >= 0.5
                ? "可以实质性回应" : "先别给实质性承诺"), Pill.Kind.NEUTRAL));
        }
        if (a.trueIntent() != null && a.trueIntent().confidence() > 0) {
            wrap.add(new Pill("把握 " + Math.round(a.trueIntent().confidence() * 100) + "%",
                Pill.Kind.NEUTRAL));
        }
        if (a.tensionResolved() != null && a.tensionResolved() >= 0.7) {
            wrap.add(new Pill("紧张已缓解", Pill.Kind.SUCCESS));
        }
        return wrap.getComponentCount() == 0 ? null : wrap;
    }

    /**
     * The one-line conclusion.
     *
     * The model reports an intent enum and nothing shaped like a sentence, so the
     * headline is §8.1's user-language phrase for that enum — the same words the
     * design doc puts in this slot. An unrecognised enum becomes 「（未识别）」
     * rather than the raw English key.
     */
    private String conclusion(Analysis a) {
        if (a.trueIntent() != null) return mapped(INTENT, a.trueIntent().choice());
        if (a.sheNeeds() != null) return "对方要的是" + mapped(NEEDS, a.sheNeeds().choice());
        return "（这次没读出结论）";
    }

    private static String mapped(Map<String, String> map, String key) {
        if (key == null || key.isBlank()) return UNKNOWN;
        return map.getOrDefault(key, UNKNOWN);
    }

    private static String dangerWord(int level) {
        if (level >= 8) return "很危险";
        if (level >= 6) return "偏危险";
        if (level >= 3) return "留神";
        return "安全";
    }

    // ------------------------------------------------------------- ask pane

    /**
     * `.ask-list{gap:8;padding:8px 12px}` — one question bubble and the answer
     * under it, then the next exchange. The list is flat on purpose: in the CSS
     * the question, the answer and the gap to the next pair are all siblings at
     * 8px, so grouping them here would have to reproduce that gap anyway.
     */
    private void renderAsk() {
        askBox.removeAll();
        if (asks.isEmpty()) {
            EmptyState empty = new EmptyState(Glyphs.sparkle(), "还没有提问",
                "就当前对话问点什么，AI 会结合聊天记录和已注入的知识库回答。");
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            askBox.add(empty);
        }
        for (int i = 0; i < asks.size(); i++) {
            askBox.add(askBubbles(asks.get(i)));
            if (i < asks.size() - 1) askBox.add(strut(Tokens.SP3));
        }
        askBox.add(Box.createVerticalGlue());
        askBox.revalidate();
        askBox.repaint();
        syncAskSend();
    }

    private JComponent askBubbles(Ask ask) {
        JPanel group = Theme.column();
        group.add(side(questionBubble(ask.question), true));
        group.add(strut(Tokens.SP3));
        if (ask.pending()) {
            group.add(side(pendingBubble(), false));
        } else if (ask.error != null) {
            group.add(side(errorBubble(ask.error), false));
        } else {
            group.add(side(answerBubble(ask), false));
        }
        return group;
    }

    /**
     * `.qa__q{background:brand;color:brand-ink}` — the user's own line.
     *
     * A plain label rather than {@link MdText}, because the bubble's text takes a
     * colour that is not one of MdText's tones (it is brand-INK on brand, not
     * text-1 on white) and MdText re-applies its own foreground on every render.
     */
    private JComponent questionBubble(String question) {
        JLabel text = new JLabel();
        text.setFont(Theme.font(Font.PLAIN, Tokens.FS_BODY));
        text.setForeground(Theme.tokens().brandInk());
        String safe = esc(question);
        // `.qa__q` is shrink-to-fit: a short question is a capsule around its own
        // words. A label cannot re-wrap itself the way MdText does, so a long one
        // gets its wrap width handed to its HTML view — which is why the ask pane
        // rebuilds when the viewport's width changes.
        int wrap = questionWrapWidth();
        if (text.getFontMetrics(text.getFont())
                .stringWidth(question == null ? "" : question) > wrap) {
            text.setText("<html><body>" + safe + "</body></html>");
            MdText.wrapTo(text, wrap);
        } else {
            text.setText(safe);
        }
        return new Bubble(QUESTION_RATIO, true, text,
            Surface.of(Tokens.R_LG, Tokens::brand, null));
    }

    /** The width a question may wrap at: the row's 84%, minus the bubble's padding. */
    private int questionWrapWidth() {
        int row = askBox.getWidth() - Tokens.SP4 * 2;
        if (row <= 0) row = FALLBACK_ROW_WIDTH;
        return Math.max(40, (int) Math.round(row * QUESTION_RATIO) - Tokens.SP4 * 2);
    }

    /** `.qa__a{background:surface-1;border:1px border-1}` — the answer. */
    private JComponent answerBubble(Ask ask) {
        Bubble bubble = new Bubble(ANSWER_RATIO, false,
            MdText.area(ask.answer, Tokens.FS_BODY),
            Surface.of(Tokens.R_LG, Tokens::surface1, Tokens::border1));
        if (ask.reasoning != null && !ask.reasoning.isBlank()) {
            bubble.with(collapse("思考过程", ask.thinkOpen, () -> {
                ask.thinkOpen = !ask.thinkOpen;
                renderAsk();
            }, thinkBlock(MdText.area(ask.reasoning, Tokens.FS_NOTE, MdText.Tone.MUTED),
                THINK_MAX)), Tokens.SP2);
        }
        // `.qa__a .qa__actions{margin-top:6;gap:6}`
        bubble.with(row(Tokens.SP2,
            new Btn("复制", Btn.Variant.SECONDARY, Btn.Size.SM)
                .onClick(() -> copy(ask.answer))), Tokens.SP2);
        return bubble;
    }

    private JComponent pendingBubble() {
        long secs = Math.max(0, System.currentTimeMillis() - thinkSince) / 1000;
        liveChip = new Chip(Chip.Kind.ANALYZING, "思考中…（已用 " + secs + "s）");
        Bubble bubble = new Bubble(ANSWER_RATIO, false, liveChip,
            Surface.of(Tokens.R_LG, Tokens::surface1, Tokens::border1));
        if (askThink.length() > 0) {
            liveThink = MdText.area(tail(askThink), Tokens.FS_NOTE, MdText.Tone.MUTED);
            bubble.with(thinkBlock(liveThink, THINK_MAX), Tokens.SP3);
        } else {
            liveThink = null;
        }
        bubble.with(hint("模型思考通常 10–30 秒。"), Tokens.SP2);
        return bubble;
    }

    private JComponent errorBubble(String msg) {
        Bubble bubble = new Bubble(ANSWER_RATIO, false,
            new Pill("回答失败", Pill.Kind.SOLID_DANGER),
            Surface.of(Tokens.R_LG, Tokens::dangerWeak, Tokens::danger));
        bubble.with(MdText.area(msg, Tokens.FS_BODY, MdText.Tone.DANGER), Tokens.SP3);
        return bubble;
    }

    private void scrollTop() {
        SwingUtilities.invokeLater(() -> roundsScroll.getVerticalScrollBar().setValue(0));
    }

    /** FR-PANEL-14: a new exchange is appended at the bottom and shown. */
    private void scrollAskBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollPane sp = askScroll;
            sp.getVerticalScrollBar().setValue(sp.getVerticalScrollBar().getMaximum());
        });
    }

    // ---------------------------------------------------------------- atoms


    /**
     * A vertical gap that shares its siblings' horizontal alignment.
     *
     * `Box.createVerticalStrut` defaults to CENTER alignment, and a `BoxLayout`
     * stack that mixes alignments lays its children out against a compromise line
     * — text ends up indented inside a card that is itself correctly placed. Every
     * gap in this panel therefore goes through here.
     */
    private static Component strut(int height) {
        Component gap = Box.createVerticalStrut(height);
        ((JComponent) gap).setAlignmentX(Component.LEFT_ALIGNMENT);
        return gap;
    }

    private static JLabel label(String text, int style, float size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.font(style, size));
        l.setForeground(color);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    /** `.round__conclusion` — 15/600, one line of user language. */
    private static JLabel headline(String text) {
        return label(text, Font.BOLD, Tokens.FS_SUBTITLE, Theme.tokens().text1());
    }

    /** `.field__hint` — 11px text-3. */
    private static JLabel hint(String text) {
        return label(text, Font.PLAIN, Tokens.FS_CAPTION, Theme.tokens().text3());
    }

    /** 12px text-3: a timestamp's weight, for the lists' own placeholders. */
    private static JLabel note(String text) {
        return label(text, Font.PLAIN, Tokens.FS_NOTE, Theme.tokens().text3());
    }

    /** `.section-label` — 12px text-3; the struts around it are the caller's. */
    private static JLabel sectionLabel(String text) {
        return label(text, Font.PLAIN, Tokens.FS_NOTE, Theme.tokens().text3());
    }

    /** `.round__when` — 12px text-3. */
    private static JLabel timeLabel(long at) {
        return label(STAMP.format(new Date(at)), Font.PLAIN, Tokens.FS_NOTE,
            Theme.tokens().text3());
    }

    /** `.round__divider` — a 1px hairline, `margin: 6px 0` at the call site. */
    private static JComponent divider() {
        JPanel p = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Theme.tokens().border1());
                g.fillRect(0, 0, getWidth(), 1);
            }
        };
        p.setOpaque(false);
        p.setPreferredSize(new Dimension(1, 1));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /**
     * A left-aligned row of small components that wraps onto as many lines as it
     * needs — `.round__tags` (gap 6) and `.reply__actions` (gap 6).
     *
     * Only for a slot that stretches its child: {@link Wrap} reports a preferred
     * width of 1 on purpose (so a row of tags can never widen the column), so in a
     * `BorderLayout` WEST/EAST slot it would be laid out one pixel wide. Use
     * {@link #hrow} there.
     */
    private static JComponent row(int gap, JComponent... children) {
        Wrap wrap = new Wrap(gap, Tokens.SP2).span();
        for (JComponent c : children) {
            if (c != null) wrap.add(c);
        }
        return wrap;
    }

    /** A single-line row for a `BorderLayout` slot, at each child's own size. */
    private static JComponent hrow(int gap, JComponent... children) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        boolean first = true;
        for (JComponent c : children) {
            if (c == null) continue;
            if (!first && gap > 0) p.add(Box.createHorizontalStrut(gap));
            first = false;
            p.add(c);
        }
        return p;
    }

    /**
     * One side of the chat: the bubble takes its own width, the row takes the rest.
     *
     * `BorderLayout` hands WEST and EAST their preferred width and nothing else,
     * which is exactly the `.qa__q{align-self:flex-end}` behaviour wanted here.
     */
    private static JComponent side(JComponent bubble, boolean right) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(bubble, right ? BorderLayout.EAST : BorderLayout.WEST);
        return row;
    }

    /**
     * Hands [c] all the horizontal slack its row has, without touching its height.
     *
     * `BorderLayout` gives its CENTER both, which is right for text and wrong for
     * `.bar` — a 4px track stretched to a 22px row reads as a filled pill.
     * `BoxLayout` cannot help either: it stretches a child to the container's
     * width only if the child's maximum allows. `GridBagLayout` with a horizontal
     * fill and no weight on Y is precisely "grow one way".
     */
    private static JComponent stretch(JComponent c) {
        JPanel holder = new JPanel(new GridBagLayout());
        holder.setOpaque(false);
        holder.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.weightx = 1;
        g.weighty = 0;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 0, 0);
        holder.add(c, g);
        return holder;
    }

    /** The three characters Swing's HTML view would otherwise read as markup. */
    private static String esc(String s) {
        return s == null ? ""
            : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String tail(StringBuilder sb) {
        String s = sb.toString();
        return s.length() <= LIVE_THINK_CHARS ? s : "…" + s.substring(s.length() - LIVE_THINK_CHARS);
    }

    private void copy(String text) {
        if (text == null) return;
        Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection(text), null);
        Toast.show(frame, "已复制");
    }

    // ---------------------------------------------------------------- widgets

    /**
     * A rounded block with a box column inside — the `.reply` and `.think` shape.
     *
     * {@link Card} covers this at 12px radius with 12px padding, which is right
     * for a round and wrong for a reply (8/8) and a thinking box (6/8), and its
     * skin is fixed by its factories. Subclassing {@link Surface} is the whole of
     * the work, so a second kit type is not worth it for two call sites.
     */
    private static class Block extends Surface {

        Block(Surface.Skin skin, int padT, int padL, int padB, int padR) {
            super(skin);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(padT, padL, padB, padR));
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        Block(Surface.Skin skin, int pad) {
            this(skin, pad, pad, pad, pad);
        }

        /** Adds a child with the gap above it that CSS would have called a margin. */
        Block with(JComponent child, int gap) {
            if (gap > 0) add(strut(gap));
            add(child);
            return this;
        }

        @Override
        public Component add(Component comp) {
            if (comp instanceof JComponent jc) jc.setAlignmentX(Component.LEFT_ALIGNMENT);
            return super.add(comp);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /**
     * `.qa__q` / `.qa__a` — one chat bubble.
     *
     * Its width is decided here rather than by its content, because the markdown
     * body reports a preferred width of 1 on purpose (see {@link MdText.Area}):
     * nothing else can tell the bubble how wide to be without also letting a long
     * answer widen the whole column. A question hugs its text up to 84% of the
     * row, an answer takes up to 92%, and both re-measure against the row's
     * current width so a resize re-wraps.
     */
    private static final class Bubble extends Block {

        private final double ratio;
        private final boolean hug;
        private final JComponent body;

        Bubble(double ratio, boolean hug, JComponent body, Surface.Skin skin) {
            super(skin, Tokens.SP3, Tokens.SP4, Tokens.SP3, Tokens.SP4);
            this.ratio = ratio;
            this.hug = hug;
            this.body = body;
            add(body);
        }

        /** The width `.qa__q`/`.qa__a`'s `max-width` allows, padding included. */
        private int cap() {
            Component parent = getParent();
            int row = parent != null && parent.getWidth() > 0
                ? parent.getWidth() : FALLBACK_ROW_WIDTH;
            return Math.max(80, (int) Math.round(row * ratio));
        }

        @Override
        public Dimension getPreferredSize() {
            Insets in = getInsets();
            int inner = Math.max(40, cap() - in.left - in.right);
            int w = hug ? Math.min(inner, body.getPreferredSize().width) : inner;
            // The height is the column's own sum: a bubble can hold more than its
            // text (the reasoning disclosure and the action row live in here too).
            return new Dimension(w + in.left + in.right, super.getPreferredSize().height);
        }
    }

    /**
     * `.think` — surface-1 at 6px, 8px of padding, capped and scrolled.
     *
     * The cap is FR-PANEL-07's 「超长内容限高 240px 内滚动」: a reasoning pass can
     * run to thousands of words and would otherwise push the candidate replies
     * off the bottom of the panel.
     */
    private static JComponent thinkBlock(JComponent text, int maxHeight) {
        Block box = new Block(Surface.subtle(Tokens.R_SM), Tokens.SP3);
        box.add(cappedScroll(text, maxHeight - Tokens.SP3 * 2));
        return box;
    }

    /**
     * A scroll pane whose height is its content's, up to [maxHeight].
     *
     * AS_NEEDED rather than ALWAYS (the main list does the opposite and says
     * why): here the content's height only grows as the viewport narrows, so the
     * bar appearing can never take itself away again — the toggle that makes the
     * list's bar a repaint loop cannot happen.
     */
    private static JScrollPane cappedScroll(JComponent content, int maxHeight) {
        JScrollPane sp = new JScrollPane(content) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(1, Math.max(1,
                    Math.min(maxHeight, content.getPreferredSize().height)));
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        Theme.slimScrollBar(sp);
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        return sp;
    }

    /**
     * `.panel__mark` — the brand glyph at 24px, drawn in `--brand`.
     *
     * Not `Icons.brand`, which is the launcher's gradient tile: the design puts a
     * brand-COLOURED mark here, and the kit's own bubble glyph is the one shape
     * that takes a colour.
     */
    private static final class Mark extends JComponent {

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(24, 24);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Kit.prep(g);
            try {
                Glyphs.sparkle().paint(g2, Math.min(getWidth(), getHeight()),
                    Theme.tokens().brand());
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * A one-line label that truncates with an ellipsis instead of widening the
     * header, and carries the full text as its tooltip (FR-PANEL-01).
     */
    private static final class Ellipsis extends JComponent {

        private final int style;
        private final float size;
        private final boolean muted;
        private String text = "";

        Ellipsis(int style, float size, boolean muted) {
            this.style = style;
            this.size = size;
            this.muted = muted;
            setOpaque(false);
            applyFont();
        }

        void applyFont() {
            setFont(Theme.font(style, size));
            revalidate();
            repaint();
        }

        void setText(String value) {
            this.text = value == null ? "" : value;
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            // A few pixels: the header row's slack goes to the title label, so
            // the relationship capsule keeps its own width.
            return new Dimension(8, muted ? Tokens.LH_CAPTION : Tokens.LH_TITLE);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Kit.prep(g);
            try {
                g2.setFont(getFont());
                g2.setColor(muted ? Theme.tokens().text3() : Theme.tokens().text1());
                java.awt.FontMetrics fm = g2.getFontMetrics();
                String shown = text;
                if (fm.stringWidth(shown) > getWidth()) {
                    while (shown.length() > 1 && fm.stringWidth(shown + "…") > getWidth()) {
                        shown = shown.substring(0, shown.length() - 1);
                    }
                    shown = shown + "…";
                }
                g2.drawString(shown, 0,
                    (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            } finally {
                g2.dispose();
            }
        }
    }
}
