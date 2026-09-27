package com.jev.probe.desktop.capture;

import com.jev.probe.desktop.config.CaptureTarget;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Screen capture for the desktop capture layer.
 *
 * On Windows there is no FLAG_SECURE equivalent for these chat clients, so the
 * pixels are always available — that is the whole reason the desktop port reads
 * the screen instead of a control tree (WeChat 4.x exposes only two nodes via UI
 * Automation; see tools/uia-probe.ps1).
 *
 * The pixels come from the target WINDOW, not from a screen rectangle. See
 * {@link Win32Windows} for why: a screen rectangle is unusable on a mixed-DPI
 * multi-monitor desktop, and it also stops meaning anything while the chat is
 * occluded or minimized.
 *
 * Two things live here because the watcher needs both cheaply:
 * - {@link #grab} — the pixels of the watched area.
 * - {@link #fingerprint} — a 64-bit average hash of the same area, so the
 *   watcher can tell "nothing changed, do not spend a vision call" from "a new
 *   message arrived" without any OCR at all.
 */
public final class ScreenGrabber {

    private static final Logger LOG = Logger.getLogger("AICHAT");

    /** A captured frame is scaled to this width before hashing. */
    private static final int HASH_SIZE = 8;

    private final Win32Windows windows = new Win32Windows();

    /**
     * Pixels of the watched area of the target's window.
     *
     * The window is read by handle, in the background: it does not have to be the
     * foreground window, or even visible — {@code PrintWindow} renders the window
     * itself, so anything covering it is irrelevant, and nothing is ever shown,
     * moved or activated to take the picture.
     *
     * @return the frame, or null when this poll found nothing to read (a minimized
     *         window whose render target Windows discarded, or a window that has
     *         not painted yet). Null means "quiet", not "failed": restoring the
     *         window just to grab a frame is what made the chat app pop back up on
     *         every poll, and it is not done here.
     */
    public BufferedImage grab(CaptureTarget target) throws IOException {
        if (target == null) {
            throw new IOException("还没有选择聊天区域。");
        }
        Win32Windows.Win win = windows.resolve(target.windowClass(), target.windowTitle());
        if (win == null) {
            throw new IOException("找不到窗口「" + target.windowTitle()
                + "」，它可能已经关闭或被改名。点「选择区域」重新框一次。");
        }
        // Minimized windows are attempted too, not skipped: PrintWindow with
        // PW_RENDERFULLCONTENT answers for some clients even then, and the attempt
        // is free. Only a blank frame ends the poll quietly.
        try {
            return windows.capture(win, target.crop());
        } catch (IOException e) {
            LOG.log(Level.FINE, "本次抓取无画面: {0}", e.getMessage());
            return null;
        }
    }

    /**
     * A 64-bit average hash of [img]: scale to 8x8 greyscale and set one bit per
     * pixel depending on whether it is brighter than the mean.
     *
     * Deliberately coarse. The watcher only needs "did the chat area visibly
     * change", and a coarse hash ignores the sub-pixel noise that a caret, an
     * anti-aliased edge or a repainted timestamp can produce — the exact jitter
     * that would otherwise turn every poll into a paid vision call.
     */
    public static long fingerprint(BufferedImage img) {
        BufferedImage small = new BufferedImage(HASH_SIZE, HASH_SIZE, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = small.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img.getScaledInstance(HASH_SIZE, HASH_SIZE, Image.SCALE_SMOOTH),
                    0, 0, null);
        } finally {
            g.dispose();
        }
        int[] gray = new int[HASH_SIZE * HASH_SIZE];
        long sum = 0;
        for (int y = 0; y < HASH_SIZE; y++) {
            for (int x = 0; x < HASH_SIZE; x++) {
                int v = small.getRaster().getSample(x, y, 0);
                gray[y * HASH_SIZE + x] = v;
                sum += v;
            }
        }
        long mean = sum / gray.length;
        long hash = 0L;
        for (int i = 0; i < gray.length; i++) {
            if (gray[i] > mean) hash |= (1L << i);
        }
        return hash;
    }

    /** How many bits differ between two fingerprints (0 = identical). */
    public static int distance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    /**
     * JPEG base64, in exactly the form the vision route expects: JPEG (a PNG
     * screenshot is several times larger), and base64 with no line wrapping
     * (the default encoder inserts newlines, which corrupt the data URL).
     */
    public static String encodeJpegBase64(BufferedImage img, float quality) {
        byte[] jpeg = toJpeg(img, quality);
        return Base64.getEncoder().withoutPadding().encodeToString(jpeg);
    }

    private static byte[] toJpeg(BufferedImage img, float quality) {
        // JPEG has no alpha channel; TYPE_INT_RGB also avoids the colour shift a
        // 4-channel source can pick up on the way through the encoder.
        BufferedImage rgb = img;
        if (img.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            try {
                g.drawImage(img, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) return new byte[0];
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(rgb, null, null), param);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "JPEG 编码失败", e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
