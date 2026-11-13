package com.tricrotism.uworldguard.storage;

import com.tricrotism.uworldguard.flags.Flags;
import com.tricrotism.uworldguard.flags.RegionGroup;
import com.tricrotism.uworldguard.flags.State;
import com.tricrotism.uworldguard.region.*;
import com.tricrotism.uworldguard.util.BlockVector3;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The streamed save writes the document SnakeYAML would have: the same data comes back when Bukkit
 * reads either one, for every shape, flag kind and awkward string.
 */
class RegionSerializerTest {

    private static ProtectedCuboidRegion cuboid(final String id, final int x1, final int z1, final int x2, final int z2) {
        return new ProtectedCuboidRegion(id, BlockVector3.at(x1, 0, z1), BlockVector3.at(x2, 255, z2));
    }

    private static RegionManager everything() {
        final RegionManager manager = new RegionManager();
        final ProtectedRegion parent = cuboid("district", -100, -100, 100, 100);
        parent.setFlag(Flags.PVP, State.DENY);
        manager.addRegion(parent);

        final ProtectedRegion claim = cuboid("claim_1-a", 0, 0, 15, 15);
        claim.setPriority(-3);
        claim.setParent(parent);
        claim.getOwners().addPlayer(UUID.fromString("51460127-e567-461a-8379-1e3ad4c403f1"));
        claim.getOwners().addPlayer(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        claim.getMembers().addGroup("Staff");
        claim.setFlag(Flags.GREETING, "<yellow>Welcome: it's \"home\" # here\n\tsecond line ünï ✓");
        claim.setFlag(Flags.HEAL_AMOUNT, 1.5);
        claim.setFlag(Flags.HEAL_DELAY, 3);
        claim.setFlag(Flags.NOTIFY_ENTER, true);
        claim.setFlag(Flags.NONPLAYER_PROTECTION_DOMAINS, Set.of("on", "<<", "plain words"));
        claim.setFlag(Flags.PVP, State.ALLOW);
        claim.setFlagGroup(Flags.PVP, RegionGroup.NON_MEMBERS);
        final Map<String, Object> kept = new LinkedHashMap<>();
        kept.put("a", 1);
        kept.put("b", List.of("x", "-1", "x#y", " lead", "trail ", "", "yes", "~"));
        kept.put("c", Map.of("d", true, "e", 2.25));
        kept.put("f", Map.of());
        claim.putUnresolvedFlag("custom-map", kept);
        claim.putUnresolvedFlag("custom-text", "hello: world");
        claim.putUnresolvedFlag("custom-number", 3.5);
        claim.putUnresolvedFlag("custom-list", List.of());
        manager.addRegion(claim);

        manager.addRegion(cuboid("123", 200, 200, 210, 210));
        manager.addRegion(cuboid("yes", 300, 300, 310, 310));
        manager.addRegion(new ProtectedPolygonRegion("poly",
            List.of(BlockVector3.at(0, 0, 0), BlockVector3.at(10, 0, 0), BlockVector3.at(-5, 0, 10)), 10, 60));
        manager.addRegion(new ProtectedCylinderRegion("cyl", 50, 50, 5, 7, 0, 100));
        manager.addRegion(new ProtectedSphereRegion("ball", 80, 64, 80, 4, 5, 6));
        manager.addRegion(new ProtectedPolyhedronRegion("gem", List.of(BlockVector3.at(0, 0, 0),
            BlockVector3.at(10, 0, 0), BlockVector3.at(0, 10, 0), BlockVector3.at(0, 0, 10))));
        final Map<String, ProtectedRegion> parts = new LinkedHashMap<>();
        parts.put("pit", cuboid("pit", 40, 40, 50, 50));
        parts.put("ring", new ProtectedSphereRegion("ring", 45, 30, 45, 8, 3, 8));
        manager.addRegion(new ProtectedCompositeRegion("mine", parts));
        manager.addRegion(new ProtectedCarvedRegion("arena", cuboid("s", 100, 100, 140, 140),
            Map.of("stands", cuboid("stands", 110, 110, 120, 120))));
        final GlobalProtectedRegion global = new GlobalProtectedRegion();
        global.setFlag(Flags.PVP, State.DENY);
        manager.addRegion(global);
        return manager;
    }

    private static Map<String, Object> read(final String text) throws Exception {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator((char) 0);
        yaml.loadFromString(text);
        return plain(yaml);
    }

    private static Map<String, Object> plain(final ConfigurationSection section) {
        final Map<String, Object> out = new LinkedHashMap<>();
        for (final String key : section.getKeys(false)) {
            final Object value = section.get(key);
            out.put(key, value instanceof ConfigurationSection child ? plain(child) : value);
        }
        return out;
    }

    @Test
    void theStreamedDocumentReadsBackAsTheTreeOne() throws Exception {
        final RegionManager manager = everything();
        final RegionSerializer serializer = new RegionSerializer();

        final String streamed = serializer.streamed(manager);

        assertNotNull(streamed, "nothing here needs a YAML library");
        assertEquals(read(serializer.toYamlTree(manager)), read(streamed));
    }

    @Test
    void aStreamedDocumentLoadsAndSavesToTheSameText() throws Exception {
        final RegionSerializer serializer = new RegionSerializer();
        final String first = serializer.toYaml(everything());
        final RegionManager loaded = new RegionManager();
        serializer.fromYaml(first, loaded);

        assertEquals(first, serializer.toYaml(loaded));
    }

    @Test
    void charactersYamlRefusesRawStillLoad() throws Exception {
        final String text = "a\nb \u0081 \u009f   ￾ ￿ 😀 lone \ud800 end";
        final RegionManager manager = new RegionManager();
        final ProtectedRegion claim = cuboid("claim", 0, 0, 15, 15);
        claim.setFlag(Flags.GREETING, text);
        manager.addRegion(claim);
        final RegionSerializer serializer = new RegionSerializer();

        final String streamed = serializer.streamed(manager);
        assertNotNull(streamed);
        final RegionManager loaded = new RegionManager();
        serializer.fromYaml(streamed, loaded);

        final ProtectedRegion read = loaded.getRegion("claim");
        assertNotNull(read);
        assertEquals(text, read.getFlag(Flags.GREETING));
    }

    @Test
    void aValueOnlyAYamlLibraryCanWriteFallsBackToTheTree() throws Exception {
        final RegionManager manager = new RegionManager();
        final ProtectedRegion claim = cuboid("claim", 0, 0, 15, 15);
        claim.putUnresolvedFlag("odd", Set.of("a"));
        manager.addRegion(claim);
        final RegionSerializer serializer = new RegionSerializer();

        assertNull(serializer.streamed(manager));
        assertEquals(read(serializer.toYamlTree(manager)), read(serializer.toYaml(manager)));
    }

    @Test
    void anEmptyWorldWritesAnEmptyMap() throws Exception {
        final RegionSerializer serializer = new RegionSerializer();

        assertEquals(read(serializer.toYamlTree(new RegionManager())), read(serializer.toYaml(new RegionManager())));
    }
}
