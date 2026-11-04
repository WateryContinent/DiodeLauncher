package net.minecraft.launcher.ui.tabs;

import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.minecraft.launcher.Launcher;
import net.minecraft.launcher.selfupdate.SelfUpdater;

/** Diode launcher options: currently just self-update controls. */
public class LauncherSettingsTab extends JPanel {
   private final Launcher minecraftLauncher;
   private final JCheckBox checkOnStartup = new JCheckBox("Check for launcher updates when the launcher starts");
   private final JButton checkNowButton = new JButton("Check for updates now");
   private final JButton releasesButton = new JButton("View releases");
   private final JLabel statusLabel = new JLabel();

   public LauncherSettingsTab(Launcher minecraftLauncher) {
      this.minecraftLauncher = minecraftLauncher;
      this.createInterface();
   }

   protected void createInterface() {
      final SelfUpdater updater = this.minecraftLauncher.getSelfUpdater();
      this.setLayout(new GridBagLayout());
      this.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
      GridBagConstraints constraints = new GridBagConstraints();
      constraints.gridx = 0;
      constraints.gridy = GridBagConstraints.RELATIVE;
      constraints.anchor = GridBagConstraints.WEST;
      constraints.fill = GridBagConstraints.HORIZONTAL;
      constraints.weightx = 1.0;
      constraints.insets = new Insets(2, 0, 2, 0);

      JLabel heading = new JLabel("Launcher updates");
      heading.setFont(heading.getFont().deriveFont(Font.BOLD));
      this.add(heading, constraints);
      String version = updater.getCurrentVersion();
      this.add(new JLabel("Installed version: " + (version == null ? "unknown" : version)), constraints);

      this.checkOnStartup.setSelected(updater.isCheckOnStartupEnabled());
      this.checkOnStartup.addActionListener(e -> updater.setCheckOnStartupEnabled(this.checkOnStartup.isSelected()));
      this.add(this.checkOnStartup, constraints);

      JPanel buttons = new JPanel(new GridBagLayout());
      GridBagConstraints buttonConstraints = new GridBagConstraints();
      buttonConstraints.insets = new Insets(0, 0, 0, 6);
      this.checkNowButton.addActionListener(e -> updater.checkNow());
      this.releasesButton.addActionListener(e -> updater.openReleasesPage());
      buttons.add(this.checkNowButton, buttonConstraints);
      buttons.add(this.releasesButton, buttonConstraints);
      constraints.fill = GridBagConstraints.NONE;
      this.add(buttons, constraints);
      constraints.fill = GridBagConstraints.HORIZONTAL;

      this.statusLabel.setText(updater.getStatus());
      updater.addStatusListener(status -> SwingUtilities.invokeLater(() -> this.statusLabel.setText(status)));
      this.add(this.statusLabel, constraints);

      // Push everything to the top-left of the tab.
      constraints.weighty = 1.0;
      this.add(Box.createGlue(), constraints);
   }
}
