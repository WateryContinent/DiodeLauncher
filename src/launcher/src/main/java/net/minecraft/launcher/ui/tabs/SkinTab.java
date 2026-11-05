package net.minecraft.launcher.ui.tabs;

import com.mojang.authlib.UserAuthentication;
import com.mojang.authlib.exceptions.AuthenticationException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import net.minecraft.launcher.Launcher;
import net.minecraft.launcher.auth.MicrosoftUserAuthentication;
import net.minecraft.launcher.profile.ProfileManager;
import net.minecraft.launcher.profile.UserChangedListener;
import net.minecraft.launcher.skins.MinecraftProfileService;
import net.minecraft.launcher.skins.MinecraftProfileService.Cape;
import net.minecraft.launcher.skins.MinecraftProfileService.Profile;
import net.minecraft.launcher.skins.MinecraftProfileService.ProfileException;
import net.minecraft.launcher.skins.MinecraftProfileService.SkinModel;
import net.minecraft.launcher.skins.SkinRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Shows the logged-in player's skin and cape in 3D, and lets them change both. */
public class SkinTab extends JPanel implements UserChangedListener {
   private static final Logger LOGGER = LogManager.getLogger();
   private static final String NO_CAPE = "No cape";
   private final Launcher minecraftLauncher;
   private final MinecraftProfileService service;
   private final SkinPreviewPanel preview = new SkinPreviewPanel();
   private final JLabel accountLabel = new JLabel(" ");
   private final JRadioButton classicButton = new JRadioButton(SkinModel.CLASSIC.label());
   private final JRadioButton slimButton = new JRadioButton(SkinModel.SLIM.label());
   private final JButton changeSkinButton = new JButton("Change skin...");
   private final JButton defaultSkinButton = new JButton("Use default skin");
   private final JButton saveSkinFileButton = new JButton("Save skin picture...");
   private final JComboBox<Object> capeBox = new JComboBox<>();
   private final JButton saveButton = new JButton("Save changes");
   private final JButton undoButton = new JButton("Undo changes");
   private final JButton reloadButton = new JButton("Reload");
   private final JLabel statusLabel = new JLabel(" ");
   private final Map<String, BufferedImage> capeImages = new ConcurrentHashMap<>();
   private Profile profile;
   private BufferedImage currentSkin;
   private File pendingFile;
   private BufferedImage pendingSkin;
   private boolean loaded;
   private boolean busy;
   private boolean updatingControls;

   public SkinTab(Launcher minecraftLauncher) {
      this.minecraftLauncher = minecraftLauncher;
      this.service = new MinecraftProfileService(minecraftLauncher.getLauncher().getProxy());
      this.createInterface();
      minecraftLauncher.getProfileManager().addUserChangedListener(this);
      // Only talk to Mojang once the tab is actually opened.
      this.addComponentListener(new ComponentAdapter() {
         @Override
         public void componentShown(ComponentEvent e) {
            if (!SkinTab.this.loaded) {
               SkinTab.this.reload();
            }
         }
      });
   }

   protected void createInterface() {
      this.setLayout(new BorderLayout(12, 0));
      this.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
      this.preview.setBorder(BorderFactory.createLoweredBevelBorder());
      this.add(this.preview, BorderLayout.WEST);

      JPanel controls = new JPanel(new GridBagLayout());
      GridBagConstraints constraints = new GridBagConstraints();
      constraints.gridx = 0;
      constraints.gridy = GridBagConstraints.RELATIVE;
      constraints.anchor = GridBagConstraints.WEST;
      constraints.fill = GridBagConstraints.HORIZONTAL;
      constraints.weightx = 1.0;
      constraints.insets = new Insets(2, 0, 2, 0);

      JPanel accountRow = new JPanel(new BorderLayout(6, 0));
      accountRow.add(this.accountLabel, BorderLayout.CENTER);
      accountRow.add(this.reloadButton, BorderLayout.EAST);
      controls.add(accountRow, constraints);

      controls.add(heading("Skin"), constraints);
      JPanel modelRow = row();
      modelRow.add(new JLabel("Arm style:"));
      ButtonGroup models = new ButtonGroup();
      models.add(this.classicButton);
      models.add(this.slimButton);
      modelRow.add(this.classicButton);
      modelRow.add(this.slimButton);
      controls.add(modelRow, constraints);
      JPanel skinButtons = row();
      skinButtons.add(this.changeSkinButton);
      skinButtons.add(this.defaultSkinButton);
      skinButtons.add(this.saveSkinFileButton);
      controls.add(skinButtons, constraints);

      controls.add(Box.createVerticalStrut(6), constraints);
      controls.add(heading("Cape"), constraints);
      JPanel capeRow = row();
      capeRow.add(this.capeBox);
      controls.add(capeRow, constraints);

      controls.add(Box.createVerticalStrut(10), constraints);
      JPanel saveRow = row();
      saveRow.add(this.saveButton);
      saveRow.add(this.undoButton);
      controls.add(saveRow, constraints);
      controls.add(this.statusLabel, constraints);

      constraints.weighty = 1.0;
      controls.add(Box.createGlue(), constraints);
      constraints.weighty = 0.0;
      JLabel note = new JLabel("<html>Changes show up in game the next time you join a world or server. "
         + "Skins must be 64x64 or 64x32 PNG pictures.</html>");
      note.setForeground(Color.GRAY);
      controls.add(note, constraints);
      this.add(controls, BorderLayout.CENTER);

      this.classicButton.addActionListener(e -> this.onPendingChange());
      this.slimButton.addActionListener(e -> this.onPendingChange());
      this.capeBox.addActionListener(e -> this.onCapeSelected());
      this.changeSkinButton.addActionListener(e -> this.chooseSkinFile());
      this.defaultSkinButton.addActionListener(e -> this.useDefaultSkin());
      this.saveSkinFileButton.addActionListener(e -> this.saveSkinPicture());
      this.saveButton.addActionListener(e -> this.saveChanges());
      this.undoButton.addActionListener(e -> this.undoChanges());
      this.reloadButton.addActionListener(e -> this.reload());
      this.updateControls();
   }

