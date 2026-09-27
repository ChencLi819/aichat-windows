package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;

/**
 * `.empty` — the 56px brand circle, a title, a one-line explanation and an
 * optional call to action.
 *
 * FR-PANEL-10 is explicit that an empty panel must not be a single grey line
 * saying 「还没有分析」: the empty state is the first thing a new user sees and
 * is where they are told what the app does and given the button that starts it.
 */
public class EmptyState extends JPanel {

    private static final int CIRCLE = 56;

    private final Glyph icon;
    private String title;
    private String desc;
    private JComponent action;

    public EmptyState(Glyph icon, String title, String desc) {
        this.icon = icon;
        this.title = title;
        this.desc = desc;
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(CENTER_ALIGNMENT);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(Tokens.SP8, Tokens.SP5,
            Tokens.SP8, Tokens.SP5));
        ThemeManager.addListener(this::repaint);
        build();
    }

    public EmptyState action(JComponent c) {
        this.action = c;
        rebuild();
        return this;
    }

    public EmptyState setMessage(String title, String desc) {
        this.title = title;
        this.desc = desc;
        rebuild();
        return this;
    }

    /** The CTA is a normal button; this just lays everything out centred. */
    private void build() {
        add(new Circle());
        add(Box.createVerticalStrut(Tokens.SP3));
        JLabel t = new JLabel(title, SwingConstants.CENTER);
        t.setFont(Theme.font(Font.BOLD, Tokens.FS_SUBTITLE));
        t.setForeground(Theme.tokens().text1());
        t.setAlignmentX(CENTER_ALIGNMENT);
        add(t);
        if (desc != null && !desc.isBlank()) {
            add(Box.createVerticalStrut(Tokens.SP3));
            add(wrapped(desc));
        }
        if (action != null) {
            add(Box.createVerticalStrut(Tokens.SP4));
            action.setAlignmentX(CENTER_ALIGNMENT);
            add(action);
        }
    }

    private void rebuild() {
        removeAll();
        build();
        revalidate();
        repaint();
    }

    private static JLabel wrapped(String text) {
        JLabel l = new JLabel("<html><div style='width:230px;text-align:center'>"
            + text.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>");
        l.setFont(Theme.font(Font.PLAIN, Tokens.FS_NOTE));
        l.setForeground(Theme.tokens().text2());
        l.setHorizontalAlignment(SwingConstants.CENTER);
        l.setAlignmentX(CENTER_ALIGNMENT);
        return l;
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    private final class Circle extends JComponent {

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(CIRCLE, CIRCLE);
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
                Tokens t = Theme.tokens();
                g2.setColor(t.brandWeak());
                g2.fill(new Ellipse2D.Double(0, 0, CIRCLE, CIRCLE));
                Graphics2D gi = (Graphics2D) g2.create();
                try {
                    gi.translate((CIRCLE - 26) / 2.0, (CIRCLE - 26) / 2.0);
                    gi.setComposite(AlphaComposite.SrcOver);
                    icon.paint(gi, 26, t.brand());
                } finally {
                    gi.dispose();
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
