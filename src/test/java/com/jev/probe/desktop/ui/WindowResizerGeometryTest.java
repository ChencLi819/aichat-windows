package com.jev.probe.desktop.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static com.jev.probe.desktop.ui.WindowResizer.E;
import static com.jev.probe.desktop.ui.WindowResizer.N;
import static com.jev.probe.desktop.ui.WindowResizer.S;
import static com.jev.probe.desktop.ui.WindowResizer.W;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 拖拽/缩放的纯几何：改动量、最小尺寸钳位、DPI 折算。
 *
 * 这些用例锁住的就是「跨屏拖拽后窗口尺寸异常」的两条底线：拖边不会把窗口
 * 拉爆（越界时窗口不跟随），以及最小尺寸在任何 DPI 下都真的生效。
 */
class WindowResizerGeometryTest {

    private static final int MIN_W = 360;
    private static final int MIN_H = 470;
    /** 面板默认逻辑尺寸。 */
    private static final Rectangle START = new Rectangle(1000, 100, 460, 720);

    @Test
    @DisplayName("拖东边：宽度按位移增长，原点不动")
    void dragEastEdgeGrowsWidth() {
        Rectangle out = WindowResizer.edgeBounds(START, E, 120, 0, 1.0, MIN_W, MIN_H);
        assertEquals(new Rectangle(1000, 100, 580, 720), out);
    }

    @Test
    @DisplayName("拖西边：原点随之移动，右边界保持不动")
    void dragWestEdgeMovesOrigin() {
        Rectangle out = WindowResizer.edgeBounds(START, W, 40, 0, 1.0, MIN_W, MIN_H);
        assertEquals(new Rectangle(1040, 100, 420, 720), out);
        assertEquals(START.x + START.width, out.x + out.width, "右边界必须不动");
    }

    @Test
    @DisplayName("西边越过最小宽度：窗口停住，不再向右滑")
    void westEdgeStopsAtMinimumWidth() {
        Rectangle out = WindowResizer.edgeBounds(START, W, 9999, 0, 1.0, MIN_W, MIN_H);
        assertEquals(MIN_W, out.width);
        assertEquals(START.x + START.width, out.x + out.width, "最小宽度处右边界仍然不动");
    }

    @Test
    @DisplayName("左上角同时拖：原点移动，右下边界不动")
    void northWestCornerMovesOriginOnly() {
        Rectangle out = WindowResizer.edgeBounds(START, N | W, 60, 30, 1.0, MIN_W, MIN_H);
        assertEquals(new Rectangle(1060, 130, 400, 690), out);
        assertEquals(START.x + START.width, out.x + out.width);
        assertEquals(START.y + START.height, out.y + out.height);
    }

    @Test
    @DisplayName("高 DPI 屏上最小尺寸按 scale 折算，逻辑最小尺寸仍成立")
    void minimumSizeScalesWithDpi() {
        // 150% 屏：最小物理尺寸 = 360×1.5 = 540
        Rectangle out = WindowResizer.edgeBounds(START, E, -9999, 0, 1.5, MIN_W, MIN_H);
        assertEquals(540, out.width);
        // 125% 屏：450
        Rectangle out2 = WindowResizer.edgeBounds(START, E, -9999, 0, 1.25, MIN_W, MIN_H);
        assertEquals(450, out2.width);
    }

    @Test
    @DisplayName("只拖南边：高度变化，宽度与原点都不受影响")
    void southEdgeLeavesWidthAlone() {
        Rectangle out = WindowResizer.edgeBounds(START, S, 0, -400, 1.0, MIN_W, MIN_H);
        assertEquals(new Rectangle(1000, 100, 460, MIN_H), out);
    }
}