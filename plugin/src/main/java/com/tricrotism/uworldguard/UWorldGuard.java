package com.tricrotism.uworldguard;

import com.tricrotism.uworldguard.commands.CompatCommands;
import com.tricrotism.uworldguard.commands.RegionCommands;
import com.tricrotism.uworldguard.config.*;
import com.tricrotism.uworldguard.gui.ChatInputListener;
import com.tricrotism.uworldguard.gui.ChatInputService;
import com.tricrotism.uworldguard.integration.BStats;
import com.tricrotism.uworldguard.integration.ReportedVersion;
import com.tricrotism.uworldguard.listeners.*;
import com.tricrotism.uworldguard.migration.MigrationCommands;
import com.tricrotism.uworldguard.packet.PacketHooks;
import com.tricrotism.uworldguard.packet.PacketSink;
import com.tricrotism.uworldguard.region.FlagLifecycle;
import com.tricrotism.uworldguard.region.RegionContainer;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionQuery;
import com.tricrotism.uworldguard.selection.SelectionService;
import com.tricrotism.uworldguard.selection.WandSelectionProvider;
import com.tricrotism.uworldguard.service.*;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.storage.SqlRegionStore;
import com.tricrotism.uworldguard.storage.YamlRegionStore;
import com.tricrotism.uworldguard.text.ChatTags;
import com.tricrotism.uworldguard.text.MessageService;
import com.tricrotism.uworldguard.text.Messages;
import com.tricrotism.uworldguard.wgcompat.FlagBridge;
import com.tricrotism.uworldguard.wgcompat.SessionDispatch;
import com.tricrotism.uworldguard.wgcompat.WgCompatBridge;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bstats.MetricsBase;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import xyz.xenondevs.invui.InvUI;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

@NullMarked
public final class UWorldGuard extends com.sk89q.worldguard.bukkit.WorldGuardPlugin {

    private @Nullable RegionContainerImpl container;
    private @Nullable Settings settings;
    private @Nullable MovementListener movement;
    private @Nullable CollisionService collision;
    private @Nullable WorldEditFlagGuard worldEditGuard;
    private @Nullable RegionStore store;
    private @Nullable MetricsBase metrics;
    private volatile @Nullable ScheduledTask autoSaveTask;
    private volatile @Nullable ScheduledTask messageExpiryTask;
    private @Nullable FlagLifecycle flagLifecycle;
    private @Nullable PlayerTickService playerTick;
    private @Nullable ChunkUnloadService chunkUnload;
    private @Nullable PendingRestores restores;

    /**
     * Re-reads {@code config.yml} and applies everything that can change at runtime. Movement mode
     * and the autosave cadence are included, so switching EVENT/TASK, retuning the poll, or changing
     * {@code auto-save-minutes} no longer needs a restart; storage backend and the selection wand are
     * still bound at enable.
     */
    public void reloadSettings() {
        reloadConfig();
        final Settings current = this.settings;
        if (current != null) {
            current.load(getConfig());
        }
        EventGate.load(getConfig(), getLogger());
        InteractionWhitelist.load(getConfig(), getLogger());
        Messages.expire();
        final MovementListener listener = this.movement;
        if (listener != null && current != null) {
            listener.applySettings(current);
        }
        if (current != null) {
            scheduleAutoSave(current);
        }
    }

    /**
     * (Re)starts the autosave at the configured cadence, cancelling any task already running. Reading
     * the period once at enable and dropping the handle meant a reload could neither retune it nor
     * start one that had been configured off at boot.
     */
    private void scheduleAutoSave(final Settings settings) {
        final ScheduledTask running = this.autoSaveTask;
        if (running != null) {
            running.cancel();
            this.autoSaveTask = null;
        }
        final RegionContainerImpl regionContainer = this.container;
        final long period = settings.autoSaveMinutes();
        if (period <= 0L || regionContainer == null) {
            return;
        }
        this.autoSaveTask = getServer().getAsyncScheduler().runAtFixedRate(this,
            _ -> regionContainer.saveAll(), period, period, TimeUnit.MINUTES);
    }

