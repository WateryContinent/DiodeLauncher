package net.minecraft.launcher.ui.tabs.website;

import com.mojang.launcher.OperatingSystem;
import java.awt.Component;
import java.awt.Dimension;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.function.Consumer;
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
   private volatile String loadedLocation;
   private volatile String lastOpenedLink;
   private volatile long lastOpenedAt;

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
         this.openLinksExternally(engine);

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

   /**
    * Links clicked on the news page open in the player's normal browser, like
    * the legacy Swing browser, instead of navigating the tab away. #anchor
    * links within the page still scroll as usual.
    */
   private void openLinksExternally(final Object engine) throws Exception {
      Class<?> listenerClass = Class.forName("javafx.beans.value.ChangeListener", true, this.loader);
      Method addListener = Class.forName("javafx.beans.value.ObservableValue", true, this.loader).getMethod("addListener", listenerClass);
      // WebEngine's load worker is a private class, so go through the public interface.
      Class<?> workerClass = Class.forName("javafx.concurrent.Worker", true, this.loader);
      final Object worker = engine.getClass().getMethod("getLoadWorker").invoke(engine);
      final Method getLocation = engine.getClass().getMethod("getLocation");
      final Method load = engine.getClass().getMethod("load", String.class);
      final Method cancel = workerClass.getMethod("cancel");

      // Remember where the page ended up (after any redirects) once it has loaded.
      addListener.invoke(workerClass.getMethod("stateProperty").invoke(worker), this.changeListener(listenerClass, new Consumer<Object>() {
         @Override
         public void accept(Object state) {
            if ("SUCCEEDED".equals(String.valueOf(state))) {
               try {
                  ModernJFXBrowser.this.loadedLocation = (String)getLocation.invoke(engine);
               } catch (Exception e) {
                  LOGGER.debug("Could not read JavaFX page location", e);
               }
            }
         }
      }));

      addListener.invoke(engine.getClass().getMethod("locationProperty").invoke(engine), this.changeListener(listenerClass, new Consumer<Object>() {
         @Override
         public void accept(Object newLocation) {
            final String target = (String)newLocation;
            final String home = ModernJFXBrowser.this.loadedLocation;
            if (home == null || target == null || target.isEmpty() || isSameDocument(target, home)) {
               return;
            }

            // WebKit reports the same navigation again after it is cancelled;
            // only open the browser once per click.
            long now = System.currentTimeMillis();
            boolean repeat = target.equals(ModernJFXBrowser.this.lastOpenedLink) && now - ModernJFXBrowser.this.lastOpenedAt < 3000L;
            ModernJFXBrowser.this.lastOpenedLink = target;
            ModernJFXBrowser.this.lastOpenedAt = now;
            if (!repeat) {
               LOGGER.info("Opening link in browser: " + target);
               try {
                  OperatingSystem.openLink(new URI(target));
               } catch (Exception e) {
                  LOGGER.error("Couldn't open link " + target, e);
               }
            }

            // Location listeners can't veto navigation, so stop it once the
            // current event finishes and go back to the news page if needed.
            try {
               ModernJFXBrowser.this.runLater(new Runnable() {
                  @Override
                  public void run() {
                     try {
                        cancel.invoke(worker);
                        if (!isSameDocument(String.valueOf(getLocation.invoke(engine)), home)) {
                           load.invoke(engine, home);
                        }
                     } catch (Exception e) {
                        LOGGER.error("Could not return to the news page", e);
                     }
                  }
               });
            } catch (Exception e) {
               LOGGER.error("Could not schedule return to the news page", e);
            }
         }
      }));
   }

   /** Implements javafx.beans.value.ChangeListener, passing on just the new value. */
   private Object changeListener(Class<?> listenerClass, final Consumer<Object> onChange) {
      return Proxy.newProxyInstance(this.loader, new Class<?>[]{listenerClass}, new InvocationHandler() {
         @Override
         public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
               case "changed":
                  onChange.accept(args[2]);
                  return null;
               case "equals":
                  return proxy == args[0];
               case "hashCode":
                  return System.identityHashCode(proxy);
               default:
                  return "ModernJFXBrowser link listener";
            }
         }
      });
   }

   private static boolean isSameDocument(String a, String b) {
      return stripFragment(a).equals(stripFragment(b));
   }

   private static String stripFragment(String url) {
      int hash = url.indexOf('#');
      return hash < 0 ? url : url.substring(0, hash);
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
      this.loadedLocation = null;
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
