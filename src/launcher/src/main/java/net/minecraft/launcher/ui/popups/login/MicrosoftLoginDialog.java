package net.minecraft.launcher.ui.popups.login;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.launcher.OperatingSystem;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dialog.ModalityType;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import net.minecraft.launcher.auth.MicrosoftAuthenticator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Shows the code the player types in at microsoft.com/link and waits for them
 * to finish signing in there.
 */
public class MicrosoftLoginDialog extends JDialog {
   private static final Logger LOGGER = LogManager.getLogger();
   private final LogInPopup popup;
   private final AtomicBoolean finished = new AtomicBoolean(false);
   private final JLabel pageLabel = new JLabel(" ");
   private final JLabel codeLabel = new JLabel(" ", SwingConstants.CENTER);
   private final JLabel statusLabel = new JLabel("Asking Microsoft for a login code...");
   private final JProgressBar progressBar = new JProgressBar();
   private final JButton copyButton = new JButton("Copy code");
   private final JButton openButton = new JButton("Open page");
   private final JButton cancelButton = new JButton("Cancel");
   private volatile MicrosoftAuthenticator.DeviceCode deviceCode;

   public MicrosoftLoginDialog(LogInPopup popup) {
      super(SwingUtilities.getWindowAncestor(popup), "Log in with Microsoft", ModalityType.MODELESS);
      this.popup = popup;
      this.createInterface();
      this.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
      this.addWindowListener(new WindowAdapter() {
         @Override
         public void windowClosing(WindowEvent e) {
            MicrosoftLoginDialog.this.cancel();
         }
      });
   }

   protected void createInterface() {
      JPanel content = new JPanel(new GridBagLayout());
      content.setBorder(BorderFactory.createEmptyBorder(12, 15, 12, 15));
      GridBagConstraints constraints = new GridBagConstraints();
      constraints.fill = GridBagConstraints.HORIZONTAL;
      constraints.gridx = 0;
      constraints.gridy = GridBagConstraints.RELATIVE;
      constraints.weightx = 1.0;

      JLabel step1 = new JLabel("1. Go to this page in your browser:");
      Font labelFont = step1.getFont().deriveFont(Font.BOLD);
      step1.setFont(labelFont);
      content.add(step1, constraints);
      this.pageLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
      this.pageLabel.addMouseListener(new MouseAdapter() {
         @Override
         public void mouseClicked(MouseEvent e) {
            MicrosoftLoginDialog.this.openPage();
         }
      });
      constraints.insets = new Insets(0, 14, 8, 0);
      content.add(this.pageLabel, constraints);

      constraints.insets = new Insets(0, 0, 0, 0);
      JLabel step2 = new JLabel("2. Enter this code and log in:");
      step2.setFont(labelFont);
      content.add(step2, constraints);
      this.codeLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 26));
      constraints.insets = new Insets(6, 0, 2, 0);
      content.add(this.codeLabel, constraints);
      JLabel copied = new JLabel("(It's already copied, so you can just paste it.)", SwingConstants.CENTER);
      copied.setFont(copied.getFont().deriveFont(copied.getFont().getSize() - 2.0F));
      constraints.insets = new Insets(0, 0, 10, 0);
      content.add(copied, constraints);

      constraints.insets = new Insets(0, 0, 4, 0);
      content.add(this.statusLabel, constraints);
      this.progressBar.setIndeterminate(true);
      content.add(this.progressBar, constraints);

      JPanel buttons = new JPanel();
      this.copyButton.setEnabled(false);
      this.openButton.setEnabled(false);
      this.copyButton.addActionListener(e -> this.copyCode());
      this.openButton.addActionListener(e -> this.openPage());
      this.cancelButton.addActionListener(e -> this.cancel());
      buttons.add(this.copyButton);
      buttons.add(this.openButton);
      buttons.add(this.cancelButton);

      this.getContentPane().setLayout(new BorderLayout());
      this.getContentPane().add(content, BorderLayout.CENTER);
      this.getContentPane().add(buttons, BorderLayout.SOUTH);
      this.pack();
      this.setMinimumSize(this.getSize());
      this.setResizable(false);
   }

   public void start() {
      this.setLocationRelativeTo(this.getOwner());
      this.setVisible(true);
      Thread thread = new Thread(this::run, "Microsoft login");
      thread.setDaemon(true);
      thread.start();
   }

   private void run() {
      MicrosoftAuthenticator authenticator = new MicrosoftAuthenticator(this.popup.getMinecraftLauncher().getLauncher().getProxy());
      try {
         final MicrosoftAuthenticator.DeviceCode code = authenticator.requestDeviceCode();
         this.deviceCode = code;
         SwingUtilities.invokeLater(() -> {
            this.pageLabel.setText("<html><a href=''>" + code.getVerificationUri() + "</a></html>");
            this.codeLabel.setText(code.getUserCode());
            this.statusLabel.setText("Waiting for you to finish logging in...");
            this.copyButton.setEnabled(true);
            this.openButton.setEnabled(true);
            this.pack();
            this.copyCode();
            this.openPage();
         });

         final MicrosoftAuthenticator.Session session = authenticator.waitForDeviceLogin(code, this.finished);
         if (session != null && this.finished.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(() -> {
               this.dispose();
               this.popup.onMicrosoftLogin(session);
            });
         }
      } catch (final AuthenticationException e) {
         LOGGER.warn("Microsoft login failed", e);
         if (this.finished.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(() -> {
               this.dispose();
               this.popup.getErrorForm().displayError(null, "Couldn't log in with Microsoft.", e.getMessage());
               this.popup.setCanLogIn(true);
            });
         }
      }
   }

   private void copyCode() {
      MicrosoftAuthenticator.DeviceCode code = this.deviceCode;
      if (code != null) {
         try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code.getUserCode()), null);
         } catch (IllegalStateException e) {
            LOGGER.debug("Clipboard busy; couldn't copy login code", e);
         }
      }
   }

   private void openPage() {
      MicrosoftAuthenticator.DeviceCode code = this.deviceCode;
      if (code != null) {
         OperatingSystem.openLink(URI.create(code.getVerificationUri()));
      }
   }

   private void cancel() {
      if (this.finished.compareAndSet(false, true)) {
         this.dispose();
         this.popup.setCanLogIn(true);
      }
   }
}
