package com.tricrotism.uworldguard.commands;

import com.tricrotism.uworldguard.text.Messages;
import com.tricrotism.uworldguard.wgcompat.CompatDiagnostics;
import com.tricrotism.uworldguard.wgcompat.WgCompatBridge;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.paper.util.sender.Source;
import org.jspecify.annotations.NullMarked;

import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

@NullMarked
public final class CompatCommands {

    @Command("uworldguard|uwg|worldguard|wg|region|regions|rg compat")
    @CommandDescription("WorldGuard API compatibility diagnostics")
    @Permission("uworldguard.compat")
    public void compat(final Source sender) {
        sender.source().sendMessage(Messages.format("<gray>WorldGuard API compatibility"));

        if (WgCompatBridge.active()) {
            sender.source().sendMessage(Messages.format(
                "<green>Active<gray> — emulating the WorldGuard 7 API. This is uWorldGuard, "
                    + "not EngineHub WorldGuard; report problems here, not to EngineHub."));
        } else {
            sender.source().sendMessage(Messages.format(
                "<red>Inactive<gray> — <reason>",
                Placeholder.unparsed("reason", WgCompatBridge.inactiveReason())));
        }

        final Plugin worldEdit = Bukkit.getPluginManager().getPlugin("WorldEdit");
        sender.source().sendMessage(Messages.format("<gray>WorldEdit: <white><version>",
            Placeholder.unparsed("version",
                worldEdit == null ? "not installed" : worldEdit.getPluginMeta().getVersion())));

        line(sender, "Region queries", CompatDiagnostics.QUERIES);
        line(sender, "Region reads", CompatDiagnostics.REGION_READS);
        line(sender, "Region mutations", CompatDiagnostics.REGION_MUTATIONS);
        line(sender, "Player wraps", CompatDiagnostics.WRAPS);
        line(sender, "Custom flags registered", CompatDiagnostics.FLAG_REGISTRATIONS);
        line(sender, "Session dispatches", CompatDiagnostics.SESSION_DISPATCHES);
        line(sender, "Events fired", CompatDiagnostics.EVENTS_FIRED);
        line(sender, "Flag values rejected", CompatDiagnostics.FLAG_PARSE_FAILURES);

        final Map<String, String> parseErrors = CompatDiagnostics.flagParseErrors();
        if (!parseErrors.isEmpty()) {
            sender.source().sendMessage(Messages.format(
                "<yellow>Last rejection per flag<gray> — the owning plugin's own words:"));
            for (final Map.Entry<String, String> entry : parseErrors.entrySet()) {
                sender.source().sendMessage(Messages.format("<gray>  <white><flag><gray>: <reason>",
                    Placeholder.unparsed("flag", entry.getKey()),
                    Placeholder.unparsed("reason", entry.getValue())));
            }
        }

        final Map<String, Long> stubs = CompatDiagnostics.stubHits();
        if (stubs.isEmpty()) {
            sender.source().sendMessage(Messages.format("<gray>No unimplemented API members have been called."));
            return;
        }
        sender.source().sendMessage(Messages.format(
            "<yellow>Unimplemented members called<gray> — plugins relying on these may misbehave:"));
        for (final Map.Entry<String, Long> entry : stubs.entrySet()) {
            sender.source().sendMessage(Messages.format("<gray>  <white><member><gray> × <count>",
                Placeholder.unparsed("member", entry.getKey()),
                Placeholder.unparsed("count", Long.toString(entry.getValue()))));
        }
    }

    private static void line(final Source sender, final String label, final LongAdder counter) {
        sender.source().sendMessage(Messages.format("<gray><label>: <white><count>",
            Placeholder.unparsed("label", label),
            Placeholder.unparsed("count", Long.toString(counter.sum()))));
    }
}
