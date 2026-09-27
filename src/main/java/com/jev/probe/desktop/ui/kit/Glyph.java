package com.jev.probe.desktop.ui.kit;

import java.awt.Color;
import java.awt.Graphics2D;

/**
 * A resolution-independent icon.
 *
 * The old `Icons` factory returned `Icon` objects with the colour baked into
 * their pixels, which meant a theme change had to rebuild every icon — one of
 * the reasons the palette was frozen at construction. A glyph is a shape plus a
 * colour handed in at paint time, so it follows the theme for free.
 */
@FunctionalInterface
public interface Glyph {

    /**
     * Paint into a `size × size` box at the origin.
     *
     * Implementations must set their own stroke and may assume antialiasing is
     * already on. The caller owns the `Graphics2D`.
     */
    void paint(Graphics2D g2, int size, Color color);
}