    /**
     * Publishes the reported version before any other plugin loads.
     *
     * <p>Consumers read the WorldGuard version at two moments, and one of them is their own
     * {@code onLoad} — PvPManager registers its region flag there and skips it when the version
     * check fails. Doing this at enable is already too late for those, so it happens here, off the
     * config file directly: {@link Settings} is not built until enable.
     */
    @Override
    public void onLoad() {
        ReportedVersion.publish(this, getConfig().getString("compatibility.report-version", ""), getLogger());
    }

    @Override
    public void onEnable() {
        InvUI.getInstance().setPlugin(this);
        saveDefaultConfig();
        final List<String> addedSettings =
            ConfigUpdater.merge(this, new File(getDataFolder(), "config.yml"), "config.yml");
        reloadConfig();
        if (!addedSettings.isEmpty()) {
            getLogger().info("config.yml gained " + addedSettings.size()
                + " new setting(s) from this version: " + String.join(", ", addedSettings));
        }
        final Settings settings = new Settings();
        settings.load(getConfig());
        this.settings = settings;
        if (!settings.membershipGrantsTrust()) {
            getLogger().warning("regions.membership-grants-trust is off: region owners and members"
                + " are not trusted anywhere. Protection is flag-only, so a region without a build"
                + " flag now denies every player including its owner. Set it back to true to"
                + " restore the stored trust lists, which are kept either way.");
        }
        EventGate.load(getConfig(), getLogger());
        InteractionWhitelist.load(getConfig(), getLogger());
        final boolean worldGuardCompat = prepareWorldGuardCompat();

        final RegionStore store = createStore(settings);
        this.store = store;

        final RegionContainerImpl regionContainer = new RegionContainerImpl(this, store);
        regionContainer.loadAll();
        this.container = regionContainer;
        final FlagLifecycle flagLifecycle = new FlagLifecycle(this, regionContainer);
        flagLifecycle.install();
        this.flagLifecycle = flagLifecycle;
        getServer().getPluginManager().registerEvents(flagLifecycle, this);

        getServer().getServicesManager().register(
            RegionContainer.class, regionContainer, this, ServicePriority.Normal);
        UWorldGuardApi.bind(regionContainer);
        UWorldGuardApi.bindBypass(Bypass::has);
        if (worldGuardCompat) {
            activateWorldGuardCompat(regionContainer);
        }

        final RegionQuery query = regionContainer.createQuery();
        final SelectionService selection = new SelectionService(this, settings);
        final MessageService messages = new MessageService(this);
        final CollisionService collision = new CollisionService(this);
        this.collision = collision;
        final ChamberedPearlTracker pearls = new ChamberedPearlTracker(this);
        final ChatInputService chatInput = new ChatInputService();
        final ChatTags chatTags = new ChatTags();

        final PendingRestores restores = new PendingRestores(this);
        this.restores = restores;
        restores.start();

        activatePackets();
        getServer().getPluginManager().registerEvents(new BuildProtectionListener(query, messages), this);
        final MovementListener movement =
            new MovementListener(this, query, messages, collision, pearls, chatTags, restores, settings);
        this.movement = movement;
        getServer().getPluginManager().registerEvents(movement, this);
        getServer().getPluginManager().registerEvents(new NaturalListener(query), this);
        getServer().getPluginManager().registerEvents(new CropTrampleListener(query), this);
        getServer().getPluginManager().registerEvents(new EntityListener(regionContainer, query), this);
        getServer().getPluginManager().registerEvents(new PlayerStateListener(query, messages), this);
        getServer().getPluginManager().registerEvents(new ItemUseListener(regionContainer, query, messages), this);
        getServer().getPluginManager().registerEvents(new EndCrystalListener(query, messages), this);
        getServer().getPluginManager().registerEvents(new WorkbenchListener(query, messages), this);
        getServer().getPluginManager().registerEvents(new DeathListener(query, messages), this);
        getServer().getPluginManager().registerEvents(new TravelListener(query), this);
        getServer().getPluginManager().registerEvents(new PearlListener(regionContainer, pearls), this);
        getServer().getPluginManager().registerEvents(new ChatInputListener(this, chatInput), this);
        final ChunkUnloadService chunkUnload = new ChunkUnloadService(this, regionContainer);
        this.chunkUnload = chunkUnload;
        final WandSelectionProvider wand = selection.wandListener();
        getServer().getPluginManager().registerEvents(
            new WorldListener(regionContainer, chunkUnload, movement, wand), this);
        getServer().getPluginManager().registerEvents(new ChatListener(chatTags), this);
        getServer().getPluginManager().registerEvents(new CommandListener(query, messages), this);
        getServer().getPluginManager().registerEvents(new PistonListener(query), this);
        getServer().getPluginManager().registerEvents(new VehicleListener(query, messages), this);
        getServer().getPluginManager().registerEvents(new InteractionListener(query, messages), this);
        getServer().getPluginManager().registerEvents(new MachineListener(regionContainer, query, messages), this);
        if (wand != null) {
            getServer().getPluginManager().registerEvents(wand, this);
        }

        new RegionCommands(this, regionContainer, selection, chatInput, messages)
            .register(new MigrationCommands(this, regionContainer), new CompatCommands());

        movement.start();
        movement.replayOnlineRestores();
        final PlayerTickService playerTick = new PlayerTickService(this, regionContainer, query);
        this.playerTick = playerTick;
        getServer().getPluginManager().registerEvents(playerTick, this);
        playerTick.start();
        chunkUnload.start();

        if (getServer().getPluginManager().getPlugin("WorldEdit") != null) {
            final WorldEditFlagGuard guard = new WorldEditFlagGuard(regionContainer);
            guard.register();
            this.worldEditGuard = guard;
        }

        scheduleAutoSave(settings);
        this.messageExpiryTask = getServer().getAsyncScheduler().runAtFixedRate(this,
            _ -> Messages.expire(), Messages.EXPIRE_MINUTES, Messages.EXPIRE_MINUTES, TimeUnit.MINUTES);

        if (settings.updateCheck()) {
            new UpdateChecker(this).start();
        }

        // bStats | https://bstats.org/plugin/bukkit/uWorldGuard/32190
        this.metrics = BStats.start(this, 32190);
    }

