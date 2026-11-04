package net.minecraft.launcher.ui.popups.login;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import org.apache.commons.lang3.exception.ExceptionUtils;

public class AuthErrorForm extends JPanel {
   private final LogInPopup popup;
   private final JLabel errorLabel = new JLabel();

   public AuthErrorForm(LogInPopup popup) {
      this.popup = popup;
      this.createInterface();
      this.clear();
   }

   protected void createInterface() {
      this.setBorder(new EmptyBorder(0, 0, 15, 0));
      this.errorLabel.setFont(this.errorLabel.getFont().deriveFont(1));
      this.add(this.errorLabel);
   }

   public void clear() {
      this.setVisible(false);
   }

   @Override
   public void setVisible(boolean value) {
      super.setVisible(value);
      this.popup.repack();
   }

   public void displayError(final Throwable throwable, final String... lines) {
      if (SwingUtilities.isEventDispatchThread()) {
         String error = "";

         for (String line : lines) {
            error = error + "<p>" + line + "</p>";
         }

         if (throwable != null) {
            error = error + "<p style='font-size: 0.9em; font-style: italic;'>(" + ExceptionUtils.getRootCauseMessage(throwable) + ")</p>";
         }

         // Fixed width so long Microsoft/Xbox messages wrap instead of widening the popup.
         this.errorLabel.setText("<html><div style='text-align: center; width: 280px;'>" + error + " </div></html>");
         this.setVisible(true);
      } else {
         SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
               AuthErrorForm.this.displayError(throwable, lines);
            }
         });
      }
   }
}
