package net.minecraft.launcher.ui.tabs;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.minecraft.launcher.Launcher;
import net.minecraft.launcher.ui.tabs.website.Browser;
import net.minecraft.launcher.ui.tabs.website.JavaFxRuntime;
import net.minecraft.launcher.ui.tabs.website.LegacySwingBrowser;
import net.minecraft.launcher.ui.tabs.website.LoadingBrowser;
import net.minecraft.launcher.ui.tabs.website.ModernJFXBrowser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class WebsiteTab extends JPanel {
   private static final Logger LOGGER = LogManager.getLogger();
   private final Launcher minecraftLauncher;
   private final AtomicBoolean javaFxUpgradeStarted = new AtomicBoolean(false);
   private volatile Browser browser;
   private volatile String currentUrl;

   public WebsiteTab(Launcher minecraftLauncher) {
      this.minecraftLauncher = minecraftLauncher;
      this.browser = new LoadingBrowser();
      this.setLayout(new BorderLayout());
      this.add(this.browser.getComponent(), BorderLayout.CENTER);
      this.browser.resize(this.getSize());
      this.addComponentListener(new ComponentAdapter() {
         @Override
         public void componentResized(ComponentEvent e) {
            WebsiteTab.this.browser.resize(e.getComponent().getSize());
         }
      });

      // Begin preparing WebView as soon as the launcher UI is constructed,
      // rather than waiting for the remote page request. On subsequent runs the
      // cached JavaFX runtime usually makes this swap almost immediate.
      this.startJavaFxUpgrade();
   }

   public void setPage(String url) {
      this.currentUrl = url;

      // LoadingBrowser intentionally does not render the site. This prevents
      // the few-second flash of badly-rendered Swing HTML before JavaFX is ready.
      if (!(this.browser instanceof LoadingBrowser)) {
         this.browser.loadUrl(url);
      }
   }

   private void startJavaFxUpgrade() {
      if (!this.javaFxUpgradeStarted.compareAndSet(false, true)) {
         return;
      }

      Thread thread = new Thread("Prepare JavaFX web browser") {
         @Override
         public void run() {
            try {
               final ClassLoader javaFxLoader = JavaFxRuntime.findOrInstall(
                  WebsiteTab.this.minecraftLauncher.getLauncher().getWorkingDirectory(),
                  WebsiteTab.this.getClass().getClassLoader()
               );

               SwingUtilities.invokeLater(new Runnable() {
                  @Override
                  public void run() {
                     if (javaFxLoader != null) {
                        WebsiteTab.this.installBrowser(WebsiteTab.this.createModernBrowser(javaFxLoader));
                     } else {
                        WebsiteTab.this.installLegacyBrowser();
                     }
                  }
               });
            } catch (final Throwable e) {
               LOGGER.warn("JavaFX WebView could not be initialized; using Swing HTML fallback", e);
               SwingUtilities.invokeLater(new Runnable() {
                  @Override
                  public void run() {
                     WebsiteTab.this.installLegacyBrowser();
                  }
               });
            }
         }
      };
      thread.setDaemon(true);
      thread.start();
   }

   private Browser createModernBrowser(ClassLoader javaFxLoader) {
      try {
         return new ModernJFXBrowser(javaFxLoader);
      } catch (Throwable e) {
         LOGGER.warn("Could not create JavaFX WebView; using Swing HTML fallback", e);
         return new LegacySwingBrowser();
      }
   }

   private void installLegacyBrowser() {
      this.installBrowser(new LegacySwingBrowser());
   }

   private void installBrowser(Browser replacement) {
      Browser previous = this.browser;
      Dimension size = this.getSize();
      replacement.resize(size);

      this.remove(previous.getComponent());
      this.browser = replacement;
      this.add(replacement.getComponent(), BorderLayout.CENTER);
      this.revalidate();
      this.repaint();

      String url = this.currentUrl;
      if (url != null) {
         replacement.loadUrl(url);
      }

      if (replacement instanceof ModernJFXBrowser) {
         LOGGER.info("Update Notes tab is using JavaFX WebView");
      } else {
         LOGGER.info("Update Notes tab is using Swing HTML fallback");
      }
   }

   public Launcher getMinecraftLauncher() {
      return this.minecraftLauncher;
   }
}