    /**
     * Decides whether the bundled WorldGuard 7 API compatibility layer can run, and if so registers
     * the flags it contributes.
     *
     * <p>Two things have to be true. WorldEdit must be present, because WorldGuard's API signatures
     * are built out of WorldEdit types and its consumers call {@code BukkitAdapter} themselves.
     * And nothing else may already answer to the name "WorldGuard" — we declare {@code provides}
     * for it, so if the real plugin is installed too, both would claim the same API and whichever
     * one a consumer reached would be a coin toss.
     *
     * <p>Separate from {@link #activateWorldGuardCompat} because of when each half has to run. The
     * shim contributes engine flags of its own ({@code teleport}, {@code teleport-message} and the
     * rest of the WorldGuard-only set), and {@code RegionSerializer} drops any stored flag whose name
     * is not registered by the time the world is read — so registering after the load silently threw
     * those values away and the next autosave persisted their absence. The binding half still needs
     * the container, which does not exist yet, so it stays where it was.
     *
     * @return whether {@link #activateWorldGuardCompat} should run once the container exists
     */
    private boolean prepareWorldGuardCompat() {
        final Plugin claimant = getServer().getPluginManager().getPlugin("WorldGuard");
        if (claimant != null && claimant != this) {
            WgCompatBridge.markInactive("another plugin provides WorldGuard ("
                + claimant.getName() + " " + claimant.getPluginMeta().getVersion() + ")");
            getLogger().severe("WorldGuard is installed alongside uWorldGuard. The WorldGuard API"
                + " compatibility layer is disabled — remove one of the two plugins.");
            return false;
        }
        if (getServer().getPluginManager().getPlugin("WorldEdit") == null) {
            WgCompatBridge.markInactive("WorldEdit is not installed");
            getLogger().warning("WorldGuard API compatibility layer inactive: WorldEdit is not"
                + " installed. Plugins that require WorldGuard will not function.");
            return false;
        }
        FlagBridge.bindFlags();
        return true;
    }

