package com.jev.probe.desktop.ui;

import com.jev.probe.desktop.kb.Contact;
import com.jev.probe.desktop.kb.KbStore;
import com.jev.probe.desktop.kb.Note;
import com.jev.probe.desktop.ui.kit.Area;
import com.jev.probe.desktop.ui.kit.Btn;
import com.jev.probe.desktop.ui.kit.DialogShell;
import com.jev.probe.desktop.ui.kit.EmptyState;
import com.jev.probe.desktop.ui.kit.Field;
import com.jev.probe.desktop.ui.kit.Glyphs;
import com.jev.probe.desktop.ui.kit.Input;
import com.jev.probe.desktop.ui.kit.Kit;
import com.jev.probe.desktop.ui.kit.Pill;
import com.jev.probe.desktop.ui.kit.RelSelect;
import com.jev.probe.desktop.ui.kit.Segmented;
import com.jev.probe.desktop.ui.kit.Switch;
import com.jev.probe.desktop.ui.kit.Toast;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static java.awt.Component.CENTER_ALIGNMENT;
import static java.awt.Component.LEFT_ALIGNMENT;

/**
 * 知识库与联系人 — the `.kb` modal: hand-written notes on one tab, the people they
 * belong to on the other.
 *
 * <p>The window is transcribed from `openKb()` in `design/demo/assets/js/app.js`,
 * which is the authority for the DOM order and for every measurement:
 *
 * <pre>
 *   dialog__head   「知识库与联系人」 + 关闭 X            (DialogShell)
 *   dialog__body   padding 16/20, scrolling
 *     kb__toolbar  seg 笔记|联系人 (200px) + 搜索 (flex:1) + 新建   gap 8, mb 12
 *     kb__list     卡片列, gap 6
 *   dialog__foot   左侧 text-3 提示 + 右侧 关闭            (DialogShell)
 * </pre>
 *
 * <p>Two things about that structure are worth stating because they are what the
 * old build got wrong:
 *
 * <ul>
 *   <li>the toolbar is INSIDE the scrolling body, so it scrolls away with the
 *       list — which is why switching tabs has to send the viewport back to the
 *       top (see {@link #resetScroll()});</li>
 *   <li>`FlowLayout` cannot express `gap: 6px` on a card row: it also insets its
 *       children by the horizontal gap, so a "6px gap" would have indented the
 *       card title 6px inside its own card and left a matching notch on the
 *       right. Every row here is a {@code BoxLayout} with explicit struts
 *       instead, so the gaps land where the CSS puts them.</li>
 * </ul>
 *
 * <p>The relationship select on a contact card is the point of the 760px:
 * FR-REL-04 wants the relationship editable where it is read, and it persists
 * straight to {@link KbStore} so the panel's header capsule can refresh without
 * this dialog being reopened.
 *
 * <p>Everything writes through to the store and then re-renders the list; the
 * lists are short and a rebuild cannot drift out of sync with the two tabs and
 * their empty states.
 */
public final class KnowledgeDialog {

    /** `.kb { width: 760px; height: 640px }`. */
    private static final int WIDTH = 760;
    private static final int HEIGHT = 640;

    /** The prototype gives the segmented control an inline `width: 200px`. */
    private static final int TAB_WIDTH = 200;

    /**
     * FR-KB-02 allows 200ms; 150ms keeps typing responsive while a multi-character
     * Chinese word is still being composed.
     */
    private static final int SEARCH_DELAY_MS = 150;

    /** `.kb-card__title { margin-bottom: 2px }` — title to the meta line under it. */
    private static final int TITLE_GAP = 2;

    /** Wrap widths for the card body text, allowing for the card's action column. */
    private static final int NOTE_WRAP = 580;
    private static final int CONTACT_WRAP = 460;