   @Override
   public void onUserChanged(ProfileManager manager) {
      SwingUtilities.invokeLater(() -> {
         if (this.loaded) {
            this.reload();
         }
      });
   }

   /** Fetches the selected account's skin and cape from Mojang. */
   public void reload() {
      this.loaded = true;
      this.pendingFile = null;
      this.pendingSkin = null;
      final MicrosoftUserAuthentication auth = this.getMicrosoftUser();
      if (auth == null) {
         this.profile = null;
         this.currentSkin = null;
         this.accountLabel.setText(" ");
         this.preview.setMessage("Not logged in");
         this.setStatus("Log in with a Microsoft account that owns Minecraft to change your skin.");
         this.updateControls();
         return;
      }

      this.accountLabel.setText("Logged in as " + auth.getSelectedProfile().getName());
      this.preview.setMessage("Loading...");
      this.setStatus("Loading your skin...");
      this.runInBackground(() -> {
         Profile loadedProfile = this.withToken(auth, this.service::getProfile);
         this.showProfile(loadedProfile, "");
      });
   }

   private void showProfile(Profile newProfile, String status) throws ProfileException {
      BufferedImage skin = this.service.downloadTexture(newProfile.getSkinUrl());
      Cape active = newProfile.getActiveCape();
      if (active != null && active.getUrl() != null) {
         this.capeImages.put(active.getId(), this.service.downloadTexture(active.getUrl()));
      }

      SwingUtilities.invokeLater(() -> {
         this.profile = newProfile;
         this.currentSkin = skin;
         this.pendingFile = null;
         this.pendingSkin = null;
         this.accountLabel.setText("Logged in as " + newProfile.getName());
         this.updatingControls = true;
         try {
            (newProfile.getSkinModel() == SkinModel.SLIM ? this.slimButton : this.classicButton).setSelected(true);
            this.capeBox.removeAllItems();
            this.capeBox.addItem(NO_CAPE);
            for (Cape cape : newProfile.getCapes()) {
               this.capeBox.addItem(cape);
            }
            this.capeBox.setSelectedItem(active == null ? NO_CAPE : active);
         } finally {
            this.updatingControls = false;
         }

         this.setStatus(status);
         this.updatePreview();
         this.updateControls();
      });
   }

   private void chooseSkinFile() {
      JFileChooser chooser = new JFileChooser();
      chooser.setDialogTitle("Choose a skin");
      chooser.setFileFilter(new FileNameExtensionFilter("Skin pictures (*.png)", "png"));
      if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
         return;
      }

      File file = chooser.getSelectedFile();
      BufferedImage image;
      try {
         image = ImageIO.read(file);
      } catch (IOException e) {
         image = null;
      }

      if (image == null) {
         JOptionPane.showMessageDialog(this, file.getName() + " isn't a picture Diode can read.", "Change skin", JOptionPane.ERROR_MESSAGE);
         return;
      }
      if (image.getWidth() != 64 || (image.getHeight() != 64 && image.getHeight() != 32)) {
         JOptionPane.showMessageDialog(
            this,
            file.getName() + " is " + image.getWidth() + "x" + image.getHeight() + ".\nMinecraft skins must be 64x64 or 64x32 pictures.",
            "Change skin",
            JOptionPane.ERROR_MESSAGE
         );
         return;
      }

