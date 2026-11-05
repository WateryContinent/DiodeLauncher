package diode.legacyfix;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.ListIterator;

/** Collections.sort as it worked before Java 8u20: it doesn't count as changing the list. */
public final class LegacySort {
   private LegacySort() {
   }

   @SuppressWarnings({"unchecked", "rawtypes"})
   public static <T> void sort(List<T> list, Comparator<? super T> comparator) {
      Object[] sorted = list.toArray();
      Arrays.sort(sorted, (Comparator)comparator);
      ListIterator<T> iterator = list.listIterator();
      for (Object element : sorted) {
         iterator.next();
         iterator.set((T)element);
      }
   }
}
