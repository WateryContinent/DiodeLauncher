package net.minecraft.launcher.selfupdate;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Standalone entry point started by {@link SelfUpdater} from a temporary copy
 * of launcher.jar:
 *
 * <pre>UpdateApplier &lt;pid&gt; &lt;launcher.jar.new&gt; &lt;launcher.jar&gt; --relaunch|--no-relaunch [command...]</pre>
 *
 * <p>Uses only the JDK (no Log4j/Gson) because it runs outside the launcher's
 * normal startup. Progress is written to diode-update.log beside the jar.</p>
 */
public final class UpdateApplier {
   static final String RELAUNCH = "--relaunch";
   static final String NO_RELAUNCH = "--no-relaunch";
   private static File logFile;

   private UpdateApplier() {
   }

   public static void main(String[] args) {
      if (args.length < 4) {
         System.err.println("Usage: UpdateApplier <pid> <new jar> <target jar> --relaunch|--no-relaunch [command...]");
         return;
      }

      long pid = Long.parseLong(args[0]);
      File newJar = new File(args[1]);
      File targetJar = new File(args[2]);
      boolean relaunch = RELAUNCH.equals(args[3]);
      List<String> command = Arrays.asList(args).subList(4, args.length);
      logFile = new File(targetJar.getAbsoluteFile().getParentFile(), "diode-update.log");
      log("Waiting for launcher process " + pid + " to exit");
      waitForExit(pid);

      if (replace(newJar, targetJar)) {
         log("Installed update into " + targetJar);
      } else {
         log("Couldn't replace " + targetJar + "; the update will be retried next time the launcher closes");
      }

      if (relaunch && !command.isEmpty()) {
         try {
            new ProcessBuilder(command)
               .redirectOutput(ProcessBuilder.Redirect.DISCARD)
               .redirectError(ProcessBuilder.Redirect.DISCARD)
               .start();
            log("Relaunched: " + String.join(" ", command));
         } catch (IOException e) {
            log("Couldn't relaunch the launcher: " + e);
         }
      }
   }

   private static void waitForExit(long pid) {
      Optional<ProcessHandle> handle = ProcessHandle.of(pid);
      if (handle.isPresent()) {
         try {
            handle.get().onExit().get(2L, TimeUnit.MINUTES);
         } catch (Exception e) {
            log("Gave up waiting for process " + pid + ": " + e);
         }
      }
   }

   /** Retries for a while because Windows can hold the jar briefly after exit. */
   private static boolean replace(File newJar, File targetJar) {
      File backup = new File(targetJar.getAbsoluteFile().getParentFile(), targetJar.getName() + ".old");
      for (int attempt = 1; attempt <= 20; attempt++) {
         try {
            if (targetJar.isFile()) {
               Files.copy(targetJar.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            Files.move(newJar.toPath(), targetJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
         } catch (IOException e) {
            log("Attempt " + attempt + " failed: " + e);
            try {
               Thread.sleep(500L);
            } catch (InterruptedException interrupted) {
               Thread.currentThread().interrupt();
               return false;
            }
         }
      }

      return false;
   }

   private static void log(String message) {
      String line = "[" + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, Locale.US).format(new Date()) + "] " + message;
      System.out.println(line);
      try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, true))) {
         writer.println(line);
      } catch (IOException ignored) {
      }
   }
}
