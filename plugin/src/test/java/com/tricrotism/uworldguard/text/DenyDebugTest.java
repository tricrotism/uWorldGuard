package com.tricrotism.uworldguard.text;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.listeners.BuildProtectionListener;
import com.tricrotism.uworldguard.region.ProtectedCuboidRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code /uwg debug} tells a player who is refused a break, for each reason a break is refused.
 */
class DenyDebugTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    private ServerMock server;
    private WorldMock world;
    private RegionManager manager;
    private BuildProtectionListener build;
    private PlayerMock player;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        final PluginMock plugin = MockBukkit.createMockPlugin("uWorldGuard");
        world = server.addSimpleWorld("world");
        final File dataFolder = plugin.getDataFolder();
        assertTrue(dataFolder.exists() || dataFolder.mkdirs());
        Files.writeString(new File(dataFolder, "messages.yml").toPath(),
            "cooldown-seconds: 0\nmessages:\n  no-permission: \"<red>No.\"\n", StandardCharsets.UTF_8);
        final RegionContainerImpl container = new RegionContainerImpl(plugin, NO_STORE);
        container.loadAll();
        manager = container.get(world);
        assertNotNull(manager);
        build = new BuildProtectionListener(container.createQuery(), new MessageService(plugin), true);
        player = server.addPlayer();
        DenyDebug.toggle(player);
    }

    @AfterEach
    void tearDown() {
        DenyDebug.clear(player.getUniqueId());
        MockBukkit.unmock();
    }

    private ProtectedCuboidRegion claim(final String id) {
        final ProtectedCuboidRegion region = new ProtectedCuboidRegion(id,
            BlockVector3.at(0, 0, 0), BlockVector3.at(15, 255, 15));
        manager.addRegion(region);
        return region;
    }

    private List<String> breakAndRead() {
        build.onBreak(new BlockBreakEvent(world.getBlockAt(8, 64, 8), player));
        final List<String> messages = new ArrayList<>();
        for (String line = player.nextMessage(); line != null; line = player.nextMessage()) {
            messages.add(line);
        }
        return messages;
    }

    private String debugLine() {
        return breakAndRead().stream().filter(m -> m.contains("[uwg debug]")).findFirst()
            .orElseThrow(() -> new AssertionError("no debug line"));
    }

    @Test
    void anUnsetFlagIsExplainedByMembership() {
        claim("farm");

        final String line = debugLine();

        assertTrue(line.contains("block-break"), line);
        assertTrue(line.contains("not a member of farm"), line);
    }

    @Test
    void aDenyNamesTheRegionThatSetIt() {
        claim("farm").setFlag(Flags.BLOCK_BREAK, State.DENY);

        assertTrue(debugLine().contains("deny in farm (priority 0)"));
    }

    @Test
    void anInheritedDenyNamesTheParent() {
        final ProtectedCuboidRegion district = new ProtectedCuboidRegion("district",
            BlockVector3.at(-100, 0, -100), BlockVector3.at(100, 255, 100));
        district.setFlag(Flags.BLOCK_BREAK, State.DENY);
        manager.addRegion(district);
        final ProtectedCuboidRegion farm = claim("farm");
        farm.setPriority(5);
        farm.setParent(district);

        assertTrue(debugLine().contains("deny in farm (priority 5), inherited from district"));
    }

    @Test
    void nothingIsSaidWithDebugOff() {
        claim("farm");
        DenyDebug.toggle(player);

        assertTrue(breakAndRead().stream().noneMatch(m -> m.contains("[uwg debug]")));
    }
}
