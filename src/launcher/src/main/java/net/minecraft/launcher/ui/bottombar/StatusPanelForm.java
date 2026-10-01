package net.minecraft.launcher.ui.bottombar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.launcher.Http;
import com.mojang.launcher.updater.LowerCaseEnumTypeAdapterFactory;
import java.awt.GridBagConstraints;
import java.net.URL;
import java.util.List;
import java.util.Map;
import javax.swing.JLabel;
import net.minecraft.launcher.Launcher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class StatusPanelForm extends SidebarGridForm {
   private static final Logger LOGGER = LogManager.getLogger();
   private static final String SERVER_SESSION = "session.minecraft.net";
   private static final String SERVER_LOGIN = "login.minecraft.net";
   private final Launcher minecraftLauncher;
   private final JLabel sessionStatus = new JLabel("???");
   private final JLabel loginStatus = new JLabel("???");
   private final Gson gson = new GsonBuilder().registerTypeAdapterFactory(new LowerCaseEnumTypeAdapterFactory()).create();

   public StatusPanelForm(Launcher minecraftLauncher) {
      this.minecraftLauncher = minecraftLauncher;
      this.createInterface();
      this.refreshStatuses();
   }

   @Override
   protected void populateGrid(GridBagConstraints constraints) {
      this.add(new JLabel("Multiplayer:", 2), constraints, 0, 0, 0, 1, 17);
      this.add(this.sessionStatus, constraints, 1, 0, 1, 1);
      this.add(new JLabel("Login:", 2), constraints, 0, 1, 0, 1, 17);
      this.add(this.loginStatus, constraints, 1, 1, 1, 1);
   }

   public JLabel getSessionStatus() {
      return this.sessionStatus;
   }

   public JLabel getLoginStatus() {
      return this.loginStatus;
   }

   public void refreshStatuses() {
      this.minecraftLauncher
         .getLauncher()
         .getVersionManager()
         .getExecutorService()
         .submit(
            new Runnable() {
               @Override
               public void run() {
                  try {
                     TypeToken<List<Map<String, StatusPanelForm.ServerStatus>>> token = new TypeToken<List<Map<String, StatusPanelForm.ServerStatus>>>() {};

                     for (Map<String, StatusPanelForm.ServerStatus> serverStatusInformation : (List<Map<String, StatusPanelForm.ServerStatus>>)(List<?>)StatusPanelForm.this.gson
                        .fromJson(
                           Http.performGet(new URL("http://status.mojang.com/check"), StatusPanelForm.this.minecraftLauncher.getLauncher().getProxy()),
                           token.getType()
                        )) {
                        if (serverStatusInformation.containsKey("login.minecraft.net")) {
                           StatusPanelForm.this.loginStatus.setText(serverStatusInformation.get("login.minecraft.net").title);
                        } else if (serverStatusInformation.containsKey("session.minecraft.net")) {
                           StatusPanelForm.this.sessionStatus.setText(serverStatusInformation.get("session.minecraft.net").title);
                        }
                     }
                  } catch (Exception e) {
                     StatusPanelForm.LOGGER.error("Couldn't get server status", e);
                  }
               }
            }
         );
   }

   public enum ServerStatus {
      GREEN("Online, no problems detected."),
      YELLOW("May be experiencing issues."),
      RED("Offline, experiencing problems.");

      private final String title;

      ServerStatus(String title) {
         this.title = title;
      }
   }
}
