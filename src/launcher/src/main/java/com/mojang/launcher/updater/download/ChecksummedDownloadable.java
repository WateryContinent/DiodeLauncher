package com.mojang.launcher.updater.download;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import org.apache.commons.io.Charsets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

public class ChecksummedDownloadable extends Downloadable {
   private String checksum;

   public ChecksummedDownloadable(Proxy proxy, URL remoteFile, File localFile, boolean forceDownload) {
      super(proxy, remoteFile, localFile, forceDownload);
   }

   @Override
   public String download() throws IOException {
      this.numAttempts++;
      this.ensureFileWritable(this.getTarget());
      File target = this.getTarget();
      File checksumFile = new File(target.getAbsolutePath() + ".sha");
      String localHash = null;
      if (target.isFile()) {
         localHash = getDigest(target, "SHA-1", 40);
      }

      if (target.isFile() && checksumFile.isFile()) {
         this.checksum = this.readFile(checksumFile, "");
         if (this.checksum.length() == 0 || this.checksum.trim().equalsIgnoreCase(localHash)) {
            return "Local file matches local checksum, using that";
         }

         this.checksum = null;
         FileUtils.deleteQuietly(checksumFile);
      }

      if (this.checksum == null) {
         try {
            HttpURLConnection connection = this.makeConnection(new URL(this.getUrl().toString() + ".sha1"));
            int status = connection.getResponseCode();
            if (status / 100 == 2) {
               InputStream inputStream = connection.getInputStream();

               try {
                  this.checksum = IOUtils.toString(inputStream, Charsets.UTF_8);
                  FileUtils.writeStringToFile(checksumFile, this.checksum);
               } catch (IOException e) {
                  this.checksum = "";
               } finally {
                  IOUtils.closeQuietly(inputStream);
               }
            } else if (checksumFile.isFile()) {
               this.checksum = this.readFile(checksumFile, "");
            } else {
               this.checksum = "";
            }
         } catch (IOException e) {
            if (!target.isFile()) {
               throw e;
            }

            this.checksum = this.readFile(checksumFile, "");
         }
      }

      try {
         HttpURLConnection connection = this.makeConnection(this.getUrl());
         int status = connection.getResponseCode();
         if (status / 100 == 2) {
            this.updateExpectedSize(connection);
            InputStream inputStream = new MonitoringInputStream(connection.getInputStream(), this.getMonitor());
            FileOutputStream outputStream = new FileOutputStream(this.getTarget());
            String digest = copyAndDigest(inputStream, outputStream, "SHA", 40);
            if (this.checksum == null || this.checksum.length() == 0) {
               return "Didn't have checksum so assuming our copy is good";
            } else if (this.checksum.trim().equalsIgnoreCase(digest)) {
               return "Downloaded successfully and checksum matched";
            } else {
               throw new RuntimeException(String.format("Checksum did not match downloaded file (Checksum was %s, downloaded %s)", this.checksum, digest));
            }
         } else if (this.getTarget().isFile()) {
            return "Couldn't connect to server (responded with " + status + ") but have local file, assuming it's good";
         } else {
            throw new RuntimeException("Server responded with " + status);
         }
      } catch (IOException e) {
         if (!this.getTarget().isFile() || this.checksum != null && this.checksum.length() != 0) {
            throw e;
         } else {
            return "Couldn't connect to server (" + e.getClass().getSimpleName() + ": '" + e.getMessage() + "') but have local file, assuming it's good";
         }
      }
   }

   private String readFile(File file, String def) {
      try {
         return FileUtils.readFileToString(file);
      } catch (Throwable ignored) {
         return def;
      }
   }
}
