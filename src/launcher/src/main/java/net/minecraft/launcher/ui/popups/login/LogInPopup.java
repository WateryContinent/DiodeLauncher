package net.minecraft.launcher.ui.popups.login;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import net.minecraft.launcher.Launcher;

/**
 * Simplified first-run screen: local name + Play Offline.
 */
public class LogInPopup extends JPanel implements ActionListener {
   private static final Color CARD_BACKGROUND = new Color(20, 20, 20, 218);
   private static final Color CARD_BORDER = new Color(104, 104, 104, 220);
   private static final Color BUTTON_NORMAL = new Color(62, 132, 42);
   private static final Color BUTTON_HOVER = new Color(75, 153, 51);
   private static final Color BUTTON_PRESSED = new Color(50, 111, 34);
   private static final Color BUTTON_BORDER = new Color(32, 76, 23);

   private final Launcher minecraftLauncher;
   private final LogInPopup.Callback callback;

   // Retained for compatibility with the original decompiled helper classes.
   // They are no longer displayed on the first screen.
   private final AuthErrorForm errorForm;
   private final ExistingUserListForm existingUserListForm;

   private final LogInForm logInForm;
   private final JButton offlineButton = new JButton("Play Offline");
   private final JProgressBar progressBar = new JProgressBar();

   public LogInPopup(Launcher minecraftLauncher, LogInPopup.Callback callback) {
      super(true);
      this.minecraftLauncher = minecraftLauncher;
      this.callback = callback;
      this.errorForm = new AuthErrorForm(this);
      this.existingUserListForm = new ExistingUserListForm(this);
      this.logInForm = new LogInForm(this);
      this.createInterface();
      this.offlineButton.addActionListener(this);
   }

   protected void createInterface() {
      this.setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
      this.setBorder(new EmptyBorder(24, 28, 26, 28));
      this.setOpaque(false);
      this.setPreferredSize(new Dimension(370, 270));
      this.setMinimumSize(new Dimension(340, 250));

      try (InputStream stream = LogInPopup.class.getResourceAsStream("/minecraft_logo.png")) {
         if (stream != null) {
            BufferedImage image = ImageIO.read(stream);
            JLabel label = new JLabel(new ImageIcon(image));
            label.setHorizontalAlignment(SwingConstants.CENTER);
            label.setAlignmentX(CENTER_ALIGNMENT);
            this.add(label);
            this.add(Box.createVerticalStrut(22));
         }
      } catch (IOException e) {
         e.printStackTrace();
      }

      this.logInForm.setAlignmentX(LEFT_ALIGNMENT);
      this.add(this.logInForm);
      this.add(Box.createVerticalStrut(14));

      styleOfflineButton();
      JPanel buttonPanel = new JPanel(new BorderLayout());
      buttonPanel.setOpaque(false);
      buttonPanel.setAlignmentX(LEFT_ALIGNMENT);
      buttonPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
      buttonPanel.add(this.offlineButton, BorderLayout.CENTER);
      this.add(buttonPanel);

      this.progressBar.setIndeterminate(true);
      this.progressBar.setVisible(false);
      this.progressBar.setAlignmentX(LEFT_ALIGNMENT);
      this.progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));
      this.progressBar.setBorder(BorderFactory.createEmptyBorder());
      this.add(Box.createVerticalStrut(6));
      this.add(this.progressBar);
   }

   private void styleOfflineButton() {
      this.offlineButton.setUI(new BasicButtonUI());
      this.offlineButton.setFont(this.offlineButton.getFont().deriveFont(Font.BOLD, 14.0F));
      this.offlineButton.setForeground(Color.WHITE);
      this.offlineButton.setBackground(BUTTON_NORMAL);
      this.offlineButton.setFocusPainted(false);
      this.offlineButton.setContentAreaFilled(true);
      this.offlineButton.setOpaque(true);
      this.offlineButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      this.offlineButton.setPreferredSize(new Dimension(300, 40));
      this.offlineButton.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BUTTON_BORDER, 2),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)
      ));

      this.offlineButton.addMouseListener(new MouseAdapter() {
         @Override
         public void mouseEntered(MouseEvent e) {
            if (LogInPopup.this.offlineButton.isEnabled()) {
               LogInPopup.this.offlineButton.setBackground(BUTTON_HOVER);
            }
         }

         @Override
         public void mouseExited(MouseEvent e) {
            if (LogInPopup.this.offlineButton.isEnabled()) {
               LogInPopup.this.offlineButton.setBackground(BUTTON_NORMAL);
            }
         }

         @Override
         public void mousePressed(MouseEvent e) {
            if (LogInPopup.this.offlineButton.isEnabled()) {
               LogInPopup.this.offlineButton.setBackground(BUTTON_PRESSED);
            }
         }

         @Override
         public void mouseReleased(MouseEvent e) {
            if (LogInPopup.this.offlineButton.isEnabled()) {
               LogInPopup.this.offlineButton.setBackground(
                     LogInPopup.this.offlineButton.contains(e.getPoint()) ? BUTTON_HOVER : BUTTON_NORMAL
               );
            }
         }
      });
   }

   @Override
   protected void paintComponent(Graphics graphics) {
      Graphics2D g = (Graphics2D)graphics.create();
      try {
         g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

         int x = 5;
         int y = 5;
         int width = getWidth() - 10;
         int height = getHeight() - 10;

         // Soft shadow so the card separates from the dirt texture.
         g.setColor(new Color(0, 0, 0, 95));
         g.fillRoundRect(x + 4, y + 5, width, height, 12, 12);

         g.setColor(CARD_BACKGROUND);
         g.fillRoundRect(x, y, width, height, 12, 12);

         g.setStroke(new BasicStroke(1.0F));
         g.setColor(CARD_BORDER);
         g.drawRoundRect(x, y, width - 1, height - 1, 12, 12);
      } finally {
         g.dispose();
      }

      super.paintComponent(graphics);
   }

   @Override
   public void actionPerformed(ActionEvent e) {
      if (e.getSource() == this.offlineButton) {
         this.playOffline();
      }
   }

   public void playOffline() {
      this.minecraftLauncher.enableOfflineMode(this.logInForm.getEnteredUsername());
   }

   public Launcher getMinecraftLauncher() {
      return this.minecraftLauncher;
   }

   public void setCanLogIn(final boolean enabled) {
      if (SwingUtilities.isEventDispatchThread()) {
         this.offlineButton.setEnabled(enabled);
         this.offlineButton.setBackground(enabled ? BUTTON_NORMAL : new Color(80, 80, 80));
         this.progressBar.setVisible(!enabled);
         this.repack();
      } else {
         SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
               LogInPopup.this.setCanLogIn(enabled);
            }
         });
      }
   }

   public LogInForm getLogInForm() {
      return this.logInForm;
   }

   public AuthErrorForm getErrorForm() {
      return this.errorForm;
   }

   public ExistingUserListForm getExistingUserListForm() {
      return this.existingUserListForm;
   }

   public void setLoggedIn(String uuid) {
      this.callback.onLogIn(uuid);
   }

   public void repack() {
      Window window = SwingUtilities.windowForComponent(this);
      if (window != null) {
         window.pack();
      }
   }

   public interface Callback {
      void onLogIn(String uuid);
   }
}
