package diode.legacyfix;

import java.io.ByteArrayOutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;

/**
 * Java agent Diode adds when it starts an old launchwrapper-based version (Forge for 1.6 and 1.7.2).
 *
 * <p>Those Forge builds sort launchwrapper's tweak list with {@code Collections.sort} while
 * launchwrapper is still looping over that list. Since Java 8u20, sorting an ArrayList counts as
 * changing it, so the loop throws a ConcurrentModificationException and the game never starts.
 * Mojang only provides Java 8u51, so instead of an older Java this agent points Forge's
 * {@code Collections.sort(List, Comparator)} calls at {@link LegacySort}, which sorts the way
 * Java 7 did. Only classes in Forge's packages are touched, and only that one method reference.</p>
 *
 * <p>Compiled for Java 8 and shipped inside the launcher as diode-legacy-fix.jar; see README.md.</p>
 */
public final class LegacyFixAgent implements ClassFileTransformer {
   private static final String COLLECTIONS = "java/util/Collections";
   private static final String SORT = "sort";
   private static final String SORT_DESCRIPTOR = "(Ljava/util/List;Ljava/util/Comparator;)V";
   private static final String REPLACEMENT = "diode/legacyfix/LegacySort";

   public static void premain(String args, Instrumentation instrumentation) {
      instrumentation.addTransformer(new LegacyFixAgent());
   }

   @Override
   public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
      if (className == null || !(className.startsWith("cpw/mods/fml/") || className.startsWith("net/minecraftforge/"))) {
         return null;
      }

      try {
         byte[] patched = redirectSort(bytes);
         if (patched != null) {
            System.out.println("[Diode] Applied the Java 8 sorting fix to " + className.replace('/', '.'));
         }
         return patched;
      } catch (RuntimeException e) {
         // Never stop the game over this; leave the class as it was.
         return null;
      }
   }

   /** Returns the class with Collections.sort(List, Comparator) redirected, or null if it doesn't call it. */
   static byte[] redirectSort(byte[] bytes) {
      int count = u2(bytes, 8);
      int[] offsets = new int[count];
      int[] tags = new int[count];
      int position = 10;
      for (int index = 1; index < count; index++) {
         int tag = bytes[position] & 0xFF;
         offsets[index] = position;
         tags[index] = tag;
         switch (tag) {
            case 1: // Utf8
               position += 3 + u2(bytes, position + 1);
               break;
            case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: // 4-byte entries
               position += 5;
               break;
            case 5: case 6: // Long, Double take two slots
               position += 9;
               index++;
               break;
            case 7: case 8: case 16: case 19: case 20: // 2-byte entries
               position += 3;
               break;
            case 15: // MethodHandle
               position += 4;
               break;
            default:
               return null; // unknown constant; don't risk it
         }
      }
      int constantPoolEnd = position;

      int target = 0;
      for (int index = 1; index < count; index++) {
         if (tags[index] != 10) {
            continue;
         }
         int classIndex = u2(bytes, offsets[index] + 1);
         int nameAndType = u2(bytes, offsets[index] + 3);
         if (tags[classIndex] == 7 && tags[nameAndType] == 12
            && COLLECTIONS.equals(utf8(bytes, offsets, u2(bytes, offsets[classIndex] + 1)))
            && SORT.equals(utf8(bytes, offsets, u2(bytes, offsets[nameAndType] + 1)))
            && SORT_DESCRIPTOR.equals(utf8(bytes, offsets, u2(bytes, offsets[nameAndType] + 3)))) {
            target = index;
            break;
         }
      }
      if (target == 0) {
         return null;
      }

      // Append Utf8 "diode/legacyfix/LegacySort" and a Class entry for it, then point the method at it.
      byte[] name = REPLACEMENT.getBytes(StandardCharsets.UTF_8);
      ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + name.length + 8);
      out.write(bytes, 0, 8);
      out.write((count + 2) >> 8);
      out.write((count + 2) & 0xFF);
      byte[] pool = new byte[constantPoolEnd - 10];
      System.arraycopy(bytes, 10, pool, 0, pool.length);
      int newClass = count + 1;
      int methodRef = offsets[target] - 10;
      pool[methodRef + 1] = (byte)(newClass >> 8);
      pool[methodRef + 2] = (byte)newClass;
      out.write(pool, 0, pool.length);
      out.write(1);
      out.write(name.length >> 8);
      out.write(name.length & 0xFF);
      out.write(name, 0, name.length);
      out.write(7);
      out.write(count >> 8);
      out.write(count & 0xFF);
      out.write(bytes, constantPoolEnd, bytes.length - constantPoolEnd);
      return out.toByteArray();
   }

   private static int u2(byte[] bytes, int position) {
      return (bytes[position] & 0xFF) << 8 | bytes[position + 1] & 0xFF;
   }

   private static String utf8(byte[] bytes, int[] offsets, int index) {
      if (index <= 0 || index >= offsets.length || (bytes[offsets[index]] & 0xFF) != 1) {
         return null;
      }
      int length = u2(bytes, offsets[index] + 1);
      return new String(bytes, offsets[index] + 3, length, StandardCharsets.UTF_8);
   }
}
