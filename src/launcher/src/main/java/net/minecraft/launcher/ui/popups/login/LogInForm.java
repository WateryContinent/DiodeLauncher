package net.minecraft.launcher.ui.popups.login;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.UserAuthentication;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import net.minecraft.launcher.profile.ProfileManager;

/**
 * The original 1.6.44 login form, reduced to the one thing that still makes
 * sense for the restored launcher: a local player name.
 */
public class LogInForm extends JPanel implements ActionListener {
   private final LogInPopup popup;
   private final JTextField usernameField;

   public LogInForm(LogInPopup popup) {
      this.popup = popup;
      this.usernameField = new JTextField(this.getSuggestedUsername());
      this.usernameField.addActionListener(this);
      this.createInterface();
   }

   protected void createInterface() {
      // Deliberately keep the original 1.6.44 GridBag/Swing styling.  The only
      // UI changes are the label text and removal of obsolete account fields.
      this.setOpaque(false);
      this.setLayout(new GridBagLayout());
      GridBagConstraints constraints = new GridBagConstraints();
      constraints.fill = GridBagConstraints.HORIZONTAL;
      constraints.gridx = 0;
      constraints.gridy = GridBagConstraints.RELATIVE;
      constraints.weightx = 1.0;

      this.add(Box.createGlue());

      JLabel usernameLabel = new JLabel("Name:");
      Font labelFont = usernameLabel.getFont().deriveFont(Font.BOLD);
      usernameLabel.setFont(labelFont);
      this.add((Component)usernameLabel, constraints);
      this.add((Component)this.usernameField, constraints);
      this.add(Box.createVerticalStrut(10), constraints);
   }

   private String getSuggestedUsername() {
      try {
         ProfileManager profileManager = this.popup.getMinecraftLauncher().getProfileManager();
         String selectedUser = profileManager.getSelectedUser();
         if (selectedUser != null) {
            UserAuthentication authentication = profileManager.getAuthDatabase().getByUUID(selectedUser);
            if (authentication != null) {
               GameProfile selectedProfile = authentication.getSelectedProfile();
               if (selectedProfile != null) {
                  String name = selectedProfile.getName();
                  if (name != null && !name.trim().isEmpty()) {
                     return name;
                  }
               }
            }
         }
      } catch (RuntimeException ignored) {
         // A damaged/old profile file should never prevent the login screen
         // from being shown.  Fall through to the historical default below.
      }

      return "Player";
   }

   @Override
   public void actionPerformed(ActionEvent e) {
      if (e.getSource() == this.usernameField) {
         this.popup.playOffline();
      }
   }

   public String getEnteredUsername() {
      return this.usernameField.getText();
   }

   /** Kept for compatibility with old launcher code that still calls it. */
   public void tryLogIn() {
      this.popup.playOffline();
   }
}
