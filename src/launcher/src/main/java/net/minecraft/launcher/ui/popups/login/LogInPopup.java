package net.minecraft.launcher.ui.popups.login;

import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.minecraft.launcher.Launcher;

public class LogInPopup extends JPanel implements ActionListener {
   private final Launcher minecraftLauncher;
   private final LogInPopup.Callback callback;

   // Retained because a few old helper classes still reference these accessors.
   // Neither form is shown during normal offline-name login.
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
      // These are the same layout/border values used by the original launcher.
      this.setOpaque(false);
      this.setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
      this.setBorder(new EmptyBorder(5, 15, 5, 15));

      try (InputStream stream = LogInPopup.class.getResourceAsStream("/minecraft_logo.png")) {
         if (stream != null) {
            BufferedImage image = ImageIO.read(stream);
            JLabel label = new JLabel(new ImageIcon(image));
            JPanel imagePanel = new JPanel();
            imagePanel.setOpaque(false);
            imagePanel.add(label);
            this.add(imagePanel);
            this.add(Box.createVerticalStrut(10));
         }
      } catch (IOException e) {
         e.printStackTrace();
      }

      // Keep the original place for authentication errors for binary/source
      // compatibility, although local offline login normally never displays it.
      this.errorForm.setOpaque(false);
      this.logInForm.setOpaque(false);
      this.add(this.errorForm);
      this.add(this.logInForm);
      this.add(Box.createVerticalStrut(15));

      // Same original button row, now containing only the one useful action.
      JPanel buttonPanel = new JPanel();
      buttonPanel.setOpaque(false);
      buttonPanel.setLayout(new GridLayout(1, 1, 10, 0));
      buttonPanel.add(this.offlineButton);
      this.add(buttonPanel);

      this.progressBar.setIndeterminate(true);
      this.progressBar.setVisible(false);
      this.add(this.progressBar);
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
         this.progressBar.setIndeterminate(false);
         this.progressBar.setIndeterminate(true);
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
