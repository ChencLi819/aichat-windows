package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;

/**
 * `.field` — label, control, hint and error line, stacked with a 6px gap.
 *
 * The error line is what FR-SET-05 and FR-KB-04 require to be *inline*: a range
 * violation or an empty title must say so next to the input instead of silently
 * reverting the old value (the old `SettingsDialog.parseInt` did exactly that)
 * or popping a dialog.
 */
public class Field extends JPanel {

    private final JLabel label = new JLabel();
    private final JLabel hint = new JLabel();
    private final JLabel error = new JLabel();
    private JComponent control;

    public Field(String labelText, JComponent control) {
        this.control = control;
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(LEFT_ALIGNMENT);

        label.setFont(Theme.font(Font.PLAIN, Tokens.FS_NOTE));
        label.setAlignmentX(LEFT_ALIGNMENT);
        label.setVisible(labelText != null && !labelText.isBlank());
        if (label.isVisible()) label.setText(labelText);
        label.setForeground(Theme.tokens().text2());

        hint.setFont(Theme.font(Font.PLAIN, Tokens.FS_CAPTION));
        hint.setAlignmentX(LEFT_ALIGNMENT);
        hint.setVisible(false);
        hint.setForeground(Theme.tokens().text3());

        error.setFont(Theme.font(Font.PLAIN, Tokens.FS_CAPTION));
        error.setAlignmentX(LEFT_ALIGNMENT);
        error.setVisible(false);
        error.setForeground(Theme.tokens().danger());

        if (label.isVisible()) add(label);
        if (control != null) {
            add(Box.createVerticalStrut(Tokens.SP2));
            control.setAlignmentX(LEFT_ALIGNMENT);
            add(control);
        }
        add(Box.createVerticalStrut(Tokens.SP2));
        add(hint);
        add(error);

        ThemeManager.addListener(this::restyle);
    }

    private void restyle() {
        label.setFont(Theme.font(Font.PLAIN, Tokens.FS_NOTE));
        hint.setFont(Theme.font(Font.PLAIN, Tokens.FS_CAPTION));
        error.setFont(Theme.font(Font.PLAIN, Tokens.FS_CAPTION));
        label.setForeground(Theme.tokens().text2());
        hint.setForeground(Theme.tokens().text3());
        error.setForeground(Theme.tokens().danger());
        revalidate();
        repaint();
    }

    public Field hint(String text) {
        boolean show = text != null && !text.isBlank();
        hint.setText(show ? text : "");
        hint.setVisible(show);
        revalidate();
        return this;
    }

    /** `null` or blank clears the error and its red border. */
    public Field error(String text) {
        boolean show = text != null && !text.isBlank();
        error.setText(show ? text : "");
        error.setVisible(show);
        if (control instanceof Input i) i.invalid(show);
        if (control instanceof Password p) p.invalid(show);
        if (control instanceof Area a) a.invalid(show);
        revalidate();
        repaint();
        return this;
    }

    public boolean hasError() {
        return error.isVisible();
    }

    public JComponent control() {
        return control;
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** A spacer that keeps a column of fields from looking cramped. */
    public static JComponent gap(int px) {
        return (JComponent) Box.createVerticalStrut(px);
    }

    @Override
    protected void paintComponent(Graphics g) {
        // Never paints: the parent's surface must show through.
        super.paintComponent(g);
    }
}
