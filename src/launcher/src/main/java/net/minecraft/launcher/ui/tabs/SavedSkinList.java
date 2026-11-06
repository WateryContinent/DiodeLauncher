package net.minecraft.launcher.ui.tabs;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;
import javax.imageio.ImageIO;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import net.minecraft.launcher.skins.SavedSkins.SavedSkin;
import net.minecraft.launcher.skins.SkinRenderer;

/** A row of saved skins, each drawn as a small player on dirt with its name underneath. */
public class SavedSkinList extends JList<SavedSkin> {
   private static final int CELL_WIDTH = 84;
   private static final int CELL_HEIGHT = 126;
   private static final int THUMB_WIDTH = 64;
   private static final int THUMB_HEIGHT = 96;
   private final DefaultListModel<SavedSkin> skins = new DefaultListModel<>();
   private final Map<SavedSkin, BufferedImage> thumbnails = new IdentityHashMap<>();
   private final Function<String, BufferedImage> capeImages;
   private Image dirt;

   /** @param capeImages gives the picture for a cape id, or null if it isn't loaded (yet) */
   public SavedSkinList(Function<String, BufferedImage> capeImages) {
      this.capeImages = capeImages;
      this.setModel(this.skins);
      this.setLayoutOrientation(JList.HORIZONTAL_WRAP);
      this.setVisibleRowCount(1);
      this.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      this.setFixedCellWidth(CELL_WIDTH);
      this.setFixedCellHeight(CELL_HEIGHT);
      this.setBackground(new Color(0x2B2B2B));
      this.setCellRenderer(new Cell());
      try {
         this.dirt = ImageIO.read(SavedSkinList.class.getResource("/dirt.png")).getScaledInstance(32, 32, Image.SCALE_FAST);
      } catch (IOException | IllegalArgumentException e) {
         this.dirt = null;
      }
   }

   public void setSkins(Iterable<SavedSkin> saved) {
      this.skins.clear();
      for (SavedSkin skin : saved) {
         this.skins.addElement(skin);
      }
      this.thumbnails.keySet().retainAll(java.util.Collections.list(this.skins.elements()));
   }

   /** Draws the thumbnails again, after a cape picture arrives or a look changes. */
   public void refreshThumbnails() {
      this.thumbnails.clear();
      this.repaint();
   }

   @Override
   public Dimension getPreferredScrollableViewportSize() {
      return new Dimension(CELL_WIDTH * 4, CELL_HEIGHT);
   }

   @Override
   protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      if (this.skins.isEmpty()) {
         Graphics2D g = (Graphics2D)graphics.create();
         g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
         g.setColor(new Color(255, 255, 255, 170));
         g.setFont(this.getFont());
         FontMetrics metrics = g.getFontMetrics();
         String[] lines = {"No saved skins yet.", "Click Save current look to keep the skin you're wearing,", "or Add skin file to add one from your computer."};
         int y = (this.getHeight() - lines.length * metrics.getHeight()) / 2 + metrics.getAscent();
         for (String line : lines) {
            g.drawString(line, 12, y);
            y += metrics.getHeight();
         }
         g.dispose();
      }
   }

   private BufferedImage thumbnail(SavedSkin skin) {
      BufferedImage thumbnail = this.thumbnails.get(skin);
      if (thumbnail == null) {
         BufferedImage cape = skin.getCapeId() == null ? null : this.capeImages.apply(skin.getCapeId());
         thumbnail = SkinRenderer.render(SkinRenderer.build(skin.getImage(), skin.isSlim(), cape), -25.0, 8.0, THUMB_WIDTH, THUMB_HEIGHT);
         this.thumbnails.put(skin, thumbnail);
      }
      return thumbnail;
   }

   private class Cell extends JComponent implements ListCellRenderer<SavedSkin> {
      private SavedSkin skin;
      private boolean selected;

      @Override
      public Component getListCellRendererComponent(JList<? extends SavedSkin> list, SavedSkin value, int index, boolean isSelected, boolean cellHasFocus) {
         this.skin = value;
         this.selected = isSelected;
         this.setToolTipText(value.getName() + " (" + (value.isSlim() ? "Slim" : "Classic") + " arms)");
         return this;
      }

      @Override
      protected void paintComponent(Graphics graphics) {
         Graphics2D g = (Graphics2D)graphics.create();
         try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int x = (CELL_WIDTH - THUMB_WIDTH - 8) / 2;
            int y = 4;
            int w = THUMB_WIDTH + 8;
            int h = THUMB_HEIGHT + 4;
            g.setClip(x, y, w, h);
            if (SavedSkinList.this.dirt != null) {
               for (int tx = x; tx < x + w; tx += 32) {
                  for (int ty = y; ty < y + h; ty += 32) {
                     g.drawImage(SavedSkinList.this.dirt, tx, ty, null);
                  }
               }
            }
            g.setColor(new Color(0, 0, 0, 90));
            g.fillRect(x, y, w, h);
            g.drawImage(SavedSkinList.this.thumbnail(this.skin), x + 4, y + 2, null);
            g.setClip(null);
            g.setColor(this.selected ? Color.WHITE : new Color(0, 0, 0, 160));
            g.drawRect(x, y, w - 1, h - 1);
            if (this.selected) {
               g.drawRect(x + 1, y + 1, w - 3, h - 3);
            }

            g.setFont(SavedSkinList.this.getFont().deriveFont(this.selected ? Font.BOLD : Font.PLAIN));
            FontMetrics metrics = g.getFontMetrics();
            String name = this.skin.getName();
            if (metrics.stringWidth(name) > CELL_WIDTH - 6) {
               while (name.length() > 1 && metrics.stringWidth(name + "...") > CELL_WIDTH - 6) {
                  name = name.substring(0, name.length() - 1);
               }
               name = name + "...";
            }
            g.setColor(this.selected ? Color.WHITE : new Color(220, 220, 220));
            g.drawString(name, (CELL_WIDTH - metrics.stringWidth(name)) / 2, y + h + 4 + metrics.getAscent());
         } finally {
            g.dispose();
         }
      }
   }
}
