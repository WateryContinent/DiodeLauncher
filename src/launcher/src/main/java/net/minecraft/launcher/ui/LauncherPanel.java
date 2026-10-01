package net.minecraft.launcher.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.GridBagLayout;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import net.minecraft.launcher.Launcher;
import net.minecraft.launcher.ui.tabs.LauncherTabPanel;

public class LauncherPanel extends JPanel {
   public static final String CARD_DIRT_BACKGROUND = "loading";
   public static final String CARD_LOGIN = "login";
   public static final String CARD_LAUNCHER = "launcher";
   private final CardLayout cardLayout;
   private final LauncherTabPanel tabPanel;
   private final BottomBarPanel bottomBar;
   private final JProgressBar progressBar;
   private final Launcher minecraftLauncher;
   private final JPanel loginPanel;

   public LauncherPanel(Launcher minecraftLauncher) {
      this.minecraftLauncher = minecraftLauncher;
      this.cardLayout = new CardLayout();
      this.setLayout(this.cardLayout);
      this.progressBar = new JProgressBar();
      this.bottomBar = new BottomBarPanel(minecraftLauncher);
      this.tabPanel = new LauncherTabPanel(minecraftLauncher);
      this.loginPanel = new TexturedPanel("/dirt.png");
      this.createInterface();
   }

   protected void createInterface() {
      this.add(this.createLauncherInterface(), CARD_LAUNCHER);
      this.add(this.createDirtInterface(), CARD_DIRT_BACKGROUND);
      this.add(this.createLoginInterface(), CARD_LOGIN);
   }

   protected JPanel createLauncherInterface() {
      JPanel result = new JPanel(new BorderLayout());
      if (this.getMinecraftLauncher().isOfflineMode()) {
         this.tabPanel.showConsole();
      } else {
         this.tabPanel.getBlog().setPage("https://waterycontinent.github.io/DiodeLauncher/");
      }

      JPanel center = new JPanel(new BorderLayout());
      center.add(this.tabPanel, BorderLayout.CENTER);
      center.add(this.progressBar, BorderLayout.SOUTH);
      this.progressBar.setVisible(false);
      this.progressBar.setMinimum(0);
      this.progressBar.setMaximum(100);
      this.progressBar.setStringPainted(true);
      result.add(center, BorderLayout.CENTER);
      result.add(this.bottomBar, BorderLayout.SOUTH);
      return result;
   }

   protected JPanel createDirtInterface() {
      return new TexturedPanel("/dirt.png");
   }

   protected JPanel createLoginInterface() {
      this.loginPanel.setLayout(new GridBagLayout());
      return this.loginPanel;
   }

   public LauncherTabPanel getTabPanel() {
      return this.tabPanel;
   }

   public BottomBarPanel getBottomBar() {
      return this.bottomBar;
   }

   public JProgressBar getProgressBar() {
      return this.progressBar;
   }

   public Launcher getMinecraftLauncher() {
      return this.minecraftLauncher;
   }

   public void setCard(String card, JPanel additional) {
      if (CARD_LOGIN.equals(card)) {
         this.loginPanel.removeAll();
         if (additional != null) {
            this.loginPanel.add(additional);
         }
         this.loginPanel.revalidate();
         this.loginPanel.repaint();
      }

      this.cardLayout.show(this, card);
   }
}
