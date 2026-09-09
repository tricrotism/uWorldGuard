package com.tricrotism.uworldguard.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Publishes {@code disable-collision} to the clients the server-side scoreboard team cannot reach.
 *
 * <p>uWorldGuard puts no-collision players in a team on the <em>main</em> scoreboard, which the
 * server itself consults. A player whom another plugin has moved to a per-player scoreboard never
 * receives that team, and collision is predicted on every client — so their client keeps pushing.
 * This sends them the team directly.
 *
 * <p>Not a packet listener: it registers nothing and reads nothing off the wire. {@link #collision}
 * walks every online player's scoreboard, which is server-wide state, so it is called on the global
 * region thread — the one that owns it.
 */
@NullMarked
public final class PacketSink implements PacketHooks.Sink {

    /**
     * One team per affected player, named {@code uwg_nc_<player>}.
     *
     * <p>Not the server-side team name, and not a single shared one either. A client on a per-player
     * scoreboard may already hold a team named like the server's from the plugin that put them there,
     * and a second {@code CREATE} for a name the client already holds is a protocol error — so the
     * name has to be ours and it has to be unique per player. Sharing one name across every
     * no-collision player looks cheaper and is wrong twice over: the second player to enter sends a
     * duplicate {@code CREATE}, and the first to leave sends the {@code REMOVE} that deletes the team
     * out from under everyone still inside, silently returning them to client-side collision with
     * nothing left to re-send it.
     */
    private static final String TEAM_PREFIX = "uwg_nc_";

    private final Plugin plugin;

    private PacketSink(final Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Arms {@link PacketHooks}. Only called once uWorldGuard has seen the PacketEvents plugin, so
     * this class is never loaded on a server without it.
     */
    public static void install(final Plugin plugin) {
        PacketHooks.install(new PacketSink(plugin));
    }

    /**
     * Idempotent, and safe on a server that never installed one.
     */
    public static void uninstall() {
        PacketHooks.uninstall();
    }

    /**
     * Sends every client that needs it the no-collision team for the player named {@code entry}.
     *
     * <p>It goes to all viewers off the main scoreboard, not just to that player: each client
     * predicts collision for <em>its own</em> player, so a bystander who does not know they are
     * uncollidable keeps pushing themselves off them and rubber-bands against a server that
     * disagrees. Sending to the affected player alone covers one of the two clients involved.
     *
     * <p>The team entry is passed by name rather than as a {@link Player}, because the caller has it
     * on the thread that owns that player and this runs on the global one.
     *
     * <p>Which scoreboard a viewer is on, and what team it has the entry in, are that viewer's own
     * state: the server keeps the player-to-scoreboard mapping in a plain map that every region
     * thread writes through {@code setScoreboard}, so reading it from here raced whichever
     * scoreboard or tab plugin last moved someone. Each viewer therefore gets one hop to their own
     * scheduler, which is the same shape the rest of the plugin uses to reach another player.
     */
    @Override
    public void collision(final String entry, final boolean disabled) {
        final ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return;
        }
        final Scoreboard main = manager.getMainScoreboard();
        for (final Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.getScheduler().run(plugin, _ -> {
                final Scoreboard board = viewer.getScoreboard();
                if (board != main && PacketHooks.ACTIVE) {
                    send(viewer, entry, disabled, board);
                }
            }, null);
        }
    }

    private static void send(final Player viewer, final String entry, final boolean disabled,
                             final Scoreboard board) {
        final String team = TEAM_PREFIX + entry;
        final WrapperPlayServerTeams packet = disabled
            ? new WrapperPlayServerTeams(team, WrapperPlayServerTeams.TeamMode.CREATE,
            info(board.getEntryTeam(entry)), List.of(entry))
            : new WrapperPlayServerTeams(team, WrapperPlayServerTeams.TeamMode.REMOVE,
            (WrapperPlayServerTeams.ScoreBoardTeamInfo) null);
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
        } catch (final LinkageError | RuntimeException e) {
            // A PacketEvents build that does not match this server writes a malformed packet rather
            // than failing cleanly. Losing client-side collision is survivable; killing the global
            // region thread is not, so the layer stands itself down.
            PacketHooks.uninstall();
        }
    }

    /**
     * A client can only hold a player in one team, so this assignment replaces whatever team the
     * viewer's scoreboard had them in. Carrying that team's prefix, suffix and colour across keeps
     * the nametag the other plugin drew, instead of blanking it.
     */
    private static WrapperPlayServerTeams.ScoreBoardTeamInfo info(final @Nullable Team existing) {
        if (existing == null) {
            return new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                Component.empty(), null, null,
                WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
                WrapperPlayServerTeams.CollisionRule.NEVER,
                NamedTextColor.WHITE,
                WrapperPlayServerTeams.OptionData.ALL);
        }
        final NamedTextColor color = NamedTextColor.namedColor(existing.color().value());
        return new WrapperPlayServerTeams.ScoreBoardTeamInfo(
            existing.displayName(), existing.prefix(), existing.suffix(),
            WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
            WrapperPlayServerTeams.CollisionRule.NEVER,
            color == null ? NamedTextColor.WHITE : color,
            WrapperPlayServerTeams.OptionData.ALL);
    }
}
