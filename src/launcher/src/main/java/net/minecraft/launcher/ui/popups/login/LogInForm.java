package net.minecraft.launcher.ui.popups.login;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Minimal offline login form used by the restored launcher.
 * The original Mojang username/password service no longer exists, so the first
 * screen only asks for a local player name.
 */
public class LogInForm extends JPanel implements ActionListener {
   private static final Color LABEL_COLOR = new Color(238, 238, 238);
   private static final Color FIELD_BACKGROUND = new Color(247, 247, 247);
   private static final Color FIELD_FOREGROUND = new Color(32, 32, 32);
   private static final Color FIELD_BORDER = new Color(78, 78, 78);

   private final LogInPopup popup;
   private final JTextField usernameField = new JTextField("Player", 22);

   public LogInForm(LogInPopup popup) {
      this.popup = popup;
      this.usernameField.addActionListener(this);
      this.createInterface();

      SwingUtilities.invokeLater(new Runnable() {
         @Override
         public void run() {
            LogInForm.this.usernameField.requestFocusInWindow();
            LogInForm.this.usernameField.selectAll();
         }
      });
   }

   protected void createInterface() {
      this.setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
      this.setOpaque(false);

      JLabel nameLabel = new JLabel("Name");
      nameLabel.setForeground(LABEL_COLOR);
      nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 13.0F));
      nameLabel.setAlignmentX(LEFT_ALIGNMENT);

      this.usernameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
      this.usernameField.setPreferredSize(new Dimension(300, 36));
      this.usernameField.setMinimumSize(new Dimension(220, 36));
      this.usernameField.setFont(this.usernameField.getFont().deriveFont(Font.PLAIN, 14.0F));
      this.usernameField.setForeground(FIELD_FOREGROUND);
      this.usernameField.setBackground(FIELD_BACKGROUND);
      this.usernameField.setCaretColor(FIELD_FOREGROUND);
      this.usernameField.setSelectionColor(new Color(89, 135, 206));
      this.usernameField.setSelectedTextColor(Color.WHITE);
      this.usernameField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(FIELD_BORDER, 1),
            BorderFactory.createEmptyBorder(7, 9, 7, 9)
      ));
      this.usernameField.setAlignmentX(LEFT_ALIGNMENT);

      this.add(nameLabel);
      this.add(javax.swing.Box.createVerticalStrut(5));
      this.add(this.usernameField);
   }

   @Override
   public void actionPerformed(ActionEvent e) {
      this.popup.playOffline();
   }

   public String getEnteredUsername() {
      return this.usernameField.getText();
   }

   /** Kept for source/binary compatibility with old launcher code. */
   public void tryLogIn() {
      this.popup.playOffline();
   }
}
