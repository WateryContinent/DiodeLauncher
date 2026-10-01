package net.minecraft.launcher.ui.tabs;

import com.mojang.launcher.events.GameOutputLogProcessor;
import com.mojang.launcher.game.process.GameProcess;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import net.minecraft.launcher.Launcher;

public class GameOutputTab extends JScrollPane implements GameOutputLogProcessor {
   private static final Font MONOSPACED = new Font("Monospaced", 0, 12);
   private static final int MAX_LINE_COUNT = 1000;
   private final JTextArea console = new JTextArea();
   private final JPopupMenu popupMenu = new JPopupMenu();
   private final JMenuItem copyTextButton = new JMenuItem("Copy All Text");
   private final Launcher minecraftLauncher;

   public GameOutputTab(Launcher minecraftLauncher) {
      this.minecraftLauncher = minecraftLauncher;
      this.popupMenu.add(this.copyTextButton);
      this.console.setComponentPopupMenu(this.popupMenu);
      this.copyTextButton.addActionListener(new ActionListener() {
         @Override
         public void actionPerformed(ActionEvent e) {
            try {
               StringSelection ss = new StringSelection(GameOutputTab.this.console.getText());
               Toolkit.getDefaultToolkit().getSystemClipboard().setContents(ss, null);
            } catch (Exception var3) {
            }
         }
      });
      this.console.setFont(MONOSPACED);
      this.console.setEditable(false);
      this.console.setMargin(null);
      this.setViewportView(this.console);
      this.console.getDocument().addDocumentListener(new DocumentListener() {
         @Override
         public void insertUpdate(DocumentEvent e) {
            SwingUtilities.invokeLater(new Runnable() {
               @Override
               public void run() {
                  Document document = GameOutputTab.this.console.getDocument();
                  Element root = document.getDefaultRootElement();

                  while (root.getElementCount() > 1001) {
                     try {
                        document.remove(0, root.getElement(0).getEndOffset());
                     } catch (BadLocationException var4) {
                     }
                  }
               }
            });
         }

         @Override
         public void removeUpdate(DocumentEvent e) {
         }

         @Override
         public void changedUpdate(DocumentEvent e) {
         }
      });
   }

   public Launcher getMinecraftLauncher() {
      return this.minecraftLauncher;
   }

   public void print(final String line) {
      if (!SwingUtilities.isEventDispatchThread()) {
         SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
               GameOutputTab.this.print(line);
            }
         });
      } else {
         Document document = this.console.getDocument();
         JScrollBar scrollBar = this.getVerticalScrollBar();
         boolean shouldScroll = false;
         if (this.getViewport().getView() == this.console) {
            shouldScroll = scrollBar.getValue() + scrollBar.getSize().getHeight() + MONOSPACED.getSize() * 4 > scrollBar.getMaximum();
         }

         try {
            document.insertString(document.getLength(), line, null);
         } catch (BadLocationException var6) {
         }

         if (shouldScroll) {
            scrollBar.setValue(Integer.MAX_VALUE);
         }
      }
   }

   @Override
   public void onGameOutput(GameProcess process, String logLine) {
      this.print(logLine + "\n");
   }
}
