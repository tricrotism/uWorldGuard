package com.tricrotism.uworldguard.migration;

import com.tricrotism.uworldguard.UWorldGuard;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.text.Messages;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Flag;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.paper.util.sender.Source;
import org.jspecify.annotations.NullMarked;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Admin command that imports regions from an existing WorldGuard installation. Parsed by the
 * same Cloud annotation parser as the region commands (see {@code RegionCommands#register}).
 */
@NullMarked
public final class MigrationCommands {

    private final UWorldGuard plugin;
    private final RegionContainerImpl container;
    private final WorldGuardImporter importer;

    public MigrationCommands(final UWorldGuard plugin, final RegionContainerImpl container) {
        this.plugin = plugin;
        this.container = container;
        this.importer = new WorldGuardImporter(plugin.getDataFolder().getParentFile());
    }

    @Command("uworldguard|uwg|worldguard|wg migrate worldguard")
    @CommandDescription("Import regions from an existing WorldGuard installation")
    @Permission("uworldguard.admin.migrate")
    public void migrate(final Source sender, @Flag(value = "overwrite", aliases = "o") final boolean overwrite) {
        final Map<String, RegionManager> targets = new LinkedHashMap<>();
        for (final World world : Bukkit.getWorlds()) {
            final RegionManager manager = container.get(world);
            if (manager != null) {
                targets.put(world.getName(), manager);
            }
        }

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> run(sender, targets, overwrite));
    }

    private void run(final Source sender, final Map<String, RegionManager> targets, final boolean overwrite) {
        int totalImported = 0;
        int totalConflicts = 0;
        int worldsWithData = 0;

        for (final Map.Entry<String, RegionManager> entry : targets.entrySet()) {
            final String world = entry.getKey();
            if (!importer.hasData(world)) {
                continue;
            }
            worldsWithData++;

            final WorldGuardImporter.Result result;
            try {
                result = importer.importWorld(world, entry.getValue(), overwrite);
            } catch (final Exception e) {
                plugin.getLogger().log(Level.WARNING, "WorldGuard migration failed for world " + world, e);
                error(sender, "Failed to migrate world <aqua>" + world + "</aqua>: " + e.getMessage());
                continue;
            }
            if (result == null) {
                continue;
            }

            if (result.imported() > 0) {
                entry.getValue().markDirty();
            }
            totalImported += result.imported();
            totalConflicts += result.conflicts().size();

            String line = "<aqua>" + world + "</aqua>: imported " + result.imported();
            if (result.skipped() > 0) {
                line += ", skipped " + result.skipped() + " unsupported";
            }
            if (!result.conflicts().isEmpty()) {
                line += ", " + result.conflicts().size() + " already exist (<aqua>"
                    + String.join("</aqua>, <aqua>", result.conflicts()) + "</aqua>)";
            }
            note(sender, line);
        }

        if (worldsWithData == 0) {
            error(sender, "No WorldGuard region data found under <aqua>plugins/WorldGuard/worlds</aqua>.");
            return;
        }

        success(sender, "Migration complete: imported " + totalImported + " region(s) from "
            + worldsWithData + " world(s).");
        if (totalConflicts > 0 && !overwrite) {
            note(sender, "<yellow>" + totalConflicts + " region(s) already existed and were left untouched. "
                + "Re-run with <aqua>--overwrite</aqua> to replace them.");
        }
    }

    private static void error(final Source sender, final String message) {
        sender.source().sendMessage(Messages.format("<red>" + message));
    }

    private static void success(final Source sender, final String message) {
        sender.source().sendMessage(Messages.format("<green>" + message));
    }

    private static void note(final Source sender, final String message) {
        sender.source().sendMessage(Messages.format("<gray>" + message));
    }
}
