package net.minecraft.launcher.updater;

import com.mojang.launcher.OperatingSystem;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Empty remote version list used by offline mode. Local installed versions are
 * still discovered by LocalVersionList, but no version metadata is fetched.
 */
public final class OfflineVersionList extends VersionList {
    @Override
    public void refreshVersions() {
        clearCache();
    }

    @Override
    public boolean hasAllFiles(CompleteMinecraftVersion version, OperatingSystem os) {
        return true;
    }

    @Override
    public String getContent(String path) throws IOException {
        throw new IOException("Remote version access is disabled in offline mode");
    }

    @Override
    public URL getUrl(String file) throws MalformedURLException {
        throw new MalformedURLException("Remote version access is disabled in offline mode");
    }
}
