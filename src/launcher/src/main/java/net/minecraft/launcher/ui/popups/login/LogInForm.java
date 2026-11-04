package net.minecraft.launcher.ui.popups.login;

import com.mojang.launcher.OperatingSystem;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.minecraft.launcher.LauncherConstants;

/**
 * The original 1.6.44 login form layout. Mojang's email/password accounts no
 * longer exist, so where the credential fields used to be it now explains the
 * Microsoft sign-in; the button row in {@link LogInPopup} starts it.
 */
public class LogInForm extends JPanel {
   private final LogInPopup popup;

   public LogInForm(LogInPopup popup) {
      this.popup = popup;
      this.createInterface();
   }

   protected void createInterface() {
      this.setLayout(new GridBagLayout());
      GridBagConstraints constraints = new GridBagConstraints();
      constraints.fill = GridBagConstraints.HORIZONTAL;
      constraints.gridx = 0;
      constraints.gridy = GridBagConstraints.RELATIVE;
      constraints.weightx = 1.0;
      this.add(Box.createGlue());

      JLabel heading = new JLabel("Log in with your Microsoft account:");
      Font labelFont = heading.getFont().deriveFont(Font.BOLD);
      Font smalltextFont = heading.getFont().deriveFont((float)labelFont.getSize() - 2.0F);
      heading.setFont(labelFont);
      this.add(heading, constraints);
      this.add(new JLabel("Use the Microsoft account you bought Minecraft with."), constraints);
      this.add(new JLabel("Your browser will open so you can finish logging in."), constraints);

      JLabel helpLabel = new JLabel("(Need help logging in?)");
      helpLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
      helpLabel.setFont(smalltextFont);
      helpLabel.setHorizontalAlignment(JLabel.RIGHT);
      helpLabel.addMouseListener(new MouseAdapter() {
         @Override
         public void mouseClicked(MouseEvent e) {
            OperatingSystem.openLink(LauncherConstants.URL_LOGIN_HELP);
         }
      });
      this.add(helpLabel, constraints);
      this.add(Box.createVerticalStrut(10), constraints);
   }

   /** Kept for old launcher code that still calls it. */
   public void tryLogIn() {
      this.popup.logInWithMicrosoft();
   }
}
