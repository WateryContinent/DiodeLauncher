package net.minecraft.launcher.ui.tabs;

import com.mojang.launcher.OperatingSystem;
import com.mojang.launcher.versions.CompleteVersion;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import net.minecraft.launcher.Launcher;
import net.minecraft.launcher.selfupdate.SelfUpdater;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Shows a game crash report. Mojang's old automatic crash service (Hopper) is
 * gone, so reports are sent by the player instead: Diode problems as a
 * pre-filled GitHub issue, vanilla game bugs to Mojang's bug tracker.
 */
public class CrashReportTab extends JPanel {
   private static final Logger LOGGER = LogManager.getLogger();
   private static final String GITHUB_NEW_ISSUE = "https://github.com/" + SelfUpdater.UPDATE_REPOSITORY + "/issues/new";
   private static final URI MOJANG_BUG_TRACKER = URI.create("https://bugs.mojang.com/");
   // Browsers and GitHub cap URL length, so only the start of a long report
   // goes in the link; the whole report is copied to the clipboard as well.
   private static final int MAX_REPORT_IN_LINK = 3000;
   private final Launcher minecraftLauncher;
   private final CompleteVersion version;
   private final File reportFile;
   private final String report;
   private final JEditorPane reportEditor = new JEditorPane();
   private final JScrollPane scrollPane = new JScrollPane(this.reportEditor);
   private final CrashReportTab.CrashInfoPane crashInfoPane;
   private final boolean isModded;

   public CrashReportTab(Launcher minecraftLauncher, CompleteVersion version, File reportFile, String report) {
      super(true);
      this.minecraftLauncher = minecraftLauncher;
      this.version = version;
      this.reportFile = reportFile;
      this.report = report;
      this.crashInfoPane = new CrashReportTab.CrashInfoPane();
      this.isModded = !report.contains("Is Modded: Probably not") && !report.contains("Is Modded: Unknown");
      this.setLayout(new BorderLayout());
      this.createInterface();
   }

   protected void createInterface() {
      this.add(this.crashInfoPane, "North");
      this.add(this.scrollPane, "Center");
      this.reportEditor.setText(this.report);
      this.crashInfoPane.createInterface();
   }

   private void reportOnGitHub() {
      boolean truncated = this.report.length() > MAX_REPORT_IN_LINK;
      String shownReport = truncated ? this.report.substring(0, MAX_REPORT_IN_LINK) : this.report;
      StringBuilder body = new StringBuilder();
      body.append("**What were you doing when the game crashed?**\n\n\n");
      body.append("**Environment**\n").append(this.buildEnvironmentInfo()).append("\n\n");
      body.append("**Crash report**\n");
      if (truncated) {
         body.append("_The report was too long for the link, so it's cut short below. The full report was copied to your clipboard: paste it here instead._\n");
      }

      body.append("```\n").append(shownReport).append("\n```\n");
      try {
         this.copyReport();
         String title = "Minecraft " + this.version.getId() + " crashed";
         OperatingSystem.openLink(URI.create(GITHUB_NEW_ISSUE + "?title=" + encode(title) + "&body=" + encode(body.toString())));
      } catch (RuntimeException e) {
         LOGGER.error("Couldn't open GitHub issue page", e);
      }
   }

   private void reportToMojang() {
      this.copyReport();
      JOptionPane.showMessageDialog(
         this,
         "<html><p>The crash report has been copied to your clipboard.</p><p>Mojang's bug tracker will open next: search for your crash first,<br>and if it isn't there yet, log in and paste the report into a new bug.</p></html>",
         "Report to Mojang",
         JOptionPane.INFORMATION_MESSAGE
      );
      OperatingSystem.openLink(MOJANG_BUG_TRACKER);
   }

   private void copyReport() {
      try {
         Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(this.report), null);
      } catch (IllegalStateException e) {
         LOGGER.warn("Couldn't copy crash report to the clipboard", e);
      }
   }

   private String buildEnvironmentInfo() {
      return "- OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")\n"
         + "- Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")\n"
         + "- Launcher: " + this.minecraftLauncher.getUserInterface().getTitle() + " (bootstrap " + this.minecraftLauncher.getBootstrapVersion() + ")\n"
         + "- Minecraft: " + this.version.getId() + "\n"
         + "- Modded: " + (this.isModded ? "yes, or unsure" : "probably not");
   }

   private static String encode(String text) {
      try {
         // URLEncoder makes spaces '+', which GitHub shows literally in issue text.
         return URLEncoder.encode(text, "UTF-8").replace("+", "%20");
      } catch (UnsupportedEncodingException e) {
         throw new IllegalStateException(e);
      }
   }

   private class CrashInfoPane extends JPanel implements ActionListener {
      public static final String INFO_NORMAL = "<html><div style='width: 100%'><p><b>Uhoh, it looks like the game has crashed! Sorry for the inconvenience :(</b></p><p>If you think Diode caused it (for example, it works in the official launcher), please report it on GitHub. If it's a bug in Minecraft itself, report it to Mojang.</p><p>You can see the full report below. Check it doesn't contain anything private before posting it.</p></div></html>";
      public static final String INFO_MODDED = "<html><div style='width: 100%'><p><b>Uhoh, it looks like the game has crashed! Sorry for the inconvenience :(</b></p><p>We think your game may be modded, so Mojang can't accept this crash report. Please send it to the mod authors to take a look at!</p><p>If you think Diode caused it, you can still report it on GitHub.</p></div></html>";
      private final JButton githubButton = new JButton("Report on GitHub");
      private final JButton mojangButton = new JButton("Report to Mojang");
      private final JButton openFileButton = new JButton("Open report file");

      protected CrashInfoPane() {
         this.githubButton.addActionListener(this);
         this.mojangButton.addActionListener(this);
         this.openFileButton.addActionListener(this);
      }

      protected void createInterface() {
         this.setLayout(new GridBagLayout());
         GridBagConstraints constraints = new GridBagConstraints();
         constraints.anchor = 13;
         constraints.fill = 2;
         constraints.insets = new Insets(2, 2, 2, 2);
         constraints.gridx = 1;
         this.add(this.githubButton, constraints);
         constraints.gridy = 1;
         this.add(this.mojangButton, constraints);
         constraints.gridy = 2;
         this.add(this.openFileButton, constraints);
         constraints.gridx = 0;
         constraints.gridy = 0;
         constraints.weightx = 1.0;
         constraints.weighty = 1.0;
         constraints.gridheight = 3;
         this.add(new JLabel(CrashReportTab.this.isModded ? INFO_MODDED : INFO_NORMAL), constraints);
         if (CrashReportTab.this.isModded) {
            this.mojangButton.setEnabled(false);
         }
      }

      @Override
      public void actionPerformed(ActionEvent e) {
         if (e.getSource() == this.githubButton) {
            CrashReportTab.this.reportOnGitHub();
         } else if (e.getSource() == this.mojangButton) {
            CrashReportTab.this.reportToMojang();
         } else if (e.getSource() == this.openFileButton) {
            OperatingSystem.openLink(CrashReportTab.this.reportFile.toURI());
         }
      }
   }
}
