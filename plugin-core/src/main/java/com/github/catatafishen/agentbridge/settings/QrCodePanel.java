package com.github.catatafishen.agentbridge.settings;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.intellij.ui.JBColor;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * A Swing panel that renders a QR code for a given URL.
 * Call {@link #setUrl(String)} to update the content; pass {@code null} to show a placeholder.
 */
public final class QrCodePanel extends JPanel {

    private static final int QR_SIZE = 160;
    private static final int QR_QUIET = 2; // quiet zone in modules

    private transient BufferedImage qrImage;
    private String errorText;

    public QrCodePanel() {
        setPreferredSize(new Dimension(QR_SIZE, QR_SIZE));
        setMinimumSize(new Dimension(QR_SIZE, QR_SIZE));
        setMaximumSize(new Dimension(QR_SIZE, QR_SIZE));
        setOpaque(false);
        setUrl(null);
    }

    /**
     * Updates the QR code. Pass {@code null} or empty to show the "not running" placeholder.
     */
    public void setUrl(String url) {
        if (url == null || url.isBlank()) {
            qrImage = null;
            errorText = null;
            repaint();
            return;
        }
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = Map.of(
                EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN, QR_QUIET
            );
            BitMatrix matrix = writer.encode(url, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints);
            qrImage = toImage(matrix);
            errorText = null;
        } catch (WriterException e) {
            qrImage = null;
            errorText = "QR error";
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (qrImage != null) {
            g.drawImage(qrImage, 0, 0, getWidth(), getHeight(), this);
        } else {
            g.setColor(JBColor.LIGHT_GRAY);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(JBColor.GRAY);
            String text = errorText != null ? errorText : "Not running";
            FontMetrics fm = g.getFontMetrics();
            int x = (getWidth() - fm.stringWidth(text)) / 2;
            int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(text, x, y);
        }
    }

    private static BufferedImage toImage(BitMatrix matrix) {
        int w = matrix.getWidth();
        int h = matrix.getHeight();
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                image.setRGB(x, y, matrix.get(x, y) ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
            }
        }
        return image;
    }
}