      this.pendingFile = file;
      this.pendingSkin = image;
      this.updatingControls = true;
      (SkinRenderer.looksSlim(image) ? this.slimButton : this.classicButton).setSelected(true);
      this.updatingControls = false;
      this.setStatus("Showing " + file.getName() + ". Click Save changes to use it.");
      this.updatePreview();
      this.updateControls();
   }

   private void useDefaultSkin() {
      int answer = JOptionPane.showConfirmDialog(
         this, "Go back to a default skin?\nYour current skin will be removed from your account.", "Use default skin", JOptionPane.OK_CANCEL_OPTION
      );
      final MicrosoftUserAuthentication auth = this.getMicrosoftUser();
      if (answer != JOptionPane.OK_OPTION || auth == null) {
         return;
      }

      this.setStatus("Changing to a default skin...");
      this.runInBackground(() -> {
         this.withToken(auth, this.service::resetSkin);
         this.showProfile(this.withToken(auth, this.service::getProfile), "You're using a default skin now.");
      });
   }

   private void saveSkinPicture() {
      if (this.currentSkin == null) {
         return;
      }

      JFileChooser chooser = new JFileChooser();
      chooser.setDialogTitle("Save skin picture");
      chooser.setSelectedFile(new File((this.profile == null ? "skin" : this.profile.getName()) + ".png"));
      if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
         return;
      }

      File file = chooser.getSelectedFile();
      if (!file.getName().toLowerCase().endsWith(".png")) {
         file = new File(file.getParentFile(), file.getName() + ".png");
      }

      try {
         ImageIO.write(this.currentSkin, "png", file);
         this.setStatus("Saved " + file.getName() + ".");
      } catch (IOException e) {
         JOptionPane.showMessageDialog(this, "Couldn't save " + file.getName() + ": " + e.getMessage(), "Save skin picture", JOptionPane.ERROR_MESSAGE);
      }
   }

   private void saveChanges() {
      final MicrosoftUserAuthentication auth = this.getMicrosoftUser();
      final Profile original = this.profile;
      if (auth == null || original == null || !this.hasChanges()) {
         return;
      }

      final File file = this.pendingFile;
      final SkinModel model = this.getSelectedModel();
      final Cape cape = this.getSelectedCape();
      final boolean capeChanged = !Objects.equals(capeId(cape), capeId(original.getActiveCape()));
      this.setStatus("Saving...");
      this.runInBackground(() -> {
         if (file != null) {
            this.withToken(auth, token -> this.service.uploadSkin(token, file, model));
         } else if (model != original.getSkinModel() && original.getSkinUrl() != null) {
            this.withToken(auth, token -> this.service.changeModel(token, original.getSkinUrl(), model));
         }
         if (capeChanged) {
            this.withToken(auth, token -> this.service.setCape(token, capeId(cape)));
         }

         this.showProfile(this.withToken(auth, this.service::getProfile), "Saved! Your new look shows up the next time you join a world.");
      });
   }

   private void undoChanges() {
      if (this.profile == null) {
         return;
      }

      this.pendingFile = null;
      this.pendingSkin = null;
      this.updatingControls = true;
      (this.profile.getSkinModel() == SkinModel.SLIM ? this.slimButton : this.classicButton).setSelected(true);
      this.capeBox.setSelectedItem(this.profile.getActiveCape() == null ? NO_CAPE : this.profile.getActiveCape());
      this.updatingControls = false;
      this.setStatus(" ");
      this.updatePreview();
      this.updateControls();
   }

   private void onPendingChange() {
      if (!this.updatingControls) {
         this.updatePreview();
         this.updateControls();
      }
   }

   private void onCapeSelected() {
      if (this.updatingControls) {
         return;
      }

      final Cape cape = this.getSelectedCape();
      if (cape != null && cape.getUrl() != null && !this.capeImages.containsKey(cape.getId())) {
         // Fetch the cape picture so the preview can show it before it's saved.
         this.minecraftLauncher.getLauncher().getVersionManager().getExecutorService().execute(() -> {
            try {
               this.capeImages.put(cape.getId(), this.service.downloadTexture(cape.getUrl()));
               SwingUtilities.invokeLater(this::updatePreview);
            } catch (ProfileException e) {
               LOGGER.warn("Couldn't download cape " + cape.getName(), e);
            }
         });
      }

      this.updatePreview();
      this.updateControls();
   }

   private void updatePreview() {
      BufferedImage skin = this.pendingSkin != null ? this.pendingSkin : this.currentSkin;
      if (skin == null) {
         if (this.profile != null) {
            this.preview.setMessage("No skin");
         }
         return;
      }

      Cape cape = this.getSelectedCape();
      this.preview.setSkin(skin, this.getSelectedModel() == SkinModel.SLIM, cape == null ? null : this.capeImages.get(cape.getId()));
   }

   private void updateControls() {
      boolean ready = this.profile != null && !this.busy;
      this.classicButton.setEnabled(ready);
      this.slimButton.setEnabled(ready);
      this.changeSkinButton.setEnabled(ready);
      this.defaultSkinButton.setEnabled(ready);
      this.saveSkinFileButton.setEnabled(ready && this.currentSkin != null);
      this.capeBox.setEnabled(ready && this.capeBox.getItemCount() > 1);
      this.saveButton.setEnabled(ready && this.hasChanges());
      this.undoButton.setEnabled(ready && this.hasChanges());
      this.reloadButton.setEnabled(!this.busy && this.getMicrosoftUser() != null);
   }

   private boolean hasChanges() {
      return this.profile != null
         && (this.pendingFile != null
            || this.getSelectedModel() != this.profile.getSkinModel()
            || !Objects.equals(capeId(this.getSelectedCape()), capeId(this.profile.getActiveCape())));
   }

   private SkinModel getSelectedModel() {
      return this.slimButton.isSelected() ? SkinModel.SLIM : SkinModel.CLASSIC;
   }

   private Cape getSelectedCape() {
      Object selected = this.capeBox.getSelectedItem();
      return selected instanceof Cape ? (Cape)selected : null;
   }

   private static String capeId(Cape cape) {
      return cape == null ? null : cape.getId();
   }

   private void setStatus(String status) {
      this.statusLabel.setText(status == null || status.isEmpty() ? " " : status);
   }

   /** The selected account, if it's a Microsoft account with a Minecraft profile. */
   private MicrosoftUserAuthentication getMicrosoftUser() {
      ProfileManager profiles = this.minecraftLauncher.getProfileManager();
      String selected = profiles.getSelectedUser();
      UserAuthentication auth = selected == null ? null : profiles.getAuthDatabase().getByUUID(selected);
      if (auth instanceof MicrosoftUserAuthentication && auth.getSelectedProfile() != null) {
         return (MicrosoftUserAuthentication)auth;
      }

      return null;
   }

   /** Runs a call with a fresh access token, logging in again once if Mojang says the token expired. */
   private <T> T withToken(MicrosoftUserAuthentication auth, ProfileCall<T> call) throws ProfileException {
      if (!auth.canPlayOnline()) {
         this.refreshLogin(auth);
      }

      try {
         return call.run(auth.getAuthenticatedToken());
      } catch (ProfileException.Unauthorized e) {
         this.refreshLogin(auth);
         return call.run(auth.getAuthenticatedToken());
      }
   }

   private void refreshLogin(MicrosoftUserAuthentication auth) throws ProfileException {
      try {
         auth.logIn();
      } catch (AuthenticationException e) {
         throw new ProfileException("Couldn't refresh your login: " + e.getMessage() + " Try logging in again from Switch User.", e);
      }

      try {
         this.minecraftLauncher.getProfileManager().saveProfiles();
      } catch (IOException e) {
         LOGGER.warn("Couldn't save the refreshed login", e);
      }
   }

   private void runInBackground(BackgroundTask task) {
      this.busy = true;
      this.updateControls();
      this.minecraftLauncher.getLauncher().getVersionManager().getExecutorService().execute(() -> {
         try {
            task.run();
         } catch (ProfileException e) {
            LOGGER.warn("Skin tab: " + e.getMessage(), e.getCause());
            SwingUtilities.invokeLater(() -> {
               this.setStatus(e.getMessage());
               if (this.currentSkin == null) {
                  this.preview.setMessage("Couldn't load skin");
               }
            });
         } catch (RuntimeException e) {
            LOGGER.error("Skin tab failed", e);
            SwingUtilities.invokeLater(() -> this.setStatus("Something went wrong: " + e));
         } finally {
            SwingUtilities.invokeLater(() -> {
               this.busy = false;
               this.updateControls();
            });
         }
      });
   }

   private static JLabel heading(String text) {
      JLabel label = new JLabel(text);
      label.setFont(label.getFont().deriveFont(Font.BOLD));
      return label;
   }

   private static JPanel row() {
      JPanel panel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
      ((java.awt.FlowLayout)panel.getLayout()).setHgap(6);
      return panel;
   }

   private interface ProfileCall<T> {
      T run(String accessToken) throws ProfileException;
   }

   private interface BackgroundTask {
      void run() throws ProfileException;
   }
}
