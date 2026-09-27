package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.kb.Relations;
import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * `.rel-select` — the relationship dropdown that sits on a contact card.
 *
 * FR-REL-04 asks for the relationship to be editable where it is read: the
 * knowledge base lists the people, so the fix for "the colleague is being judged
 * as if she were my partner" has to live on her row, not three dialogs away.
 *
 * The two states are deliberately different shapes, not just different colours:
 * an unset relationship is a dashed grey pill reading 「关系：未设置」, a set one is
 * a solid brand pill reading the value. A user glancing at the list can tell
 * which people will fall back to the global default without opening anything.
 *
 * The candidates come from {@link Relations#CANDIDATES} — the same nine the
 * panel's header capsule offers — so the two entry points cannot drift apart.
 */
public class RelSelect extends JComponent {

    private static final int HEIGHT = 22;
    private static final int PAD_X = 6;

    private String value;
    private Consumer<String> listener;

    public RelSelect(String value) {
        this.value = value == null ? "" : value.trim();
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("这个人的关系");
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showMenu();
            }
        });
        ThemeManager.addListener(this::repaint);
    }

    /** The stored relationship; blank means "not set, the default applies". */
    public String value() {
        return value;
    }

    /**
     * Programmatic change. Does NOT fire {@link #onChange} — the listener is for
     * real user picks, so a caller rebinding the component cannot loop back into
     * its own persistence.
     */
    public RelSelect setValue(String v) {
        String next = v == null ? "" : v.trim();
        if (next.equals(value)) return this;
        value = next;
        revalidate();
        repaint();
        return this;
    }

    /** Called with the chosen value; "" means the relationship was cleared. */
    public RelSelect onChange(Consumer<String> listener) {
        this.listener = listener;
        return this;
    }

    // ---------------------------------------------------------------- menu

    private void showMenu() {
        List<Menu.Item> items = new ArrayList<>();
        for (String candidate : Relations.CANDIDATES) {
            boolean active = candidate.equals(value);
            items.add(new Menu.Item(candidate, active ? Glyphs.check() : null, true,
                () -> pick(candidate)));
        }
        items.add(Menu.Item.separator());
        items.add(Menu.Item.of("自定义…", this::custom));
        items.add(Menu.Item.of("清除", () -> pick("")));
        Menu.show(this, items);
    }

    /** Free text on top of the built-in nine (Q-6), in its own small modal. */
    private void custom() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        DialogShell shell = DialogShell.modal(owner, "自定义关系", 400, 220);
        Input input = new Input()
            .placeholder("自定义，例如：前同事")
            .text(Relations.isBuiltIn(value) ? "" : value);
        Field field = new Field("关系", input);
        shell.body().add(DialogShell.pin(field));

        String[] picked = new String[1];
        Btn apply = new Btn("应用", Btn.Variant.PRIMARY);
        apply.onClick(() -> {
            String v = input.value().trim();
            if (v.isEmpty()) {
                // FR-KB-04's spirit: say so next to the field instead of closing.
                field.error("请输入自定义关系");
                input.requestFocusInWindow();
                return;
            }
            picked[0] = v;
            shell.close();
        });
        input.onEnter(() -> apply.doClick());
        shell.footer(new Btn("取消", Btn.Variant.SECONDARY).onClick(shell::close), apply);
        shell.show();

        if (picked[0] != null) pick(picked[0]);
    }

    private void pick(String next) {
        setValue(next);
        if (listener != null) listener.accept(value);
    }

    // -------------------------------------------------------------- drawing

    private String text() {
        return value.isEmpty() ? "关系：未设置" : value;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(Theme.font(Font.PLAIN, Tokens.FS_CAPTION));
        return new Dimension(fm.stringWidth(text()) + PAD_X * 2, HEIGHT);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Kit.prep(g);
        try {
            Tokens t = Theme.tokens();
            boolean empty = value.isEmpty();
            Color bg = empty ? t.surface2() : t.brandWeak();
            Color fg = empty ? t.text3() : t.brand();
            int w = getWidth();
            int h = getHeight();
            Kit.fill(g2, 0, 0, w, h, Tokens.R_SM, bg);
            g2.setColor(t.border1());
            if (empty) {
                g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                    1f, new float[]{3f, 2.5f}, 0f));
            } else {
                g2.setStroke(new BasicStroke(1f));
            }
            g2.drawRoundRect(0, 0, w - 1, h - 1, Tokens.R_SM * 2, Tokens.R_SM * 2);

            g2.setFont(Theme.font(Font.PLAIN, Tokens.FS_CAPTION));
            g2.setColor(fg);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text(), PAD_X, (h + fm.getAscent() - fm.getDescent()) / 2);
        } finally {
            g2.dispose();
        }
    }
}
