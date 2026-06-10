package com.tricrotism.uworldguard.storage;

import com.tricrotism.uworldguard.region.RegionManager;
import org.jspecify.annotations.NullMarked;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Stores each world's regions in {@code <dataFolder>/regions/<world>.yml}.
 */
@NullMarked
public final class YamlRegionStore implements RegionStore {

    private final File regionsDir;
    private final RegionSerializer serializer = new RegionSerializer();

    public YamlRegionStore(final File dataFolder) {
        this.regionsDir = new File(dataFolder, "regions");
    }

    @Override
    public void load(final String worldName, final RegionManager manager) throws Exception {
        final File file = new File(regionsDir, worldName + ".yml");
        if (!file.exists()) {
            return;
        }
        serializer.fromYaml(Files.readString(file.toPath()), manager);
    }

    @Override
    public void save(final String worldName, final RegionManager manager) throws Exception {
        Files.createDirectories(regionsDir.toPath());
        final Path target = new File(regionsDir, worldName + ".yml").toPath();
        final Path tmp = new File(regionsDir, worldName + ".yml.tmp").toPath();
        Files.writeString(tmp, serializer.toYaml(manager));
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
