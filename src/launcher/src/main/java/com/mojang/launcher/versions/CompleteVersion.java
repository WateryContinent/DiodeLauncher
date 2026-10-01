package com.mojang.launcher.versions;

import java.util.Date;

public interface CompleteVersion extends Version {
   @Override
   String getId();

   @Override
   ReleaseType getType();

   @Override
   Date getUpdatedTime();

   @Override
   Date getReleaseTime();

   int getMinimumLauncherVersion();

   boolean appliesToCurrentEnvironment();

   String getIncompatibilityReason();

   boolean isSynced();

   void setSynced(boolean var1);
}
