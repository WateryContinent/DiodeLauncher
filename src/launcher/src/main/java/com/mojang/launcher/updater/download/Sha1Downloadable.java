package com.mojang.launcher.updater.download;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;

/**
 * Downloadable backed by the SHA-1 and size already supplied by Mojang's
 * modern launcher metadata. Unlike the old ChecksummedDownloadable this does
 * not try to discover a sibling .sha1 URL.
 */
public class Sha1Downloadable extends Downloadable {
   private final String sha1;

   public Sha1Downloadable(Proxy proxy, URL remoteFile, File localFile, String sha1, long expectedSize, boolean forceDownload) {
      super(proxy, remoteFile, localFile, forceDownload);
      this.sha1 = sha1;
      if (expectedSize > 0L) {
         this.setExpectedSize(expectedSize);
      }
   }

   @Override
   public String download() throws IOException {
      this.numAttempts++;
      this.ensureFileWritable(this.getTarget());

      if (!this.shouldIgnoreLocal() && this.getTarget().isFile() && this.sha1 != null && !this.sha1.isEmpty()) {
         String local = getDigest(this.getTarget(), "SHA-1", 40);
         if (this.sha1.equalsIgnoreCase(local)) {
            return "Local file matches expected SHA-1";
         }
      }

      HttpURLConnection connection = this.makeConnection(this.getUrl());
      int status = connection.getResponseCode();
      if (status / 100 != 2) {
         if (this.getTarget().isFile() && (this.sha1 == null || this.sha1.isEmpty())) {
            return "Couldn't connect to server (responded with " + status + ") but have local file, assuming it's good";
         }
         throw new RuntimeException("Server responded with " + status);
      }

      this.updateExpectedSize(connection);
      InputStream inputStream = new MonitoringInputStream(connection.getInputStream(), this.getMonitor());
      FileOutputStream outputStream = new FileOutputStream(this.getTarget());
      String downloaded = copyAndDigest(inputStream, outputStream, "SHA-1", 40);
      if (this.sha1 == null || this.sha1.isEmpty()) {
         return "Downloaded successfully (no SHA-1 supplied)";
      }
      if (!this.sha1.equalsIgnoreCase(downloaded)) {
         throw new RuntimeException("SHA-1 did not match downloaded file (expected " + this.sha1 + ", downloaded " + downloaded + ")");
      }
      return "Downloaded successfully and SHA-1 matched";
   }
}
