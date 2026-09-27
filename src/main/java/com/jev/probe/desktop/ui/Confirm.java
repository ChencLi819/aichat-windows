package com.jev.probe.desktop.ui;

import com.jev.probe.desktop.ui.kit.Btn;
import com.jev.probe.desktop.ui.kit.Card;
import com.jev.probe.desktop.ui.kit.DialogShell;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Window;

/**
 * The app's one confirmation dialog, replacing every {@code JOptionPane} the old
 * settings and knowledge screens reached for (FR-SET-07).
 *
 * A native confirm box would be the one piece of Windows chrome left in an
 * otherwise self-drawn UI, and it cannot follow the palette — so the shared
 * {@link DialogShell} draws it instead, exactly like the other dialogs. The
 * destructive variant fills its confirm button with the danger colour, which is
 * what makes "this cannot be undone" readable before the click rather than
 * after.
 *
 * <p>It is modal and it blocks, so it must be called from the EDT. Calling it
 * while another modal dialog is open is fine: pass that dialog as {@code owner}
 * (the platform nests the modality, which is what a caller wants — the outer
 * dialog stays blocked until the question is answered).
 */
public final class Confirm {

    /** How loud the confirm button is. */
    public enum Kind {
        /** A non-destructive question. Confirms on the brand colour. */
        NORMAL,
        /** A destructive action. Confirms on the danger colour. */
        DANGER
    }

    private static final int WIDTH = 420;
    /** Body wrap width: 420 minus the two paddings the box adds around the text. */
    private static final int TEXT_WIDTH = 356;
    /** A starting height; the real one comes from {@code pack()} below. */
    private static final int PLACEHOLDER_HEIGHT = 160;

    private Confirm() {
    }

    /**
     * Ask a yes/no question and block until it is answered.
     *
     * @return true when the user confirmed; false for the cancel button, Esc, and
     *         the window close.
     */
    public static boolean ask(
        Window owner, String title, String body, String confirmLabel, Kind kind
    ) {
        boolean[] confirmed = {false};
        DialogShell shell = DialogShell.modal(owner, title, WIDTH, PLACEHOLDER_HEIGHT);

        JPanel content = DialogShell.column();
        content.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        content.setBorder(BorderFactory.createEmptyBorder(
            Tokens.SP5, Tokens.SP6, Tokens.SP5, Tokens.SP6));
        Card block = Card.subtle();
        block.add(Card.wide(message(body)));
        content.add(block);
        shell.body().add(content);

        Btn cancel = new Btn("取消", Btn.Variant.SECONDARY);
        cancel.onClick(shell::close);
        Btn ok = new Btn(
            confirmLabel == null || confirmLabel.isBlank() ? "确定" : confirmLabel,
            kind == Kind.DANGER ? Btn.Variant.DANGER_SOLID : Btn.Variant.PRIMARY);
        ok.onClick(() -> {
            confirmed[0] = true;
            shell.close();
        });
        shell.footer(cancel, ok);

        // The shell fixes a size at construction; a question's height depends on
        // its text, so measure the real content and force the 420px width.
        shell.dialog().pack();
        int height = shell.dialog().getHeight();
        shell.dialog().setSize(new Dimension(WIDTH, height));
        shell.dialog().setMinimumSize(new Dimension(WIDTH, height));
        shell.show();
        return confirmed[0];
    }

    /** The question itself, wrapped to the dialog's body width. */
    private static JLabel message(String body) {
        JLabel l = new JLabel(
            "<html><div style='width:" + TEXT_WIDTH + "px'>" + escape(body) + "</div></html>");
        l.setFont(Theme.font(Font.PLAIN, Tokens.FS_BODY));
        l.setForeground(Theme.tokens().text2());
        l.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        ThemeManager.addListener(() -> {
            l.setFont(Theme.font(Font.PLAIN, Tokens.FS_BODY));
            l.setForeground(Theme.tokens().text2());
        });
        return l;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>");
    }
}
