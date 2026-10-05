package com.tricrotism.uworldguard.commands;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.ProtectedCuboidRegion;
import com.tricrotism.uworldguard.region.RegionContainerImpl;
import com.tricrotism.uworldguard.region.RegionManager;
import com.tricrotism.uworldguard.storage.RegionStore;
import com.tricrotism.uworldguard.util.BlockVector3;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.incendo.cloud.paper.util.sender.Source;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code -w <world>} flag, parsed by Cloud exactly as the server would: what the console can
 * reach with it, and that it does not swallow or get swallowed by the arguments around it.
 */
class WorldFlagCommandTest {

    private static final RegionStore NO_STORE = new RegionStore() {
        @Override
        public void load(final String worldName, final RegionManager manager) {}

        @Override
        public void save(final String worldName, final RegionManager manager) {}
    };

    private static final class Commands extends CommandManager<Source> {
        Commands() {
            super(ExecutionCoordinator.simpleCoordinator(), CommandRegistrationHandler.nullCommandRegistrationHandler());
        }

        @Override
        public boolean hasPermission(final Source sender, final String permission) {
            return true;
        }
    }

    private ServerMock server;
    private Commands commands;
    private ProtectedCuboidRegion spawn;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        final WorldMock arena = server.addSimpleWorld("arena");
        final RegionContainerImpl container =
            new RegionContainerImpl(MockBukkit.createMockPlugin("uWorldGuard"), NO_STORE);
        container.loadAll();
        final RegionManager manager = container.get(arena);
        assertNotNull(manager);
        spawn = new ProtectedCuboidRegion("spawn", BlockVector3.at(0, 0, 0), BlockVector3.at(32, 255, 32));
        manager.addRegion(spawn);

        commands = new Commands();
        new AnnotationParser<>(commands, Source.class)
            .parse(new RegionCommands(null, container, null, null, null));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void run(final CommandSender sender, final String line) {
        commands.commandExecutor().executeCommand(new Source() {
            @Override
            public CommandSourceStack stack() {
                throw new UnsupportedOperationException();
            }

            @Override
            public CommandSender source() {
                return sender;
            }
        }, line).join();
    }

    @Test
    void theConsoleSetsAFlagInANamedWorld() {
        run(server.getConsoleSender(), "rg flag spawn pvp deny -w arena");

        assertEquals(State.DENY, spawn.getFlag(Flags.PVP));
    }

    @Test
    void aValueWithSpacesKeepsItsWordsAndLosesTheWorldFlag() {
        run(server.getConsoleSender(), "rg flag spawn deny-message Keep out -w arena");

        assertEquals("Keep out", spawn.getFlag(Flags.DENY_MESSAGE));
    }

    @Test
    void aNegativeValueIsNotReadAsAFlag() {
        run(server.getConsoleSender(), "rg flag spawn walk-speed -0.5 -w arena");

        assertEquals(-0.5, spawn.getFlag(Flags.WALK_SPEED));
    }

    @Test
    void aGroupAfterTheValueStillQualifiesTheFlag() {
        run(server.getConsoleSender(), "rg flag spawn pvp deny -g members -w arena");

        assertEquals(State.DENY, spawn.getFlag(Flags.PVP));
        assertEquals(com.tricrotism.uworldguard.flags.RegionGroup.MEMBERS, spawn.getFlagGroup(Flags.PVP));
    }

    @Test
    void aNegativePriorityIsNotReadAsAFlag() {
        run(server.getConsoleSender(), "rg priority spawn -5 -w arena");

        assertEquals(-5, spawn.getPriority());
    }

    @Test
    void theConsoleIsToldToNameAWorld() {
        run(server.getConsoleSender(), "rg priority spawn 7");

        assertEquals(0, spawn.getPriority());
    }

    @Test
    void aPlayerReachesAnotherWorld() {
        final PlayerMock player = server.addPlayer();

        run(player, "rg priority spawn 7 -w arena");

        assertEquals(7, spawn.getPriority());
    }

    @Test
    void listTakesTheWorldFlagWithoutAPage() {
        final PlayerMock player = server.addPlayer();

        run(player, "rg list -w arena");

        final net.kyori.adventure.text.Component listing = player.nextComponentMessage();
        assertNotNull(listing);
        assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(listing).contains("in arena"));
    }

    @Test
    void listStillTakesAPageBeforeTheWorldFlag() {
        final PlayerMock player = server.addPlayer();

        run(player, "rg list 1 -w arena");

        final net.kyori.adventure.text.Component listing = player.nextComponentMessage();
        assertNotNull(listing);
        assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(listing).contains("page 1/1"));
    }

    @Test
    void anUnknownWorldChangesNothing() {
        run(server.getConsoleSender(), "rg priority spawn 7 -w nowhere");

        assertEquals(0, spawn.getPriority());
    }
}
