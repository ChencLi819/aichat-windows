package com.jev.probe.desktop.ui.kit;

import com.jev.probe.desktop.ui.Theme;
import com.jev.probe.desktop.ui.ThemeManager;
import com.jev.probe.desktop.ui.Tokens;
import com.jev.probe.desktop.ui.WindowResizer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * The dialog chrome from `.dialog` / `.dialog__head` / `.dialog__body` /
 * `.dialog__foot`, as a reusable shell.
 *
 * A dialog is a window, so `SwingUtilities.updateComponentTreeUI` cannot reskin
 * it; a rounded, translucent, undecorated root that paints itself from the
 * palette can, and does — changing the theme while the settings dialog is open
 * repaints it in place, which is what FR-SET-08 asks for.
 *
 * Esc always closes (FR-COM-04). The caller owns modality and what "close"
 * means, because a dialog with unsaved edits must ask before discarding.
 *
 * Dialogs open at the size the design specifies and are resizable from there.
 * Being undecorated, they have no native frame to drag, so the edges come from
 * {@link WindowResizer} — which is also why the opening size must not be
 * installed as the minimum size: `setMinimumSize(size)` is exactly what pinned
 * these windows shut before.
 */
public class DialogShell {

    /**
     * The smallest a dialog can be dragged to. Deliberately well under the
     * opening size so there is room to shrink on a small screen; below this the
     * five-page settings rail and the footer stop fitting.
     */
    private static final int MIN_W = 620;
    private static final int MIN_H = 420;

    private final JDialog dialog;
    private final JPanel head;
    private final JLabel titleLabel;
    private final JPanel body;
    private final JPanel foot;
    private final JPanel footWest;
    private final JPanel footActions;
    private final Surface root;
    private Runnable onClose;
    private WindowResizer resizer;
    /**
     * Kept as fields rather than constructor locals because {@link #headless()}
     * rebuilds the resizer after the constructor has already run.
     */
    private final int minW;
    private final int minH;

    private DialogShell(Window owner, String title, int width, int height) {
        dialog = new JDialog(owner);
        dialog.setModal(true);
        dialog.setUndecorated(true);
        try {
            dialog.setBackground(new Color(0, 0, 0, 0));
        } catch (Exception e) {
            dialog.getContentPane().setBackground(Theme.tokens().surface0());
        }

        // [resizer] is null while this lambda is built and assigned by the time
        // it is painted, which is why the hint is read inside the skin rather
        // than captured.
        root = new Surface((g2, w, h, t) -> {
            Kit.fill(g2, 0, 0, w, h, Tokens.R_XL, t.surface0());
            Kit.stroke(g2, 0, 0, w, h, Tokens.R_XL, t.border1());
            if (resizer != null) {
                int z = resizer.hotZone();
                if (z != 0) {
                    Kit.resizeHint(g2, w, h, Tokens.R_XL, WindowResizer.GRAB,
                        (z & WindowResizer.N) != 0, (z & WindowResizer.S) != 0,
                        (z & WindowResizer.W) != 0, (z & WindowResizer.E) != 0,
                        Kit.tint(t.brand(), 90));
                }
            }
        }, new BorderLayout());
        root.setWindowSurface(true);

        // ---------------------------------------------------------- header
        head = new Surface(Surface.flatEdge(t -> t.surface0(), t -> t.border1(), false),
            new BorderLayout(Tokens.SP3, 0));
        head.setBorder(BorderFactory.createEmptyBorder(
            Tokens.SP4, Tokens.SP5, Tokens.SP4, Tokens.SP5));
        titleLabel = new JLabel(title);
        titleLabel.setFont(Theme.font(Font.BOLD, Tokens.FS_TITLE));
        titleLabel.setForeground(Theme.tokens().text1());
        head.add(titleLabel, BorderLayout.CENTER);
        Btn close = new Btn(Glyphs.close(), Btn.Variant.ICON).iconDanger(true);
        close.setToolTipText("关闭");
        close.onClick(this::close);
        JPanel closeWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        closeWrap.setOpaque(false);
        closeWrap.add(close);
        head.add(closeWrap, BorderLayout.EAST);

        // ------------------------------------------------------------ body
        body = Theme.column();
        JScrollPane scroll = new JScrollPane(body);
        Theme.slimScrollBar(scroll);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);