    private final DialogShell shell;
    private final Runnable onRelationsChanged;
    private final Segmented tabs = new Segmented("笔记", "联系人").fixedWidth(TAB_WIDTH);
    private final Input search = new Input().placeholder("搜索标题、正文、别名、备注…");
    private final Btn createBtn = new Btn("新建", Btn.Variant.PRIMARY);
    /**
     * Not in the prototype's toolbar, which only has 新建: bulk pasting a block of
     * notes is an existing capability of this dialog and dropping it to save a
     * button would be a behaviour loss, not a cleanup.
     */
    private final Btn importBtn = new Btn("从文本导入", Btn.Variant.SECONDARY, Btn.Size.SM);
    private final JPanel listBox = Theme.column();
    private final Timer searchTimer = new Timer(SEARCH_DELAY_MS, null);
    /**
     * The scroll pane `DialogShell` wrapped around the body. Held here because the
     * tab switch has to reset it, and the shell exposes only the body — the
     * ancestor is resolved once, in {@link #build()}.
     */
    private JScrollPane scroll;
    /** 0 = notes, 1 = contacts. */
    private int tab;

    private KnowledgeDialog(Window owner, int tab, Runnable onRelationsChanged) {
        this.tab = tab == 1 ? 1 : 0;
        this.onRelationsChanged = onRelationsChanged;
        this.shell = DialogShell.modal(owner, "知识库与联系人", WIDTH, HEIGHT)
            .footNote("笔记会被注入分析；联系人用来跨会话认人。");
        build();
        shell.footer(new Btn("关闭", Btn.Variant.SECONDARY).onClick(shell::close));
        shell.onClose(() -> {
            searchTimer.stop();
            shell.dialog().dispose();
        });
    }

    // ------------------------------------------------------------ entry points

    /** Open the dialog and block until it is dismissed. */
    public static void show(Window owner) {
        show(owner, 0, null);
    }

    /** @param initialTab 0 = 笔记, 1 = 联系人. */
    public static void show(Window owner, int initialTab) {
        show(owner, initialTab, null);
    }

    /**
     * @param onRelationsChanged run after any relationship edit, so the panel's
     *        header capsule can refresh while this dialog is still open
     *        (FR-REL-04).
     */
    public static void show(Window owner, Runnable onRelationsChanged) {
        show(owner, 0, onRelationsChanged);
    }

    /** @param initialTab 0 = 笔记, 1 = 联系人. */
    public static void show(Window owner, int initialTab, Runnable onRelationsChanged) {
        new KnowledgeDialog(owner, initialTab, onRelationsChanged).shell.show();
    }

    // ------------------------------------------------------------------ build

    /**
     * `dialog__body` with the prototype's own `padding:16px 20px`, then the
     * toolbar and the list.
     *
     * The list is NOT in a scroll pane of its own: the prototype scrolls the whole
     * body, toolbar included, and a nested scroller would put a second scrollbar
     * inside a 640px window and stop the list from growing past it.
     */
    private void build() {
        shell.body().setBorder(BorderFactory.createEmptyBorder(
            Tokens.SP5, Tokens.SP6, Tokens.SP5, Tokens.SP6));
        // Resolved here rather than lazily: at this point the shell has already
        // wrapped the body, so the ancestor is exactly the one to reset.
        scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, shell.body());

        tabs.setSelected(tab);
        tabs.onChange(this::switchTab);
        // FR-KB-02: filter on a keystroke, not on a committed search.
        searchTimer.setRepeats(false);
        searchTimer.addActionListener(e -> render());
        search.onChange(searchTimer::restart);
        createBtn.onClick(this::create);
        importBtn.onClick(this::importNotes);
        importBtn.setVisible(tab == 0);