    /**
     * Binds the compatibility layer to the loaded container, so plugins built against WorldGuard see
     * uWorldGuard's regions and flags. Only called when {@link #prepareWorldGuardCompat} cleared it.
     */
    private void activateWorldGuardCompat(final RegionContainer regionContainer) {
        WgCompatBridge.bind(regionContainer, this);
        WgCompatBridge.bypassCheck(Bypass::has);
        getLogger().info("WorldGuard API compatibility layer active (emulating the WorldGuard 7 API"
            + " — this is uWorldGuard " + ReportedVersion.real(this)
            + ", not EngineHub WorldGuard).");
    }

    /**
     * Arms the packet layer behind {@code disable-collision}.
     *
     * <p>PacketEvents is a required dependency, but an incompatible build can still fail to link, so
     * {@code PacketSink} (the only class naming it outside InvUI) is loaded only from here. Without it
     * collision still works for every player on the main scoreboard, which is nearly all of them.
     */
    private void activatePackets() {
        try {
            PacketSink.install(this);
        } catch (final LinkageError | RuntimeException e) {
            getLogger().log(Level.WARNING, "PacketEvents is installed but its API could not be used;"
                + " players on a per-player scoreboard will not get client-side disable-collision.", e);
        }
    }

    private RegionStore createStore(final Settings settings) {
        if (settings.isSqlEnabled()) {
            try {
                getLogger().info("Using SQL storage backend.");
                return new SqlRegionStore(settings.sqlUrl(), settings.sqlUser(), settings.sqlPassword());
            } catch (final Exception e) {
                getLogger().log(Level.WARNING, "Failed to initialise SQL storage; falling back to YAML.", e);
            }
        }
        return new YamlRegionStore(getDataFolder());
    }

    /**
     * Releases in dependency order: stop the things that read regions, then the ones that hold state
     * outside this plugin, then write the regions out.
     *
     * <p>The write is the one step that must happen. Everything before it touches players, foreign
     * plugins and a third-party packet library on a server that is already tearing down, and any one
     * of them throwing used to take the region save with it — an unclean shutdown lost every change
     * made since the last autosave.
     *
     * <p>Cancelling comes first for the same reason. The compat layer hands control to other people's
     * code, and on a plugin-only disable — a hot swap, or a plugin manager — one throw in there used
     * to leave the autosave and the per-player ticks running against a plugin that no longer exists.
     */
    @Override
    public void onDisable() {
        try {
            releaseRuntime();
        } catch (final RuntimeException | LinkageError e) {
            getLogger().log(Level.SEVERE, "Error while shutting down; saving regions anyway.", e);
        }
        if (container != null) {
            container.saveAllBlocking();
        }
        if (store != null) {
            store.close();
            store = null;
        }
    }

    private void releaseRuntime() {
        final ScheduledTask autoSave = this.autoSaveTask;
        if (autoSave != null) {
            autoSave.cancel();
            this.autoSaveTask = null;
        }
        final ScheduledTask messageExpiry = this.messageExpiryTask;
        if (messageExpiry != null) {
            messageExpiry.cancel();
            this.messageExpiryTask = null;
        }
        if (playerTick != null) {
            playerTick.stop();
            playerTick = null;
        }
        if (chunkUnload != null) {
            chunkUnload.stop();
            chunkUnload = null;
        }
        if (restores != null) {
            restores.stop();
            restores = null;
        }
        if (movement != null) {
            movement.stop();
        }
        if (flagLifecycle != null) {
            flagLifecycle.uninstall();
            flagLifecycle = null;
        }
        SessionDispatch.shutdown();
        WgCompatBridge.unbind();
        UWorldGuardApi.bind(null);
        UWorldGuardApi.bindBypass(null);
        if (movement != null) {
            movement.shutdown();
        }
        if (worldEditGuard != null) {
            worldEditGuard.unregister();
            worldEditGuard = null;
        }
        if (collision != null) {
            collision.shutdown();
        }
        if (PacketHooks.ACTIVE) {
            PacketSink.uninstall();
        }
        if (metrics != null) {
            metrics.shutdown();
            metrics = null;
        }
    }
}