        // ---------------------------------------------------------- footer
        foot = new Surface(Surface.flatEdge(t -> t.surface1(), t -> t.border1(), true),
            new BorderLayout(Tokens.SP3, 0));
        foot.setBorder(BorderFactory.createEmptyBorder(
            Tokens.SP2, Tokens.SP5, Tokens.SP2, Tokens.SP5));
        footWest = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, Tokens.SP3));
        footWest.setOpaque(false);
        footActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.SP3, Tokens.SP3));
        footActions.setOpaque(false);
        foot.add(footWest, BorderLayout.WEST);
        foot.add(footActions, BorderLayout.CENTER);

        root.add(head, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(foot, BorderLayout.SOUTH);
        dialog.setContentPane(root);

        // ------------------------------------------------------ size + resize
        //
        // The design fixes the size a dialog OPENS at; it does not fix the size
        // it has to stay at. The floor is [MIN_W × MIN_H] rather than the
        // opening size, so every dialog here can be dragged bigger or smaller.
        dialog.setSize(width, height);
        minW = Math.min(width, MIN_W);
        minH = Math.min(height, MIN_H);
        dialog.setMinimumSize(new Dimension(minW, minH));
        // The header doubles as the drag handle, exactly as on the panel: there
        // is no title bar to grab, and the close button is excluded inside
        // WindowResizer so a press on it still closes rather than drags.
        resizer = new WindowResizer(dialog, head, minW, minH);
        // Same contract as the panel: the shell paints the hint, the controller
        // says when it changed.
        resizer.onHoverChange(() -> root.repaint());
        resizer.install();
        // A dialog is built and disposed every time it opens, so the listener is
        // released on the window's own close event — whichever path got us here,
        // including a plain `dispose()` from outside.
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                releaseResizer();
            }
        });

        dialog.getRootPane().registerKeyboardAction(e -> close(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
        Runnable restyle = () -> {
            titleLabel.setFont(Theme.font(Font.BOLD, Tokens.FS_TITLE));
            titleLabel.setForeground(Theme.tokens().text1());
            root.repaint();
        };
        // 按组件生命周期登记：对话框每次打开都会新建一套，用全局 addListener
        // 会让每次关闭都留下永不释放的回调（见 ThemeManager.bind）。
        ThemeManager.bind(titleLabel, restyle);
    }

    /**
     * Opens at [width × height] and can be resized from there.
     *
     * "Modal" describes modality, not size: these used to be locked at their
     * opening size because the minimum was set to the same dimension.
     */
    public static DialogShell modal(Window owner, String title, int width, int height) {
        return new DialogShell(owner, title, width, height);
    }

    /**
     * Drops the title bar, for the dialogs whose design has no `.dialog__head`.
     *
     * <p>Only the walkthrough is one: `.dialog.onboard` is exactly
     * `.onboard__body + .dialog__foot`, so a header here is 56px of chrome the
     * prototype never had and content the body is entitled to. Removing it is
     * how the walkthrough gets its designed height back.
     *
     * <p>The header is also the drag handle, so there is nothing left to drag by
     * and the resizer is rebuilt without one — {@link WindowResizer} takes a null
     * handle and simply skips the move path. The edges still resize: that is a
     * desktop affordance the browser prototype never had, and dropping it would
     * leave the dialog uncorrectable on a screen too small to show it whole.
     *
     * <p>Esc still closes, which matters more here than elsewhere: with no header
     * there is no close button, so Esc (FR-COM-04) is the way out.
     */
    public DialogShell headless() {
        root.remove(head);
        releaseResizer();
        resizer = new WindowResizer(dialog, null, minW, minH);
        resizer.onHoverChange(() -> root.repaint());
        resizer.install();
        root.revalidate();
        root.repaint();
        return this;
    }

    /** The scrollable content column; add pages or groups to it. */
    public JPanel body() {
        return body;
    }

    /** The footer row; it is right-aligned and already has the top hairline. */
    public JPanel foot() {
        return foot;
    }

    public JDialog dialog() {
        return dialog;
    }

    public JLabel titleLabel() {
        return titleLabel;
    }

    /** Replaces what happens on the close button and on Esc. */
    public DialogShell onClose(Runnable action) {
        this.onClose = action;
        return this;
    }

    /** Adds a left-aligned note to the footer (knowledge-base style). */
    public DialogShell footNote(String text) {
        JLabel note = new JLabel(text);
        note.setFont(Theme.font(Font.PLAIN, Tokens.FS_NOTE));
        note.setForeground(Theme.tokens().text3());
        footWest.removeAll();
        footWest.add(note);
        Runnable restyle = () -> {
            note.setFont(Theme.font(Font.PLAIN, Tokens.FS_NOTE));
            note.setForeground(Theme.tokens().text3());
        };
        ThemeManager.bind(note, restyle);
        return this;
    }

    /** The row footer buttons belong in — right-aligned, above the surface-1 bar. */
    public JPanel footActions() {
        return footActions;
    }

    public DialogShell footer(JComponent... components) {
        for (JComponent c : components) footActions.add(c);
        return this;
    }

    public void close() {
        if (onClose != null) {
            onClose.run();
            return;
        }
        dispose();
    }

    /** Closes the window, releasing the resize listener on the way out. */
    public void dispose() {
        releaseResizer();
        dialog.dispose();
    }

    /**
     * Drops the global resize listener. Idempotent: the close path and the
     * window-closed event both end up here.
     */
    private void releaseResizer() {
        if (resizer != null) {
            resizer.uninstall();
            resizer = null;
        }
    }

    /** Centres on [owner] and shows. */
    public void show() {
        dialog.setLocationRelativeTo(dialog.getOwner());
        dialog.setVisible(true);
    }

    /** A vertical gap for [body] — aligned like the rows around it. */
    public static JComponent gap(int px) {
        return Theme.gap(px);
    }

    /** A page title + description pair, as every settings page opens with. */
    public static JPanel pageHeader(String title, String desc) {
        JPanel p = Theme.column();
        p.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        JLabel t = new JLabel(title);
        t.setFont(Theme.font(Font.BOLD, Tokens.FS_TITLE));
        t.setForeground(Theme.tokens().text1());
        t.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        JLabel d = new JLabel(desc);
        d.setFont(Theme.font(Font.PLAIN, Tokens.FS_NOTE));
        d.setForeground(Theme.tokens().text2());
        d.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        p.add(t);
        p.add(Box.createVerticalStrut(Tokens.SP1));
        p.add(d);
        Runnable restyle = () -> {
            t.setFont(Theme.font(Font.BOLD, Tokens.FS_TITLE));
            d.setFont(Theme.font(Font.PLAIN, Tokens.FS_NOTE));
            t.setForeground(Theme.tokens().text1());
            d.setForeground(Theme.tokens().text2());
        };
        ThemeManager.bind(t, restyle);
        return p;
    }

    /** A small bold section label — `.group__title`. */
    public static JLabel groupTitle(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.font(Font.BOLD, Tokens.FS_NOTE));
        l.setForeground(Theme.tokens().text2());
        l.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        Runnable restyle = () -> {
            l.setFont(Theme.font(Font.BOLD, Tokens.FS_NOTE));
            l.setForeground(Theme.tokens().text2());
        };
        ThemeManager.bind(l, restyle);
        return l;
    }

    /** Wraps [content] with the 20px bottom margin a `.group` has. */
    public static JPanel group(JComponent content) {
        JPanel p = Theme.column();
        p.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        p.add(content);
        p.setBorder(BorderFactory.createEmptyBorder(0, 0, Tokens.SP6, 0));
        return p;
    }

    /** An empty column that stretches to the body width. */
    public static JPanel column() {
        return Theme.column();
    }

    /** Sets a box-layout-friendly max height so a child is not stretched. */
    public static <T extends JComponent> T pin(T c) {
        c.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    /** A horizontal row: [left] stretches, the rest keep their width. */
    public static JPanel row(JComponent left, JComponent... rest) {
        JPanel p = new JPanel(new BorderLayout(Tokens.SP3, 0));
        p.setOpaque(false);
        p.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        p.add(left, BorderLayout.CENTER);
        if (rest.length > 0) {
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.SP2, 0));
            right.setOpaque(false);
            for (JComponent c : rest) right.add(c);
            p.add(right, BorderLayout.EAST);
        }
        int h = Math.max(left.getPreferredSize().height,
            rest.length == 0 ? 0 : rest[0].getPreferredSize().height);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(h, 24)));
        return p;
    }

    /** A row's label and description, stacked; the flexible part of [row]. */
    public static JPanel labelled(String title, String desc) {
        JPanel p = Theme.column();
        p.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        JLabel t = new JLabel(title);
        t.setFont(Theme.font(Font.PLAIN, Tokens.FS_BODY));
        t.setForeground(Theme.tokens().text1());
        t.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        p.add(t);
        if (desc != null && !desc.isBlank()) {
            JLabel d = new JLabel(desc);
            d.setFont(Theme.font(Font.PLAIN, Tokens.FS_CAPTION));
            d.setForeground(Theme.tokens().text3());
            d.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            p.add(d);
        }
        Runnable restyle = () -> {
            t.setFont(Theme.font(Font.PLAIN, Tokens.FS_BODY));
            t.setForeground(Theme.tokens().text1());
        };
        ThemeManager.bind(t, restyle);
        return p;
    }
}
