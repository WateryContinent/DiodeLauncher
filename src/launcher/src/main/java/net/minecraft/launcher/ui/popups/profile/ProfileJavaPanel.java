package net.minecraft.launcher.ui.popups.profile;

import com.mojang.launcher.OperatingSystem;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public class ProfileJavaPanel extends JPanel {
   private static final int DEFAULT_MEMORY_MB = 2048;
   private static final Pattern XMX_PATTERN = Pattern.compile("(?:^|\\s)-Xmx(\\d+)([kKmMgG]?)(?=\\s|$)");

   private final ProfileEditorPopup editor;
   private final JCheckBox memoryCustom = new JCheckBox("Memory (MB):");
   private final JSpinner memorySpinner = new JSpinner(new SpinnerNumberModel(DEFAULT_MEMORY_MB, 512, 65536, 512));
   private final JCheckBox javaPathCustom = new JCheckBox("Executable:");
   private final JTextField javaPathField = new JTextField();
   private final JCheckBox javaArgsCustom = new JCheckBox("JVM Arguments (Advanced):");
   private final JTextField javaArgsField = new JTextField();

   public ProfileJavaPanel(ProfileEditorPopup editor) {
      this.editor = editor;
      this.setLayout(new GridBagLayout());
      this.setBorder(BorderFactory.createTitledBorder("Java & Memory"));
      this.memorySpinner.setToolTipText("Custom maximum RAM for this profile. Leave unchecked to use the launcher default (2048 MB).");
      this.javaArgsField.setToolTipText("Additional JVM arguments only. Memory is controlled separately above.");

      this.createInterface();
      this.fillDefaultValues();
      this.addEventHandlers();
   }

   protected void createInterface() {
      GridBagConstraints constraints = new GridBagConstraints();
      constraints.insets = new Insets(2, 2, 2, 2);
      constraints.anchor = GridBagConstraints.WEST;
      constraints.gridy = 0;
      constraints.gridx = 0;
      this.add(this.memoryCustom, constraints);

      constraints.gridx = 1;
      constraints.fill = GridBagConstraints.HORIZONTAL;
      constraints.weightx = 1.0;
      this.add(this.memorySpinner, constraints);

      constraints.gridy++;
      constraints.gridx = 0;
      constraints.weightx = 0.0;
      constraints.fill = GridBagConstraints.NONE;
      this.add(this.javaPathCustom, constraints);

      constraints.gridx = 1;
      constraints.fill = GridBagConstraints.HORIZONTAL;
      constraints.weightx = 1.0;
      this.add(this.javaPathField, constraints);

      constraints.gridy++;
      constraints.gridx = 0;
      constraints.weightx = 0.0;
      constraints.fill = GridBagConstraints.NONE;
      this.add(this.javaArgsCustom, constraints);

      constraints.gridx = 1;
      constraints.fill = GridBagConstraints.HORIZONTAL;
      constraints.weightx = 1.0;
      this.add(this.javaArgsField, constraints);
   }

   protected void fillDefaultValues() {
      String javaPath = this.editor.getProfile().getJavaPath();
      if (javaPath != null) {
         this.javaPathCustom.setSelected(true);
         this.javaPathField.setText(javaPath);
      } else {
         this.javaPathCustom.setSelected(false);
         this.javaPathField.setText(OperatingSystem.getCurrentPlatform().getJavaDir());
      }

      this.updateJavaPathState();

      String args = this.editor.getProfile().getJavaArgs();
      Integer memoryMb = this.editor.getProfile().getMemoryMb();
      if (memoryMb == null) {
         memoryMb = this.extractMemoryMb(args);
      }

      if (memoryMb != null) {
         this.memoryCustom.setSelected(true);
         this.memorySpinner.setValue(memoryMb);
         this.editor.getProfile().setMemoryMb(memoryMb);
      } else {
         this.memoryCustom.setSelected(false);
         this.memorySpinner.setValue(DEFAULT_MEMORY_MB);
         this.editor.getProfile().setMemoryMb(null);
      }
      this.updateMemoryState();

      String cleanedArgs = this.stripManagedArguments(args);
      if (cleanedArgs.length() > 0) {
         this.javaArgsCustom.setSelected(true);
         this.javaArgsField.setText(cleanedArgs);
         this.editor.getProfile().setJavaArgs(cleanedArgs);
      } else {
         this.javaArgsCustom.setSelected(false);
         this.javaArgsField.setText("");
         this.editor.getProfile().setJavaArgs(null);
      }

      this.updateJavaArgsState();
   }

   protected void addEventHandlers() {
      this.memoryCustom.addItemListener(new ItemListener() {
         @Override
         public void itemStateChanged(ItemEvent e) {
            ProfileJavaPanel.this.updateMemoryState();
         }
      });
      this.memorySpinner.addChangeListener(new ChangeListener() {
         @Override
         public void stateChanged(ChangeEvent e) {
            if (ProfileJavaPanel.this.memoryCustom.isSelected()) {
               ProfileJavaPanel.this.editor.getProfile().setMemoryMb((Integer)ProfileJavaPanel.this.memorySpinner.getValue());
            }
         }
      });

      this.javaPathCustom.addItemListener(new ItemListener() {
         @Override
         public void itemStateChanged(ItemEvent e) {
            ProfileJavaPanel.this.updateJavaPathState();
         }
      });
      this.javaPathField.getDocument().addDocumentListener(new DocumentListener() {
         @Override
         public void insertUpdate(DocumentEvent e) {
            ProfileJavaPanel.this.updateJavaPath();
         }

         @Override
         public void removeUpdate(DocumentEvent e) {
            ProfileJavaPanel.this.updateJavaPath();
         }

         @Override
         public void changedUpdate(DocumentEvent e) {
            ProfileJavaPanel.this.updateJavaPath();
         }
      });
      this.javaArgsCustom.addItemListener(new ItemListener() {
         @Override
         public void itemStateChanged(ItemEvent e) {
            ProfileJavaPanel.this.updateJavaArgsState();
         }
      });
      this.javaArgsField.getDocument().addDocumentListener(new DocumentListener() {
         @Override
         public void insertUpdate(DocumentEvent e) {
            ProfileJavaPanel.this.updateJavaArgs();
         }

         @Override
         public void removeUpdate(DocumentEvent e) {
            ProfileJavaPanel.this.updateJavaArgs();
         }

         @Override
         public void changedUpdate(DocumentEvent e) {
            ProfileJavaPanel.this.updateJavaArgs();
         }
      });
   }

   private Integer extractMemoryMb(String args) {
      if (args == null) {
         return null;
      }

      Matcher matcher = XMX_PATTERN.matcher(args);
      if (!matcher.find()) {
         return null;
      }

      try {
         long value = Long.parseLong(matcher.group(1));
         String unit = matcher.group(2);
         if ("g".equalsIgnoreCase(unit)) {
            value *= 1024L;
         } else if ("k".equalsIgnoreCase(unit)) {
            value = Math.max(1L, value / 1024L);
         }

         value = Math.max(512L, Math.min(65536L, value));
         return (int)value;
      } catch (NumberFormatException ignored) {
         return null;
      }
   }

   private String stripManagedArguments(String args) {
      if (args == null || args.trim().isEmpty()) {
         return "";
      }

      StringBuilder result = new StringBuilder();
      String[] split = args.trim().split("\\s+");
      for (String arg : split) {
         if (arg.regionMatches(true, 0, "-Xmx", 0, 4)) {
            continue;
         }
         if (arg.indexOf("UseConcMarkSweepGC") >= 0 || arg.indexOf("CMS") >= 0) {
            continue;
         }

         if (result.length() > 0) {
            result.append(' ');
         }
         result.append(arg);
      }
      return result.toString();
   }

   private void updateMemoryState() {
      boolean custom = this.memoryCustom.isSelected();
      this.memorySpinner.setEnabled(custom);
      if (custom) {
         this.editor.getProfile().setMemoryMb((Integer)this.memorySpinner.getValue());
      } else {
         this.editor.getProfile().setMemoryMb(null);
      }
   }

   private void updateJavaPath() {
      if (this.javaPathCustom.isSelected()) {
         this.editor.getProfile().setJavaDir(this.javaPathField.getText());
      } else {
         this.editor.getProfile().setJavaDir(null);
      }
   }

   private void updateJavaPathState() {
      if (this.javaPathCustom.isSelected()) {
         this.javaPathField.setEnabled(true);
         this.editor.getProfile().setJavaDir(this.javaPathField.getText());
      } else {
         this.javaPathField.setEnabled(false);
         this.editor.getProfile().setJavaDir(null);
      }
   }

   private void updateJavaArgs() {
      if (this.javaArgsCustom.isSelected()) {
         this.editor.getProfile().setJavaArgs(this.stripManagedArguments(this.javaArgsField.getText()));
      } else {
         this.editor.getProfile().setJavaArgs(null);
      }
   }

   private void updateJavaArgsState() {
      if (this.javaArgsCustom.isSelected()) {
         this.javaArgsField.setEnabled(true);
         this.editor.getProfile().setJavaArgs(this.stripManagedArguments(this.javaArgsField.getText()));
      } else {
         this.javaArgsField.setEnabled(false);
         this.editor.getProfile().setJavaArgs(null);
      }
   }
}
