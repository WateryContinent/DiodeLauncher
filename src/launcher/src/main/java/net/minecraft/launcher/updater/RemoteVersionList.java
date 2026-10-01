package net.minecraft.launcher.updater;

import com.mojang.launcher.Http;
import com.mojang.launcher.OperatingSystem;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;

public class RemoteVersionList extends VersionList {
   private final String baseUrl;
   private final Proxy proxy;

   public RemoteVersionList(String baseUrl, Proxy proxy) {
      this.baseUrl = baseUrl;
      this.proxy = proxy;
   }

   @Override
   public boolean hasAllFiles(CompleteMinecraftVersion version, OperatingSystem os) {
      return true;
   }

   @Override
   public String getContent(String path) throws IOException {
      return Http.performGet(this.getUrl(path), this.proxy);
   }

   @Override
   public URL getUrl(String file) throws MalformedURLException {
      if (file.startsWith("http://") || file.startsWith("https://")) {
         return new URL(file);
      }
      return new URL(this.baseUrl + file);
   }

   public Proxy getProxy() {
      return this.proxy;
   }
}
