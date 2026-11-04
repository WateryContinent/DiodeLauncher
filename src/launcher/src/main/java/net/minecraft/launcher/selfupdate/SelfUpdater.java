package net.minecraft.launcher.selfupdate;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import com.mojang.launcher.OperatingSystem;
import com.mojang.launcher.updater.DownloadProgress;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import net.minecraft.launcher.Launcher;
import net.minecraft.launcher.LauncherConstants;
import net.minecraft.launcher.LauncherSettings;
import net.minecraft.launcher.SwingUserInterface;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Checks GitHub Releases for a newer Diode launcher.jar and installs it.
 *
 * <p>A running launcher.jar can't be overwritten on Windows, so the new jar is
 * downloaded beside it as {@code launcher.jar.new}. When the launcher exits,
 * a copy of the current jar runs {@link UpdateApplier} in a separate JVM,
 * which waits for this process to finish, swaps the jars and optionally
 * starts the launcher again.</p>
 */
public class SelfUpdater {
   private static final Logger LOGGER = LogManager.getLogger();
   public static final String UPDATE_REPOSITORY = "WateryContinent/DiodeLauncher";
   public static final String RELEASES_PAGE = "https://github.com/" + UPDATE_REPOSITORY + "/releases";
   // -Ddiode.updateUrl=... points the checker at a fork or a local test server.
   private static final String UPDATE_URL = System.getProperty(
      "diode.updateUrl", "https://api.github.com/repos/" + UPDATE_REPOSITORY + "/releases/latest"
   );
   private static final String UPDATER_JAR_PREFIX = "diode-updater-";
   private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)*)");
   private final Launcher minecraftLauncher;
   private final LauncherSettings settings;
   private final Proxy proxy;
   private final File workingDirectory;
   private final String[] launcherArgs;
   private final String currentVersion;
   private final File runningJar;
   private final Gson gson = new Gson();
   private final AtomicBoolean busy = new AtomicBoolean(false);
   private final List<SelfUpdater.StatusListener> statusListeners = new CopyOnWriteArrayList<>();
   private volatile String status;
   private File stagedJar;
   private String stagedVersion;
   private boolean updaterStarted;
   private boolean shutdownHookRegistered;

   public SelfUpdater(Launcher minecraftLauncher, LauncherSettings settings, Proxy proxy, File workingDirectory, String[] launcherArgs) {
      this.minecraftLauncher = minecraftLauncher;
      this.settings = settings;
      this.proxy = proxy == null ? Proxy.NO_PROXY : proxy;
      this.workingDirectory = workingDirectory;
      this.launcherArgs = launcherArgs == null ? new String[0] : launcherArgs.clone();
      this.currentVersion = extractVersion(LauncherConstants.getVersionName());
      this.runningJar = findRunningJar();
      cleanUpOldUpdaterJars();
      this.recoverStagedUpdate();
      if (this.stagedJar == null) {
         this.status = this.getUnsupportedReason() == null ? "Not checked yet." : this.getUnsupportedReason();
      }
   }

   public String getCurrentVersion() {
      return this.currentVersion;
   }

   public String getStatus() {
      return this.status;
   }

   public void addStatusListener(SelfUpdater.StatusListener listener) {
      this.statusListeners.add(listener);
   }

   public boolean isCheckOnStartupEnabled() {
      return this.settings.isCheckForUpdates();
   }

   public void setCheckOnStartupEnabled(boolean enabled) {
      this.settings.setCheckForUpdates(enabled);
      LOGGER.info("Launcher update checks on startup " + (enabled ? "enabled" : "disabled"));
   }

   /** Called once the launcher window is up; respects the user's toggle. */
   public void checkOnStartup() {
      if (!this.settings.isCheckForUpdates()) {
         LOGGER.info("Launcher update check skipped (disabled in Launcher Settings)");
      } else if (this.minecraftLauncher.isOfflineMode()) {
         LOGGER.info("Launcher update check skipped (offline mode)");
      } else if (this.stagedJar != null) {
         LOGGER.info("Diode " + this.stagedVersion + " is already downloaded and will be installed when the launcher closes");
      } else {
         this.startCheck(false);
      }
   }

   /** Called from the "Check for updates now" button. */
   public void checkNow() {
      if (this.stagedJar != null) {
         this.promptRestart();
      } else {
         this.startCheck(true);
      }
   }

   private void startCheck(final boolean manual) {
      String unsupportedReason = this.getUnsupportedReason();
      if (unsupportedReason != null) {
         this.setStatus(unsupportedReason);
         return;
      }

      if (!this.busy.compareAndSet(false, true)) {
         return;
      }

      Thread thread = new Thread(() -> {
         try {
            this.runCheck(manual);
         } finally {
            this.busy.set(false);
         }
      }, "Launcher update check");
      thread.setDaemon(true);
      thread.start();
   }

   private void runCheck(boolean manual) {
      this.setStatus("Checking for updates...");
      SelfUpdater.Release release;
      try {
         release = this.fetchLatestRelease();
      } catch (IOException | JsonParseException e) {
         LOGGER.warn("Couldn't check for launcher updates", e);
         this.setStatus("Couldn't check for updates: " + e.getMessage());
         return;
      }

      if (release == null) {
         this.setStatus("No releases have been published yet.");
         return;
      }

      String latestVersion = extractVersion(release.tagName);
      if (latestVersion == null || compareVersions(latestVersion, this.currentVersion) <= 0) {
         LOGGER.info("Launcher is up to date (" + this.currentVersion + ", latest release " + release.tagName + ")");
         this.setStatus("You're up to date (" + this.currentVersion + ").");
         return;
      }

      SelfUpdater.Asset asset = release.findLauncherJar();
      if (asset == null) {
         LOGGER.warn("Release " + release.tagName + " has no launcher.jar asset");
         this.setStatus("Diode " + latestVersion + " is out, but its release has no launcher.jar to download.");
         return;
      }

      LOGGER.info("Diode " + latestVersion + " is available (running " + this.currentVersion + ")");
      this.setStatus("Diode " + latestVersion + " is available.");
      if (!manual && latestVersion.equals(this.settings.getSkippedUpdateVersion())) {
         LOGGER.info("Not prompting for " + latestVersion + "; the user chose to skip it");
         return;
      }

      if (this.promptForUpdate(release, latestVersion, manual)) {
         this.downloadAndStage(asset, latestVersion);
      }
   }

   private SelfUpdater.Release fetchLatestRelease() throws IOException {
      HttpURLConnection connection = this.openConnection(new URL(UPDATE_URL));
      // "full" also returns the release notes already rendered from Markdown to HTML (body_html).
      connection.setRequestProperty("Accept", "application/vnd.github.full+json");
      int code = connection.getResponseCode();
      if (code == 404) {
         return null;
      }

      if (code / 100 != 2) {
         throw new IOException("GitHub replied with HTTP " + code);
      }

      try (InputStream in = connection.getInputStream()) {
         return this.gson.fromJson(IOUtils.toString(in, StandardCharsets.UTF_8), SelfUpdater.Release.class);
      }
   }

   /** Returns true if the user wants to download the update now. */
   private boolean promptForUpdate(SelfUpdater.Release release, String latestVersion, boolean manual) {
      String[] options = manual ? new String[]{"Update now", "Not now"} : new String[]{"Update now", "Remind me later", "Skip this version"};
      int choice = this.showOptionDialog(this.buildUpdateMessage(release, latestVersion), "Launcher update available", options);
      if (choice == 2) {
         this.settings.setSkippedUpdateVersion(latestVersion);
         this.setStatus("Skipped Diode " + latestVersion + ". Use \"Check for updates now\" to install it later.");
      }

      return choice == 0;
   }

   private void downloadAndStage(SelfUpdater.Asset asset, String version) {
      File directory = this.runningJar.getAbsoluteFile().getParentFile();
      File partFile = new File(directory, this.runningJar.getName() + ".part");
      File newFile = new File(directory, this.runningJar.getName() + ".new");
      this.setStatus("Downloading Diode " + version + "...");
      LOGGER.info("Downloading launcher update from " + asset.downloadUrl);

      try {
         HttpURLConnection connection = this.openConnection(new URL(asset.downloadUrl));
         int code = connection.getResponseCode();
         if (code / 100 != 2) {
            throw new IOException("Download failed with HTTP " + code);
         }

         long total = connection.getContentLengthLong() > 0 ? connection.getContentLengthLong() : asset.size;
         MessageDigest digest = MessageDigest.getInstance("SHA-256");
         long downloaded = 0L;
         try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(partFile)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) >= 0) {
               out.write(buffer, 0, read);
               digest.update(buffer, 0, read);
               downloaded += read;
               if (total > 0L) {
                  this.minecraftLauncher.getUserInterface().setDownloadProgress(
                     new DownloadProgress(downloaded, total, "Downloading Diode " + version)
                  );
               }
            }
         } finally {
            this.minecraftLauncher.getUserInterface().hideDownloadProgress();
         }

         if (asset.size > 0L && downloaded != asset.size) {
            throw new IOException("Downloaded " + downloaded + " bytes but expected " + asset.size);
         }

         // GitHub publishes a sha256 for every release asset; older API
         // responses may not include it, in which case size + jar checks remain.
         if (asset.digest != null && asset.digest.toLowerCase(Locale.ROOT).startsWith("sha256:")) {
            String expected = asset.digest.substring("sha256:".length()).toLowerCase(Locale.ROOT);
            String actual = String.format("%064x", new BigInteger(1, digest.digest()));
            if (!expected.equals(actual)) {
               throw new IOException("SHA-256 mismatch (expected " + expected + ", got " + actual + ")");
            }
         }

         verifyLauncherJar(partFile);
         Files.move(partFile.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException | NoSuchAlgorithmException e) {
         LOGGER.error("Couldn't download launcher update", e);
         partFile.delete();
         this.setStatus("Update download failed: " + e.getMessage());
         return;
      }

      LOGGER.info("Diode " + version + " downloaded to " + newFile);
      this.stage(newFile, version);
      this.promptRestart();
   }

   private void promptRestart() {
      int choice = this.showOptionDialog(
         "Diode " + this.stagedVersion + " is ready to install.\n\nRestart the launcher now to finish updating? If you choose Later, the update installs when you close the launcher.",
         "Launcher update ready",
         new String[]{"Restart now", "Later"}
      );
      if (choice == 0) {
         if (this.startUpdater(true)) {
            this.minecraftLauncher.getLauncher().shutdownLauncher();
         } else {
            this.showOptionDialog("Couldn't start the updater. See the Launcher Log tab for details.", "Launcher update failed", new String[]{"OK"});
         }
      }
   }

   private synchronized void stage(File jar, String version) {
      this.stagedJar = jar;
      this.stagedVersion = version;
      this.setStatus("Diode " + version + " will be installed when the launcher closes.");
      if (!this.shutdownHookRegistered) {
         this.shutdownHookRegistered = true;
         Runtime.getRuntime().addShutdownHook(new Thread(() -> this.startUpdater(false), "Launcher update installer"));
      }
   }

   /** A previous session may have downloaded an update but failed to swap it in. */
   private void recoverStagedUpdate() {
      if (this.runningJar == null) {
         return;
      }

      File directory = this.runningJar.getAbsoluteFile().getParentFile();
      new File(directory, this.runningJar.getName() + ".part").delete();
      File newFile = new File(directory, this.runningJar.getName() + ".new");
      if (!newFile.isFile()) {
         return;
      }

      String version = null;
      try (JarFile jar = new JarFile(newFile)) {
         if (jar.getManifest() != null) {
            version = extractVersion(jar.getManifest().getMainAttributes().getValue("Implementation-Version"));
         }
      } catch (IOException e) {
         LOGGER.warn("Ignoring unreadable staged update " + newFile, e);
      }

      if (version != null && this.currentVersion != null && compareVersions(version, this.currentVersion) > 0) {
         LOGGER.info("Found previously downloaded Diode " + version + "; it will be installed when the launcher closes");
         this.stage(newFile, version);
      } else {
         newFile.delete();
      }
   }

   /**
    * Spawns {@link UpdateApplier} from a temporary copy of the current jar so
    * that nothing holds launcher.jar open while it is replaced.
    */
   private synchronized boolean startUpdater(boolean relaunch) {
      if (this.updaterStarted || this.stagedJar == null || !this.stagedJar.isFile()) {
         return this.updaterStarted;
      }

      String java = findJavaExecutable();
      if (java == null) {
         LOGGER.error("Can't install the launcher update: no java executable found next to " + System.getProperty("java.home"));
         return false;
      }

      try {
         File updaterJar = File.createTempFile(UPDATER_JAR_PREFIX, ".jar");
         Files.copy(this.runningJar.toPath(), updaterJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
         List<String> command = new ArrayList<>();
         command.add(java);
         command.add("-cp");
         command.add(updaterJar.getAbsolutePath());
         command.add(UpdateApplier.class.getName());
         command.add(Long.toString(ProcessHandle.current().pid()));
         command.add(this.stagedJar.getAbsolutePath());
         command.add(this.runningJar.getAbsolutePath());
         command.add(relaunch ? UpdateApplier.RELAUNCH : UpdateApplier.NO_RELAUNCH);
         if (relaunch) {
            command.addAll(this.buildRelaunchCommand(java));
         }

         new ProcessBuilder(command)
            .directory(new File(System.getProperty("user.dir")))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
         this.updaterStarted = true;
         // May run inside the shutdown hook, where Log4j can already be gone.
         System.out.println("Started launcher updater for Diode " + this.stagedVersion + (relaunch ? " (will relaunch)" : ""));
         return true;
      } catch (IOException e) {
         LOGGER.error("Couldn't start launcher updater", e);
         return false;
      }
   }

   /** Rebuilds the command that started this launcher, so a restart looks the same. */
   private List<String> buildRelaunchCommand(String java) {
      List<String> command = new ArrayList<>();
      // Installed via jpackage: restart through the app's own .exe so its
      // icon, shortcuts and packaged JVM options all stay the same.
      String appLauncher = System.getProperty("jpackage.app-path");
      if (appLauncher != null && new File(appLauncher).isFile()) {
         command.add(appLauncher);
         this.addLauncherOptions(command, false);
         return command;
      }

      command.add(java);
      try {
         // Keep memory/system-property flags; drop debugger and agent flags,
         // which would clash with the old process or an IDE.
         for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (argument.startsWith("-X") || argument.startsWith("-D")) {
               command.add(argument);
            }
         }
      } catch (RuntimeException | LinkageError ignored) {
      }

      // Bootstrap 5 loads launcher.jar itself; Main passes 100 when launcher.jar
      // was started directly.
      boolean viaBootstrap = this.minecraftLauncher.getBootstrapVersion() != null && this.minecraftLauncher.getBootstrapVersion() < 100;
      command.add("-cp");
      if (viaBootstrap) {
         command.add(System.getProperty("java.class.path"));
         command.add("net.minecraft.bootstrap.Bootstrap");
      } else {
         command.add(this.runningJar.getAbsolutePath());
         command.add("net.minecraft.launcher.Main");
      }

      this.addLauncherOptions(command, viaBootstrap);
      return command;
   }

   private void addLauncherOptions(List<String> command, boolean viaBootstrap) {
      command.add("--workDir");
      command.add(this.workingDirectory.getAbsolutePath());
      SocketAddress address = this.proxy.address();
      if (address instanceof InetSocketAddress) {
         command.add("--proxyHost");
         command.add(((InetSocketAddress)address).getHostString());
         command.add("--proxyPort");
         command.add(Integer.toString(((InetSocketAddress)address).getPort()));
      }

      if (!viaBootstrap && this.minecraftLauncher.usesWinTenHack()) {
         command.add("--winTen");
      }

      command.addAll(Arrays.asList(this.launcherArgs));
   }

   private String getUnsupportedReason() {
      if (this.runningJar == null) {
         return "Updates only work when the launcher runs from launcher.jar.";
      } else if (this.currentVersion == null) {
         return "Can't tell which launcher version this is, so updates are off.";
      } else if (!this.runningJar.getAbsoluteFile().getParentFile().canWrite()) {
         return "Can't update: " + this.runningJar.getAbsoluteFile().getParent() + " isn't writable.";
      } else {
         return null;
      }
   }

   private HttpURLConnection openConnection(URL url) throws IOException {
      HttpURLConnection connection = (HttpURLConnection)url.openConnection(this.proxy);
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(30000);
      connection.setUseCaches(false);
      connection.setRequestProperty("User-Agent", "DiodeLauncher/" + this.currentVersion);
      return connection;
   }

   /** The version line, then the release notes formatted in a scrollable panel. */
   private Object buildUpdateMessage(SelfUpdater.Release release, String latestVersion) {
      JLabel heading = new JLabel("Diode " + latestVersion + " is available. You have " + this.currentVersion + ".");
      heading.setFont(heading.getFont().deriveFont(Font.BOLD));
      String notesHtml = release.getNotesHtml();
      if (notesHtml == null) {
         return heading;
      }

      Font font = UIManager.getFont("Label.font");
      String family = font == null ? "sans-serif" : font.getFamily();
      HTMLEditorKit kit = new HTMLEditorKit();
      HTMLDocument document = (HTMLDocument)kit.createDefaultDocument();
      // Rules go on this document only; the kit's own style sheet is shared app-wide.
      StyleSheet styles = document.getStyleSheet();
      styles.addRule("body { font-family: '" + family + "'; font-size: 11pt; margin: 4px 8px; }");
      styles.addRule("h1, h2, h3 { font-size: 12pt; margin-top: 10px; margin-bottom: 2px; }");
      styles.addRule("p { margin-top: 4px; margin-bottom: 4px; }");
      styles.addRule("ul { margin-top: 2px; margin-bottom: 4px; margin-left: 18px; }");
      // Release file names are written as code; a monospace font makes them too wide for the table.
      styles.addRule("code { font-family: '" + family + "'; font-size: 10pt; }");
      styles.addRule("th { text-align: left; }");
      try {
         // Read into the fresh document; JEditorPane.setText would merge the
         // first paragraph into the document's empty one and lose it.
         kit.read(new StringReader("<html><body>" + notesHtml + "</body></html>"), document, 0);
      } catch (IOException | BadLocationException e) {
         LOGGER.warn("Couldn't show the release notes", e);
         return heading;
      }

      JEditorPane notes = new JEditorPane();
      notes.setEditable(false);
      notes.setEditorKit(kit);
      notes.setDocument(document);
      notes.setCaretPosition(0);
      notes.addHyperlinkListener(event -> {
         if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getURL() != null) {
            try {
               OperatingSystem.openLink(event.getURL().toURI());
            } catch (Exception e) {
               LOGGER.warn("Couldn't open link " + event.getURL(), e);
            }
         }
      });
      JScrollPane scroll = new JScrollPane(notes);
      scroll.setPreferredSize(new Dimension(620, 300));

      JPanel panel = new JPanel(new BorderLayout(0, 8));
      panel.add(heading, BorderLayout.NORTH);
      panel.add(scroll, BorderLayout.CENTER);
      if (release.htmlUrl != null) {
         JLabel link = new JLabel("<html><a href=''>View this release on GitHub</a></html>");
         link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
         link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
               OperatingSystem.openLink(URI.create(release.htmlUrl));
            }
         });
         panel.add(link, BorderLayout.SOUTH);
      }

      return panel;
   }

   private int showOptionDialog(final Object message, final String title, final String[] options) {
      final int[] result = new int[]{-1};
      Runnable dialog = () -> {
         JFrame frame = ((SwingUserInterface)this.minecraftLauncher.getUserInterface()).getFrame();
         result[0] = JOptionPane.showOptionDialog(
            frame, message, title, JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]
         );
      };

      if (SwingUtilities.isEventDispatchThread()) {
         dialog.run();
      } else {
         try {
            SwingUtilities.invokeAndWait(dialog);
         } catch (Exception e) {
            LOGGER.warn("Couldn't show update dialog", e);
         }
      }

      return result[0];
   }

   private void setStatus(String status) {
      this.status = status;
      for (SelfUpdater.StatusListener listener : this.statusListeners) {
         listener.onStatusChanged(status);
      }
   }

   public void openReleasesPage() {
      OperatingSystem.openLink(URI.create(RELEASES_PAGE));
   }

   private static void verifyLauncherJar(File file) throws IOException {
      try (JarFile jar = new JarFile(file)) {
         if (jar.getEntry("net/minecraft/launcher/Main.class") == null) {
            throw new IOException("Downloaded file isn't a Diode launcher.jar");
         }
      }
   }

   private static File findRunningJar() {
      try {
         File source = new File(SelfUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
         return source.isFile() && source.getName().toLowerCase(Locale.ROOT).endsWith(".jar") ? source : null;
      } catch (Exception e) {
         return null;
      }
   }

   /** Prefers the exact java/javaw binary running us, so console behaviour matches. */
   private static String findJavaExecutable() {
      String command = ProcessHandle.current().info().command().orElse(null);
      if (command != null) {
         String name = new File(command).getName().toLowerCase(Locale.ROOT);
         if (name.equals("java") || name.equals("java.exe") || name.equals("javaw.exe")) {
            return command;
         }
      }

      // A native wrapper (e.g. a packaged .exe) is running us; fall back to
      // the runtime's own binary if it shipped one.
      File bin = new File(System.getProperty("java.home"), "bin");
      for (String candidate : new String[]{"javaw.exe", "java.exe", "java"}) {
         File file = new File(bin, candidate);
         if (file.isFile()) {
            return file.getAbsolutePath();
         }
      }

      return null;
   }

   private static void cleanUpOldUpdaterJars() {
      File[] files = new File(System.getProperty("java.io.tmpdir")).listFiles();
      if (files != null) {
         long cutoff = System.currentTimeMillis() - 60000L;
         for (File file : files) {
            if (file.getName().startsWith(UPDATER_JAR_PREFIX) && file.getName().endsWith(".jar") && file.lastModified() < cutoff) {
               file.delete();
            }
         }
      }
   }

   static String extractVersion(String raw) {
      if (raw == null) {
         return null;
      }

      Matcher matcher = VERSION_PATTERN.matcher(raw);
      return matcher.find() ? matcher.group(1) : null;
   }

   static int compareVersions(String a, String b) {
      String[] left = a.split("\\.");
      String[] right = b.split("\\.");
      for (int i = 0; i < Math.max(left.length, right.length); i++) {
         long l = i < left.length ? Long.parseLong(left[i]) : 0L;
         long r = i < right.length ? Long.parseLong(right[i]) : 0L;
         if (l != r) {
            return Long.compare(l, r);
         }
      }

      return 0;
   }

   public interface StatusListener {
      void onStatusChanged(String status);
   }

   private static class Release {
      @SerializedName("tag_name")
      private String tagName;
      private String body;
      @SerializedName("body_html")
      private String bodyHtml;
      @SerializedName("html_url")
      private String htmlUrl;
      private List<SelfUpdater.Asset> assets;

      /** GitHub's rendered notes, or the plain text ones if only those were sent; null if empty. */
      private String getNotesHtml() {
         if (this.bodyHtml != null && !this.bodyHtml.trim().isEmpty()) {
            // Swing's HTML renderer is HTML 3.2: it doesn't know GitHub's table
            // wrapper element and ignores CSS table borders, so adjust for it.
            return this.bodyHtml
               .replaceAll("</?markdown-accessiblity-table[^>]*>", "")
               .replaceAll("<table[^>]*>", "<table border=\"1\" cellspacing=\"0\" cellpadding=\"3\">");
         }

         if (this.body == null || this.body.trim().isEmpty()) {
            return null;
         }

         String escaped = this.body.trim().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
         return escaped.replace("\r\n", "\n").replace("\n", "<br>");
      }

      private SelfUpdater.Asset findLauncherJar() {
         if (this.assets == null) {
            return null;
         }

         for (SelfUpdater.Asset asset : this.assets) {
            if ("launcher.jar".equalsIgnoreCase(asset.name)) {
               return asset;
            }
         }

         return null;
      }
   }

   private static class Asset {
      private String name;
      @SerializedName("browser_download_url")
      private String downloadUrl;
      private long size;
      private String digest;
   }
}
