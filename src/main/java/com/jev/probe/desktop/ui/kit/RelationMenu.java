package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.kb.Relations;
import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * `.rel-menu` — the in-place relationship editor behind the panel's relation
 * capsule.
 *
 * FR-REL-03 asks for the change to happen where the user noticed the problem:
 * click the pill, pick from the built-in candidates or type a custom value, and
 * the analysis uses it from the next round on. The menu therefore also carries
 * 「清除（回落默认关系）」 and a route into the knowledge base for the fuller edit
 * (aliases, notes, history).
 *
 * A contact must exist before a relationship can be stored — it lives on the
 * contact (FR-REL-01) — so with no recognised contact the menu offers the
 * 「存为联系人」 route instead of silently accepting a value it has nowhere to put.
 */
public final class RelationMenu {

    private RelationMenu() {
    }

    /**
     * @param sessionTitle the conversation title, shown in the heading.
     * @param current the relationship in force now, or blank.
     * @param hasContact whether a contact exists to store it on.
     * @param onApply called with the chosen value once it has been stored.
     * @param onClear called when the user clears the relationship.
     * @param onOpenContacts called for 「在联系人里编辑 →」.
     * @param onSaveContact called when there is no contact yet and the user picks
     *        a value — the caller creates the contact and then applies it.
     */
    public static void show(JComponent invoker,
                            String sessionTitle,
                            String current,
                            boolean hasContact,
                            Consumer<String> onApply,
                            Runnable onClear,
                            Runnable onOpenContacts,
                            Consumer<String> onSaveContact) {
        Window owner = SwingUtilities.getWindowAncestor(invoker);
        if (owner == null) return;

        JWindow popup = popup(owner);
        Surface shell = new Surface((g2, w, h, t) -> {
            Kit.fill(g2, 0, 0, w, h, Tokens.R_LG, t.surface1());
            Kit.stroke(g2, 0, 0, w, h, Tokens.R_LG, t.border1());
        });
        shell.setLayout(new BoxLayout(shell, BoxLayout.Y_AXIS));
        shell.setBorder(BorderFactory.createEmptyBorder(Tokens.SP3, Tokens.SP3, Tokens.SP3, Tokens.SP3));
        shell.setWindowSurface(true);

        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SP2, 0));
        head.setOpaque(false);
        head.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        JLabel title = bold("与「" + sessionTitle + "」的关系", Tokens.FS_NOTE);
        JLabel note = new JLabel("只影响这个会话人");
        note.setFont(Theme.font(Font.PLAIN, Tokens.FS_CAPTION));
        note.setForeground(Theme.tokens().text3());
        head.add(title);
        head.add(note);
        shell.add(head);
        shell.add(javax.swing.Box.createVerticalStrut(Tokens.SP2));

        // The three-column candidate grid.
        JPanel grid = new JPanel(new GridLayout(0, 3, Tokens.SP2, Tokens.SP2));
        grid.setOpaque(false);
        grid.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        for (String candidate : Relations.CANDIDATES) {
            boolean active = candidate.equals(current);
            grid.add(new Candidate(candidate, active, () -> {
                popup.dispose();
                pick(candidate, hasContact, onApply, onSaveContact);
            }));
        }
        shell.add(grid);
        shell.add(javax.swing.Box.createVerticalStrut(Tokens.SP3));

        Input custom = new Input().placeholder("自定义，例如：前同事").text(
            Relations.isBuiltIn(current) ? "" : current);
        Btn apply = new Btn("应用", Btn.Variant.PRIMARY, Btn.Size.SM);
        apply.onClick(() -> {
            String v = custom.value().trim();
            if (v.isEmpty()) {
                Toast.show(owner, "请输入自定义关系");
                return;
            }
            popup.dispose();
            pick(v, hasContact, onApply, onSaveContact);
        });
        custom.onEnter(() -> apply.doClick());
        JPanel customRow = new JPanel(new BorderLayout(Tokens.SP2, 0));
        customRow.setOpaque(false);
        customRow.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        customRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, Input.HEIGHT));
        customRow.add(custom, BorderLayout.CENTER);
        customRow.add(apply, BorderLayout.EAST);
        shell.add(customRow);
        shell.add(javax.swing.Box.createVerticalStrut(Tokens.SP2));

        Btn clear = new Btn("清除（回落默认关系）", Btn.Variant.GHOST, Btn.Size.SM);
        clear.onClick(() -> {
            popup.dispose();
            if (onClear != null) onClear.run();
        });
        Btn contacts = new Btn("在联系人里编辑 →", Btn.Variant.GHOST, Btn.Size.SM);
        contacts.onClick(() -> {
            popup.dispose();
            if (onOpenContacts != null) onOpenContacts.run();
        });
        JPanel foot = new JPanel(new BorderLayout());
        foot.setOpaque(false);
        foot.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        foot.add(clear, BorderLayout.WEST);
        foot.add(contacts, BorderLayout.EAST);
        shell.add(foot);

        popup.setContentPane(shell);
        popup.pack();
        popup.setSize(Math.max(268, popup.getWidth()), popup.getHeight());
        popup.setLocation(invoker.getLocationOnScreen().x, 
            invoker.getLocationOnScreen().y + invoker.getHeight() + Tokens.SP1);
        dismissOnFocusLoss(popup);
        popup.setVisible(true);
        popup.toFront();
        popup.requestFocus();
    }

    private static void pick(String value, boolean hasContact,
                             Consumer<String> onApply, Consumer<String> onSaveContact) {
        if (!hasContact && onSaveContact != null) {
            onSaveContact.accept(value);
        } else if (onApply != null) {
            onApply.accept(value);
        }
    }

    private static JWindow popup(Window owner) {
        JWindow popup = new JWindow(owner);
        try {
            popup.setBackground(new Color(0, 0, 0, 0));
        } catch (Exception e) {
            popup.setBackground(Theme.tokens().surface1());
        }
        popup.setFocusableWindowState(true);
        popup.setAlwaysOnTop(true);
        popup.setLayout(new BorderLayout());
        return popup;
    }

    private static void dismissOnFocusLoss(JWindow popup) {
        popup.addWindowFocusListener(new java.awt.event.WindowFocusListener() {
            @Override
            public void windowGainedFocus(java.awt.event.WindowEvent e) {
            }

            @Override
            public void windowLostFocus(java.awt.event.WindowEvent e) {
                popup.dispose();
            }
        });
    }

    private static JLabel bold(String text, float size) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.font(Font.BOLD, size));
        l.setForeground(Theme.tokens().text1());
        return l;
    }

    /** One `.rel-menu__item`. */
    private static final class Candidate extends JComponent {

        private final String value;
        private final boolean active;
        private final Runnable action;

        Candidate(String value, boolean active, Runnable action) {
            this.value = value;
            this.active = active;
            this.action = action;
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(78, 26));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    action.run();
                }

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
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Kit.prep(g);
            try {
                Tokens t = Theme.tokens();
                boolean over = getMousePosition() != null;
                Kit.fill(g2, 0, 0, getWidth(), getHeight(), Tokens.R_SM,
                    active ? t.brandWeak() : t.surface0());
                Kit.stroke(g2, 0, 0, getWidth(), getHeight(), Tokens.R_SM,
                    active || over ? t.brand() : t.border1());
                g2.setFont(Theme.font(active ? Font.BOLD : Font.PLAIN, Tokens.FS_CAPTION));
                g2.setColor(active || over ? t.brand() : t.text1());
                java.awt.FontMetrics fm = g2.getFontMetrics();
                g2.drawString(value, (getWidth() - fm.stringWidth(value)) / 2,
                    (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            } finally {
                g2.dispose();
            }
        }
    }
}
