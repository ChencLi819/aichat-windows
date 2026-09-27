package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * `.callout` — the settings page's explanatory block: a 3px semantic bar on the
 * left, a weak wash behind it, a bold title and body copy.
 *
 * FR-REL-05 needs a prominent explanation of where a relationship is stored
 * ("on the contact; this field is only a fallback"), and a plain paragraph among
 * the rows would read as just another setting. The info/warn variants are the
 * same component with a different semantic colour.
 *
 * The body re-wraps to the block's real width on layout rather than assuming a
 * size: the same callout is used in the 900px settings dialog and would fit a
 * narrower column if the nav ever collapses.
 */
public class Callout extends JPanel {

    public enum Kind { INFO, WARN }

    private final Kind kind;
    private final JLabel title = new JLabel();
    private final JLabel body = new JLabel();
    private final String bodyText;
    private int wrappedAt;

    public Callout(Kind kind, String titleText, String bodyText) {
        this.kind = kind;
        this.bodyText = bodyText == null ? "" : bodyText;
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(LEFT_ALIGNMENT);
        setBorder(BorderFactory.createEmptyBorder(
            Tokens.SP3, Tokens.SP4, Tokens.SP3, Tokens.SP4));
        title.setText(titleText == null ? "" : titleText);
        title.setFont(Theme.font(Font.BOLD, Tokens.FS_BODY));
        title.setForeground(Theme.tokens().text1());
        title.setAlignmentX(LEFT_ALIGNMENT);
        body.setFont(Theme.font(Font.PLAIN, Tokens.FS_NOTE));
        body.setForeground(Theme.tokens().text2());
        body.setAlignmentX(LEFT_ALIGNMENT);
        add(title);
        add(Box.createVerticalStrut(Tokens.SP1));
        add(body);
        ThemeManager.addListener(this::restyle);
    }

    /** Trailing action, e.g. 「按联系人管理关系 →」. */
    public Callout action(JComponent c) {
        add(Box.createVerticalStrut(Tokens.SP3));
        c.setAlignmentX(LEFT_ALIGNMENT);
        add(c);
        return this;
    }

    private void restyle() {
        title.setFont(Theme.font(Font.BOLD, Tokens.FS_BODY));
        title.setForeground(Theme.tokens().text1());
        body.setFont(Theme.font(Font.PLAIN, Tokens.FS_NOTE));
        body.setForeground(Theme.tokens().text2());
        wrappedAt = 0;
        revalidate();
        repaint();
    }

    @Override
    public void doLayout() {
        int avail = getWidth() - getInsets().left - getInsets().right;
        if (avail > 60 && avail != wrappedAt) {
            wrappedAt = avail;
            body.setText("<html><div style='width:" + avail + "px'>"
                + escape(bodyText) + "</div></html>");
        }
        super.doLayout();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>");
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
            Color accent = kind == Kind.WARN ? t.warning() : t.info();
            Color wash = kind == Kind.WARN ? t.warningWeak() : t.infoWeak();
            Kit.fill(g2, 0, 0, getWidth(), getHeight(), Tokens.R_MD, wash);
            // The 3px left bar is what identifies the callout at a glance.
            g2.setColor(accent);
            g2.setStroke(new BasicStroke(3f));
            g2.drawLine(1, 2, 1, getHeight() - 3);
        } finally {
            g2.dispose();
        }
    }
}