        shell.body().add(toolbar());
        // `.kb__toolbar { margin-bottom: var(--sp-4) }`.
        shell.body().add(DialogShell.gap(Tokens.SP4));
        shell.body().add(listBox);
        shell.body().add(Box.createVerticalGlue());
        render();
    }

    /**
     * `.kb__toolbar` — `seg` (200px) · 搜索 (flex:1) · 新建, 8px apart.
     *
     * The button pair is wrapped in its own row so BorderLayout keeps them at
     * their own 28/32px heights inside a 34px toolbar, instead of stretching them
     * to the tools beside them.
     */
    private JComponent toolbar() {
        JPanel row = new JPanel(new BorderLayout(Tokens.SP3, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.add(tabs, BorderLayout.WEST);
        row.add(search, BorderLayout.CENTER);
        row.add(hbox(Tokens.SP2, importBtn, createBtn), BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Input.HEIGHT));
        return row;
    }

    /**
     * `.seg` went from 笔记 to 联系人: rebuild, then put the reader back at the
     * top.
     *
     * Both the tab and the scroll position are part of "which list am I looking
     * at", and only the tab was being switched. Coming from halfway down a long
     * note list, 联系人 opened with the toolbar off-screen and its own first card
     * already scrolled past — the reader's first impression of the tab was its
     * middle, which reads as a blank pane with content floating in it.
     *
     * `revalidate()` is needed as well because hiding 从文本导入 changes the
     * toolbar's preferred width, and BorderLayout does not re-place the EAST
     * child until the container is laid out again.
     */
    private void switchTab(int index) {
        tab = index;
        importBtn.setVisible(index == 0);
        shell.body().revalidate();
        render();
        resetScroll();
    }

    /**
     * Puts the viewport back at the top of the tab that just became visible.
     *
     * Issued twice on purpose, exactly as the settings pages do it: the rebuild
     * revalidates the body, and a position set before that layout pass has run can
     * be clamped against the previous list's extent, leaving the new one part-way
     * down. The deferred call re-applies it once the swap has settled.
     *
     * Deliberately NOT called from {@link #render()}: editing a relationship on a
     * card far down the list persists and re-renders, and yanking the reader to
     * the top there would lose their place for no reason.
     */
    private void resetScroll() {
        if (scroll == null) return;
        scroll.getViewport().setViewPosition(new Point(0, 0));
        SwingUtilities.invokeLater(
            () -> scroll.getViewport().setViewPosition(new Point(0, 0)));
    }

    // --------------------------------------------------------------- rendering

    /** Rebuilds the list only, so the search box keeps focus while it filters. */
    private void render() {
        String q = search.value().trim().toLowerCase(Locale.ROOT);
        listBox.removeAll();
        if (tab == 0) renderNotes(q);
        else renderContacts(q);
        // The list is followed by a vertical glue, so it has to keep reporting
        // exactly the height its cards need or the glue would start above them.
        listBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, listBox.getPreferredSize().height));
        listBox.revalidate();
        listBox.repaint();
    }

    private void renderNotes(String q) {
        List<Note> hits = new ArrayList<>();
        for (Note n : KbStore.get().notes()) {
            if (q.isEmpty() || matches(n, q)) hits.add(n);
        }
        if (hits.isEmpty()) {
            // FR-KB-05: the prototype's copy, for "no notes" and "no match" alike.
            listBox.add(new EmptyState(Glyphs.sparkle(), "还没有笔记",
                "写点该记住的事实：习惯、忌口、项目代号、约定过的时间。"));
            return;
        }
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) listBox.add(DialogShell.gap(Tokens.SP2));   // `.kb__list { gap: 6px }`
            listBox.add(noteCard(hits.get(i)));
        }
    }

    private void renderContacts(String q) {
        List<Contact> hits = new ArrayList<>();
        for (Contact c : KbStore.get().contacts()) {
            if (q.isEmpty() || matches(c, q)) hits.add(c);
        }
        if (hits.isEmpty()) {
            listBox.add(new EmptyState(Glyphs.sparkle(), "还没有联系人",
                "也可以在面板的「⋯」菜单里点「存为联系人」，把当前会话存进来。"));
            return;
        }
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) listBox.add(DialogShell.gap(Tokens.SP2));
            listBox.add(contactCard(hits.get(i)));
        }
    }

    // ------------------------------------------------------------------- cards

    /** `.kb-card` for a note: title (+ 常驻) / body / tags | switch, 删除. */
    private JComponent noteCard(Note n) {
        KbCard card = new KbCard();
        JPanel main = Theme.column();

        main.add(hbox(Tokens.SP2,
            label(or(n.title(), "（无标题）"), Tokens.FS_BODY, Font.BOLD, Tokens::text1),
            n.alwaysOn() ? Pill.brand("常驻") : null));
        main.add(DialogShell.gap(TITLE_GAP));

        if (!n.content().isBlank()) {
            main.add(pin(wrapped(n.content(), NOTE_WRAP, Tokens.FS_NOTE, Tokens::text2)));
        }
        if (!n.tags().isEmpty()) {
            main.add(DialogShell.gap(Tokens.SP2));   // `.kb-card__tags { margin-top: 6px }`
            main.add(tagsRow(n.tags()));
        }
        card.add(main, BorderLayout.CENTER);

        Switch enabled = new Switch(n.enabled());
        enabled.setToolTipText("启用/停用");
        enabled.onChange(on -> KbStore.get().saveNote(new Note(
            n.id(), n.title(), n.content(), n.tags(), n.alwaysOn(), on, n.updatedAt())));

        Btn delete = new Btn("删除", Btn.Variant.DANGER, Btn.Size.SM);
        delete.onClick(() -> deleteNote(n));

        card.add(hbox(Tokens.SP2, enabled, delete), BorderLayout.EAST);
        clickable(card, () -> editNote(n));
        return card;
    }

    /**
     * `.kb-card` for a contact: 名字 + 关系下拉 (+ 回落默认) / 来源 · 历史 / 备注 |
     * 清空历史, 删除.
     */
    private JComponent contactCard(Contact c) {
        KbCard card = new KbCard();
        JPanel main = Theme.column();

        RelSelect rel = new RelSelect(c.relationship());
        rel.onChange(v -> applyRelationship(c, v));

        main.add(hbox(Tokens.SP2,
            label(or(c.name(), "（无名）"), Tokens.FS_BODY, Font.BOLD, Tokens::text1),
            rel,
            // The value in force comes from 设置 → 默认关系; say so (FR-REL-06).
            c.relationship().isBlank() ? new Pill("回落默认", Pill.Kind.OUTLINE_WARNING) : null));
        main.add(DialogShell.gap(TITLE_GAP));

        int lines = KbStore.get().logSize(c.id());
        main.add(pin(label(meta(c, lines), Tokens.FS_NOTE, Font.PLAIN, Tokens::text2)));
        main.add(pin(c.notes().isBlank()
            ? label("暂无备注", Tokens.FS_NOTE, Font.PLAIN, Tokens::text3)
            : wrapped("备注：" + c.notes(), CONTACT_WRAP, Tokens.FS_NOTE, Tokens::text2)));
        card.add(main, BorderLayout.CENTER);

        Btn clear = new Btn("清空历史（" + lines + " 条）", Btn.Variant.SECONDARY, Btn.Size.SM);
        clear.onClick(() -> clearLog(c));
        Btn delete = new Btn("删除", Btn.Variant.DANGER, Btn.Size.SM);
        delete.onClick(() -> deleteContact(c));

        card.add(hbox(Tokens.SP2, clear, delete), BorderLayout.EAST);
        clickable(card, () -> editContact(c));
        return card;
    }

    /**
     * The card's one `.kb-card__meta` line: `别名：… · 来源：… · 历史 N 条`.
     *
     * The prototype prints the segments unconditionally, which is right for its
     * mock data but not for a contact the user typed in by hand: that one has no
     * aliases and no apps, and 「来源： · 历史 0 条」 reads as a rendering bug. The
     * empty segments are dropped instead — every populated field is still in the
     * order the prototype puts them in.
     */
    private static String meta(Contact c, int lines) {
        StringBuilder sb = new StringBuilder();
        String aliases = String.join("、", c.aliases());
        String apps = String.join("、", c.apps());
        if (!aliases.isBlank()) sb.append("别名：").append(aliases).append(" · ");
        if (!apps.isBlank()) sb.append("来源：").append(apps).append(" · ");
        return sb.append("历史 ").append(lines).append(" 条").toString();
    }

    /**
     * `.kb-card__tags` — one line of pills, 4px apart.
     *
     * Deliberately not the design system's wrapping row: `.kb-card__tags` has no
     * `flex-wrap`, so the reference shows one line, and a wrapping row's height is
     * only known after a deferred re-measure — which would leave the list's own
     * maximum height, computed from the preferred size right here, one line too
     * tall until the next render. A note with more tags than the card is wide
     * clips the overflow, exactly as the browser clip does.
     */
    private static JComponent tagsRow(List<String> tags) {
        JComponent[] pills = new JComponent[tags.size()];
        for (int i = 0; i < tags.size(); i++) pills[i] = Pill.neutral(tags.get(i));
        return hbox(Tokens.SP1, pills);
    }

    /**
     * `.kb-card`: a hairline row that lifts on hover and opens its editor when the
     * card body is clicked.
     *
     * The maximum size is derived from the preferred size rather than captured
     * once, so the card always reports exactly the height its wrapped text needs —
     * including after a font-size change.
     */
    private static final class KbCard extends JPanel {

        KbCard() {
            setOpaque(false);
            // `gap: 8px` between the body text and the action column, the same
            // token the CSS uses for the card's own flex gap.
            setLayout(new BorderLayout(Tokens.SP3, Tokens.SP2));
            // `padding: 8px 12px`.
            setBorder(BorderFactory.createEmptyBorder(
                Tokens.SP3, Tokens.SP4, Tokens.SP3, Tokens.SP4));
            setAlignmentX(LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    repaint();
                }
            });
            ThemeManager.addListener(this::repaint);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Kit.prep(g);
            try {
                Tokens t = Theme.tokens();
                // `getMousePosition(true)` counts this panel's children, so moving
                // onto the switch or the relation select keeps the hover state.
                boolean over = getMousePosition(true) != null;
                Kit.roundRect(g2, 0, 0, getWidth(), getHeight(), Tokens.R_MD,
                    over ? t.surface1() : t.surface0(), over ? t.border2() : t.border1());
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * Makes the whole card open its editor without letting the controls on it do
     * the same (FR-KB-03).
     *
     * Swing delivers a mouse event only to the deepest component, so there is no
     * bubbling to filter: the listener is attached to the card and to every
     * non-interactive descendant, and never to a button, switch or relation
     * select — those own their clicks.
     */
    private static void clickable(Component c, Runnable open) {
        if (c instanceof Btn || c instanceof Switch || c instanceof RelSelect) return;
        c.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                open.run();
            }
        });
        if (c instanceof JComponent jc) {
            jc.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) clickable(child, open);
        }
    }

    // ------------------------------------------------------------------ atoms

    /**
     * A left-aligned row whose children sit exactly [gap] apart — the shape every
     * `.kb-card__*` row has.
     *
     * `FlowLayout` is the obvious tool for this and is wrong twice over: it adds
     * its horizontal gap on the OUTSIDE as well, so `new FlowLayout(LEFT, 6, 0)`
     * indents the row 6px into its own card and leaves a matching notch on the
     * right, and it reports one line's height no matter what.
     *
     * BoxLayout with explicit struts puts the gaps where the CSS puts them, but
     * only if every child is wrapped in {@link #fixed}: BoxLayout hands spare width
     * out in proportion to how far a child can grow, and both {@link Lbl} and
     * {@link Btn} report a maximum width of two billion so that they can span a
     * column. Left bare in a row, the label would swallow the entire remainder and
     * push the 常驻 pill to the far edge of the card instead of leaving it beside
     * the title, where the prototype has it.
     *
     * A null item is skipped, which is how an optional pill is omitted without a
     * second row builder.
     */
    private static JPanel hbox(int gap, JComponent... items) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        boolean first = true;
        for (JComponent item : items) {
            if (item == null) continue;
            if (!first) row.add(Box.createHorizontalStrut(gap));
            first = false;
            row.add(fixed(item));
        }
        row.add(Box.createHorizontalGlue());
        return pin(row);
    }

    /** [c] at exactly its preferred size, reporting no room to grow. */
    private static JComponent fixed(JComponent c) {
        JPanel holder = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return getPreferredSize();
            }

            @Override
            public Dimension getMinimumSize() {
                return getPreferredSize();
            }
        };
        holder.setOpaque(false);
        // The cross-axis position: `align-items: center`, which is what keeps a
        // 22px pill and a 20px label on one middle line.
        holder.setAlignmentY(CENTER_ALIGNMENT);
        holder.add(c, BorderLayout.CENTER);
        return holder;
    }

    /** `.card__row`-style label-left / switch-right line. */
    private static JComponent switchRow(String text, Switch sw) {
        JPanel row = new JPanel(new BorderLayout(Tokens.SP3, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE,
            Math.max(24, sw.getPreferredSize().height)));
        row.add(label(text, Tokens.FS_BODY, Font.PLAIN, Tokens::text1), BorderLayout.CENTER);
        row.add(hbox(0, sw), BorderLayout.EAST);
        return row;
    }

    private static void pad(DialogShell d) {
        d.body().setBorder(BorderFactory.createEmptyBorder(
            Tokens.SP5, Tokens.SP5, Tokens.SP5, Tokens.SP5));
    }

    /** A left-aligned label that never stretches in a box column. */
    private static JLabel label(String text, float size, int style, Function<Tokens, Color> color) {
        return new Lbl(text, 0, size, style, color);
    }

    /** The same, wrapped to a fixed width — card bodies and long notes. */
    private static JLabel wrapped(String text, int width, float size, Function<Tokens, Color> color) {
        return new Lbl(text, width, size, Font.PLAIN, color);
    }

    private static final class Lbl extends JLabel {

        private final float size;
        private final int style;
        private final Function<Tokens, Color> color;
        private final String raw;
        /** 0 means a plain single-line label; otherwise the wrap width in px. */
        private final int wrapWidth;

        Lbl(String text, int wrapWidth, float size, int style, Function<Tokens, Color> color) {
            super(text == null ? "" : text);
            this.raw = text == null ? "" : text;
            this.wrapWidth = wrapWidth;
            this.size = size;
            this.style = style;
            this.color = color;
            setAlignmentX(LEFT_ALIGNMENT);
            restyle();
            ThemeManager.addListener(this::restyle);
        }

        private void restyle() {
            setFont(Theme.font(style, size));
            setForeground(color.apply(Theme.tokens()));
            setText(wrapWidth > 0
                ? "<html><div style='width:" + wrapWidth + "px'>" + escape(raw) + "</div></html>"
                : raw);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** Keeps a box-layout child at the height its content asks for. */
    private static <T extends JComponent> T pin(T c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    // -------------------------------------------------------------- mutations

    /** FR-REL-04: persist on the contact, then let the panel refresh its capsule. */
    private void applyRelationship(Contact c, String value) {
        KbStore.get().saveContact(c.withRelationship(value));
        Toast.show(shell.dialog(), value.isEmpty()
            ? "已清除「" + or(c.name(), "（无名）") + "」的关系，将回落默认"
            : "已把「" + or(c.name(), "（无名）") + "」的关系设为「" + value + "」");
        notifyRelations();
        render();
    }

    private void create() {
        if (tab == 0) editNote(null);
        else editContact(null);
    }

    private void deleteNote(Note n) {
        if (!Confirm.ask(shell.dialog(), "删除笔记",
            "删除「" + or(n.title(), "（无标题）") + "」？不可恢复。",
            "删除", Confirm.Kind.DANGER)) {
            return;
        }
        KbStore.get().deleteNote(n.id());
        render();
    }

    private void deleteContact(Contact c) {
        if (!Confirm.ask(shell.dialog(), "删除联系人",
            "删除「" + or(c.name(), "（无名）") + "」及其全部历史？不可恢复。",
            "删除", Confirm.Kind.DANGER)) {
            return;
        }
        KbStore.get().deleteContact(c.id());
        notifyRelations();
        render();
    }

    private void clearLog(Contact c) {
        int lines = KbStore.get().logSize(c.id());
        if (lines == 0) {
            Toast.show(shell.dialog(), "本来就没有历史。");
            return;
        }
        if (!Confirm.ask(shell.dialog(), "清空历史",
            "删掉「" + or(c.name(), "（无名）") + "」的 " + lines
                + " 条聊天历史？联系人档案保留。",
            "清空", Confirm.Kind.DANGER)) {
            return;
        }
        KbStore.get().clearLog(c.id());
        render();
    }

    private void notifyRelations() {
        if (onRelationsChanged != null) onRelationsChanged.run();
    }

    // ---------------------------------------------------------------- editors

    private void editNote(Note existing) {
        DialogShell d = DialogShell.modal(shell.dialog(),
            existing == null ? "新建笔记" : "编辑笔记", 560, 520);
        pad(d);

        Input title = new Input()
            .text(existing == null ? "" : existing.title())
            .placeholder("例如：她最近在备考，别催");
        Area content = new Area(6, 30).text(existing == null ? "" : existing.content());
        content.fixedHeight(120);
        content.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        Input tags = new Input()
            .text(existing == null ? "" : String.join("、", existing.tags()))
            .placeholder("用逗号或空格分隔");
        Switch alwaysOn = new Switch(existing != null && existing.alwaysOn());
        Switch enabled = new Switch(existing == null || existing.enabled());

        Field titleField = new Field("标题", title);
        Btn save = new Btn("保存", Btn.Variant.PRIMARY);
        Runnable commit = () -> {
            if (title.value().isBlank() && content.value().isBlank()) {
                // FR-KB-04: an empty note is not storable — say so inline and stay.
                titleField.error("标题和正文不能都空着");
                return;
            }
            KbStore.get().saveNote(new Note(
                existing == null ? KbStore.newId() : existing.id(),
                title.value().trim(),
                content.value().trim(),
                splitTags(tags.value()),
                alwaysOn.isOn(),
                enabled.isOn(),
                System.currentTimeMillis()));
            d.close();
            render();
        };
        save.onClick(commit);
        title.onEnter(save::doClick);
        tags.onEnter(save::doClick);

        d.body().add(pin(titleField));
        d.body().add(DialogShell.gap(Tokens.SP3));
        d.body().add(pin(new Field("正文", content)));
        d.body().add(DialogShell.gap(Tokens.SP3));
        d.body().add(pin(new Field("标签", tags)));
        d.body().add(DialogShell.gap(Tokens.SP4));
        d.body().add(pin(switchRow("常驻注入（每次分析都带上）", alwaysOn)));
        d.body().add(DialogShell.gap(Tokens.SP3));
        d.body().add(pin(switchRow("启用", enabled)));
        d.body().add(Box.createVerticalGlue());
        d.footer(new Btn("取消", Btn.Variant.SECONDARY).onClick(d::close), save);
        d.show();
    }

    private void editContact(Contact existing) {
        DialogShell d = DialogShell.modal(shell.dialog(),
            existing == null ? "新建联系人" : "编辑联系人", 560, 460);
        pad(d);

        Input name = new Input()
            .text(existing == null ? "" : existing.name())
            .placeholder("会话里显示的名字");
        Input aliases = new Input()
            .text(existing == null ? "" : String.join("、", existing.aliases()))
            .placeholder("微信 / QQ / 飞书里的其他叫法");
        Area notes = new Area(4, 30).text(existing == null ? "" : existing.notes());
        notes.fixedHeight(100);
        notes.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));

        Field nameField = new Field("名字", name);
        Btn save = new Btn("保存", Btn.Variant.PRIMARY);
        Runnable commit = () -> {
            if (name.value().isBlank()) {
                nameField.error("名字不能空");
                return;
            }
            // The relationship is maintained by the select on the card, and apps /
            // autoSummary have no field here: both are carried over so a rename
            // cannot silently break app matching or drop the relationship.
            KbStore.get().saveContact(new Contact(
                existing == null ? KbStore.newId() : existing.id(),
                name.value().trim(),
                splitTags(aliases.value()),
                existing == null ? List.of() : existing.apps(),
                existing == null ? "" : existing.relationship(),
                notes.value().trim(),
                existing == null ? "" : existing.autoSummary(),
                System.currentTimeMillis()));
            d.close();
            render();
            notifyRelations();
        };
        save.onClick(commit);
        name.onEnter(save::doClick);

        d.body().add(pin(nameField));
        d.body().add(DialogShell.gap(Tokens.SP3));
        d.body().add(pin(new Field("别名", aliases).hint("用「、」或逗号分隔，同一个人的不同会话名")));
        d.body().add(DialogShell.gap(Tokens.SP3));
        d.body().add(pin(new Field("备注", notes)));
        d.body().add(Box.createVerticalGlue());
        d.footer(new Btn("取消", Btn.Variant.SECONDARY).onClick(d::close), save);
        d.show();
    }

    /**
     * Paste-a-block import, kept from the old dialog: paragraphs separated by
     * blank lines, each paragraph's first line becoming the title.
     */
    private void importNotes() {
        DialogShell d = DialogShell.modal(shell.dialog(), "从文本导入", 560, 440);
        pad(d);

        Area text = new Area(12, 30);
        text.fixedHeight(220);
        text.setMaximumSize(new Dimension(Integer.MAX_VALUE, 220));
        Field field = new Field("内容", text).hint("按空行分段，每段第一行当标题，其余当正文。");

        Btn run = new Btn("导入", Btn.Variant.PRIMARY);
        run.onClick(() -> {
            List<String[]> parsed = parseParagraphs(text.value());
            if (parsed.isEmpty()) {
                field.error("没解析出内容");
                return;
            }
            for (String[] p : parsed) {
                KbStore.get().saveNote(new Note(
                    KbStore.newId(), p[0], p[1], List.of(), false, true,
                    System.currentTimeMillis()));
            }
            d.close();
            render();
            Toast.show(shell.dialog(), "已导入 " + parsed.size() + " 条");
        });

        d.body().add(pin(field));
        d.body().add(Box.createVerticalGlue());
        d.footer(new Btn("取消", Btn.Variant.SECONDARY).onClick(d::close), run);
        d.show();
    }

    // ----------------------------------------------------------------- search

    private static boolean matches(Note n, String q) {
        return (n.title() + n.content() + String.join("", n.tags()))
            .toLowerCase(Locale.ROOT).contains(q);
    }

    private static boolean matches(Contact c, String q) {
        return (c.name() + String.join("、", c.aliases()) + c.relationship() + c.notes())
            .toLowerCase(Locale.ROOT).contains(q);
    }

    // ---------------------------------------------------------------- helpers

    private static String or(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    /** Tags are accepted with commas, 顿号 or whitespace between them. */
    private static List<String> splitTags(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split("[,，、\\s]+")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Blank-line separated paragraphs → {title, body} pairs. */
    private static List<String[]> parseParagraphs(String raw) {
        List<String[]> out = new ArrayList<>();
        for (String block : raw.replace("\r\n", "\n").split("\n\\s*\n")) {
            String[] lines = block.strip().split("\n", 2);
            if (lines.length == 0 || lines[0].isBlank()) continue;
            out.add(new String[]{lines[0].trim(), lines.length > 1 ? lines[1].trim() : ""});
        }
        return out;
    }
}
