package net.minecraft.launcher.ui.tabs.website;

import java.awt.Component;
import java.awt.Dimension;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reflection-based JavaFX WebView wrapper.
 *
 * Keeping JavaFX behind reflection means the launcher still compiles and runs
 * on a plain JDK 21 install. If JavaFX cannot be loaded, WebsiteTab falls back
 * to LegacySwingBrowser.
 */
public class ModernJFXBrowser implements Browser {
   private static final Logger LOGGER = LogManager.getLogger();
   private final ClassLoader loader;
   private final Component fxPanel;
   private volatile Object webView;
   private volatile Object webEngine;
   private volatile String pendingUrl;
   private volatile Dimension pendingSize;

   public ModernJFXBrowser(ClassLoader loader) throws Exception {
      this.loader = loader;
      Class<?> jfxPanelClass = Class.forName("javafx.embed.swing.JFXPanel", true, loader);
      this.fxPanel = (Component)jfxPanelClass.getDeclaredConstructor().newInstance();
      this.runLater(new Runnable() {
         @Override
         public void run() {
            ModernJFXBrowser.this.initializeBrowser();
         }
      });
   }

   private void initializeBrowser() {
      try {
         Class<?> webViewClass = Class.forName("javafx.scene.web.WebView", true, this.loader);
         Class<?> parentClass = Class.forName("javafx.scene.Parent", true, this.loader);
         Class<?> sceneClass = Class.forName("javafx.scene.Scene", true, this.loader);

         Object view = webViewClass.getDeclaredConstructor().newInstance();
         webViewClass.getMethod("setContextMenuEnabled", boolean.class).invoke(view, Boolean.FALSE);
         Object engine = webViewClass.getMethod("getEngine").invoke(view);
         engine.getClass().getMethod("setJavaScriptEnabled", boolean.class).invoke(engine, Boolean.TRUE);

         Constructor<?> sceneConstructor = sceneClass.getConstructor(parentClass);
         Object scene = sceneConstructor.newInstance(view);
         this.fxPanel.getClass().getMethod("setScene", sceneClass).invoke(this.fxPanel, scene);

         this.webView = view;
         this.webEngine = engine;

         Dimension size = this.pendingSize;
         if (size != null) {
            this.applySize(size);
         }

         String url = this.pendingUrl;
         if (url != null) {
            this.loadOnFxThread(url);
         }
      } catch (Throwable e) {
         LOGGER.error("Could not initialize JavaFX WebView", e);
      }
   }

   private void runLater(Runnable runnable) throws Exception {
      Class<?> platformClass = Class.forName("javafx.application.Platform", true, this.loader);
      Method runLater = platformClass.getMethod("runLater", Runnable.class);
      runLater.invoke(null, runnable);
   }

   @Override
   public void loadUrl(final String url) {
      this.pendingUrl = url;
      if (this.webEngine != null) {
         try {
            this.runLater(new Runnable() {
               @Override
               public void run() {
                  ModernJFXBrowser.this.loadOnFxThread(url);
               }
            });
         } catch (Exception e) {
            LOGGER.error("Could not schedule JavaFX page load " + url, e);
         }
      }
   }

   private void loadOnFxThread(String url) {
      try {
         this.webEngine.getClass().getMethod("load", String.class).invoke(this.webEngine, url);
      } catch (Throwable e) {
         LOGGER.error("Could not load JavaFX page " + url, e);
      }
   }

   @Override
   public Component getComponent() {
      return this.fxPanel;
   }

   @Override
   public void resize(final Dimension size) {
      this.pendingSize = size;
      if (this.webView != null) {
         try {
            this.runLater(new Runnable() {
               @Override
               public void run() {
                  ModernJFXBrowser.this.applySize(size);
               }
            });
         } catch (Exception e) {
            LOGGER.debug("Could not schedule JavaFX resize", e);
         }
      }
   }

   private void applySize(Dimension size) {
      try {
         Method setMinSize = this.webView.getClass().getMethod("setMinSize", double.class, double.class);
         Method setPrefSize = this.webView.getClass().getMethod("setPrefSize", double.class, double.class);
         Method setMaxSize = this.webView.getClass().getMethod("setMaxSize", double.class, double.class);
         double width = Math.max(0.0, size.getWidth());
         double height = Math.max(0.0, size.getHeight());
         setMinSize.invoke(this.webView, width, height);
         setPrefSize.invoke(this.webView, width, height);
         setMaxSize.invoke(this.webView, width, height);
      } catch (Throwable e) {
         LOGGER.debug("Could not resize JavaFX WebView", e);
      }
   }
}
