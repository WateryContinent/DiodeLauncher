package net.minecraft.launcher.ui.tabs.website;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;

/**
 * Small placeholder shown while the JavaFX/WebKit renderer is prepared.
 * This avoids briefly rendering the target website through Swing's ancient
 * HTML renderer before the modern WebView is ready.
 */
public class LoadingBrowser implements Browser {
   private final JPanel panel = new JPanel(new BorderLayout(0, 12));

   public LoadingBrowser() {
      this.panel.setBackground(new Color(32, 32, 32));
      this.panel.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));

      JLabel label = new JLabel("Loading Update Notes...", SwingConstants.CENTER);
      label.setForeground(new Color(220, 220, 220));
      label.setFont(label.getFont().deriveFont(Font.PLAIN, 18.0F));
      this.panel.add(label, BorderLayout.CENTER);

      JProgressBar progress = new JProgressBar();
      progress.setIndeterminate(true);
      progress.setBorderPainted(false);
      progress.setOpaque(false);
      this.panel.add(progress, BorderLayout.SOUTH);
   }

   @Override
   public void loadUrl(String url) {
      // Deliberately ignored. WebsiteTab will send the URL to whichever real
      // browser (JavaFX or Swing fallback) becomes available.
   }

   @Override
   public Component getComponent() {
      return this.panel;
   }

   @Override
   public void resize(Dimension size) {
   }
}
