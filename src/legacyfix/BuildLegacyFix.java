import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

/**
 * Compiles the legacy fix agent for Java 8 and writes it into the launcher's resources.
 * Run from the repository root: java src/legacyfix/BuildLegacyFix.java
 */
public class BuildLegacyFix {
   public static void main(String[] args) throws IOException {
      Path sources = Path.of("src/legacyfix/src");
      Path classes = Path.of("build/legacyfix");
      Path jar = Path.of("src/launcher/src/main/resources/diode-legacy-fix.jar");
      List<String> javacArgs = new ArrayList<>(List.of("--release", "8", "-nowarn", "-Xlint:-options", "-d", classes.toString()));
      try (Stream<Path> files = Files.walk(sources)) {
         files.filter(p -> p.toString().endsWith(".java")).forEach(p -> javacArgs.add(p.toString()));
      }
      if (ToolProvider.getSystemJavaCompiler().run(null, null, null, javacArgs.toArray(new String[0])) != 0) {
         throw new IllegalStateException("javac failed");
      }

      // Old Forge reads every class on the classpath with ASM 4, which only understands class files
      // up to Java 7 (version 51), so mark ours as Java 7. The code uses nothing newer: no lambdas,
      // default methods or invokedynamic. javac can't target 7 itself any more.
      try (Stream<Path> files = Files.walk(classes)) {
         for (Path file : (Iterable<Path>)files.filter(p -> p.toString().endsWith(".class"))::iterator) {
            byte[] bytes = Files.readAllBytes(file);
            bytes[6] = 0;
            bytes[7] = 51;
            Files.write(file, bytes);
         }
      }

      Manifest manifest = new Manifest();
      manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
      manifest.getMainAttributes().putValue("Premain-Class", "diode.legacyfix.LegacyFixAgent");
      try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest);
           Stream<Path> files = Files.walk(classes)) {
         for (Path file : (Iterable<Path>)files.filter(Files::isRegularFile).sorted()::iterator) {
            JarEntry entry = new JarEntry(classes.relativize(file).toString().replace('\\', '/'));
            entry.setTime(0L); // same bytes every build
            out.putNextEntry(entry);
            Files.copy(file, (OutputStream)out);
            out.closeEntry();
         }
      }
      System.out.println("Wrote " + jar + " (" + Files.size(jar) + " bytes)");
   }
}
