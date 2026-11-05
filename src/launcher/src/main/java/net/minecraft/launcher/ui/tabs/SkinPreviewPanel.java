package net.minecraft.launcher.ui.tabs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import net.minecraft.launcher.skins.SkinRenderer;
import net.minecraft.launcher.ui.TexturedPanel;

/** The player model on the launcher's dirt background. Drag to turn it; double-click to face it again. */
public class SkinPreviewPanel extends TexturedPanel {
   private static final double START_YAW = -25.0;
   private static final double START_PITCH = 8.0;
   private SkinRenderer.Model model;
   private String message = "Loading...";
   private double yaw = START_YAW;
   private double pitch = START_PITCH;
   private BufferedImage rendered;
   private int dragX;
   private int dragY;

   public SkinPreviewPanel() {
      super("/dirt.png");
      this.setPreferredSize(new Dimension(230, 300));
      MouseAdapter mouse = new MouseAdapter() {
         @Override
         public void mousePressed(MouseEvent e) {
            SkinPreviewPanel.this.dragX = e.getX();
            SkinPreviewPanel.this.dragY = e.getY();
         }

         @Override
         public void mouseDragged(MouseEvent e) {
            SkinPreviewPanel.this.yaw += (e.getX() - SkinPreviewPanel.this.dragX) * 1.2;
            SkinPreviewPanel.this.pitch = Math.max(-30.0, Math.min(40.0, SkinPreviewPanel.this.pitch + (e.getY() - SkinPreviewPanel.this.dragY) * 0.6));
            SkinPreviewPanel.this.dragX = e.getX();
            SkinPreviewPanel.this.dragY = e.getY();
            SkinPreviewPanel.this.rendered = null;
            SkinPreviewPanel.this.repaint();
         }

         @Override
         public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
               SkinPreviewPanel.this.yaw = START_YAW;
               SkinPreviewPanel.this.pitch = START_PITCH;
               SkinPreviewPanel.this.rendered = null;
               SkinPreviewPanel.this.repaint();
            }
         }
      };
      this.addMouseListener(mouse);
      this.addMouseMotionListener(mouse);
   }

   /** Shows a skin (and cape, which may be null). */
   public void setSkin(BufferedImage skin, boolean slim, BufferedImage cape) {
      this.model = skin == null ? null : SkinRenderer.build(skin, slim, cape);
      this.message = skin == null ? "No skin" : null;
      this.rendered = null;
      this.repaint();
   }

   /** Shows a message instead of the player (while loading, or when there's nothing to show). */
   public void setMessage(String message) {
      this.model = null;
      this.message = message;
      this.rendered = null;
      this.repaint();
   }

   @Override
   public void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      Graphics2D g = (Graphics2D)graphics.create();
      try {
         g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
         int width = this.getWidth();
         int height = this.getHeight();
         if (this.model != null) {
            int modelHeight = height - 24;
            if (this.rendered == null || this.rendered.getWidth() != width || this.rendered.getHeight() != modelHeight) {
               this.rendered = SkinRenderer.render(this.model, this.yaw, this.pitch, width, modelHeight);
            }

            g.drawImage(this.rendered, 0, 4, null);
            this.drawCentred(g, "Drag to turn", height - 10, new Color(255, 255, 255, 150), 11f);
         } else if (this.message != null) {
            this.drawCentred(g, this.message, height / 2, Color.WHITE, 13f);
         }
      } finally {
         g.dispose();
      }
   }

   private void drawCentred(Graphics2D g, String text, int baseline, Color colour, float size) {
      g.setFont(this.getFont().deriveFont(Font.BOLD, size));
      FontMetrics metrics = g.getFontMetrics();
      int x = (this.getWidth() - metrics.stringWidth(text)) / 2;
      g.setColor(new Color(0, 0, 0, 160));
      g.drawString(text, x + 1, baseline + 1);
      g.setColor(colour);
      g.drawString(text, x, baseline);
   }
}
