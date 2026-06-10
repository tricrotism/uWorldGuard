# uWorldGuard Dev Guide

Paper plugin. Java 25, Paper API 26.1.2, `paper-plugin.yml` + `PluginLoader`.
**Folia-compatible and thread-safe by default. Performance comes first, always.**

## Working Practices

Bias toward caution over speed. Use judgment on trivial tasks.

**Think before coding.** State assumptions. If multiple interpretations exist, present them — don't pick silently. If something is unclear, stop and ask.

**Simplicity first.** Minimum code that solves the problem. No features, abstractions, configurability, or error handling beyond what's asked. If you wrote 200 lines and it could be 50, rewrite.

**Surgical changes.** Touch only what you must. Don't refactor adjacent code or "improve" formatting. Match existing style. Notice unrelated dead code? Mention it, don't delete. Remove imports/variables only if *your* change made them unused.

**Light logic first.** Reach for the lightest tool: a one-line guard over a helper, a helper over a class, a local over a new field, a direct `if` chain over a strategy/registry until 3+ branches truly vary independently. When unsure between two approaches, write the lighter one first; escalate only when it provably fails.

**Update deprecated usages when you touch a file** — but only on lines you'd already be reading or changing. Don't open a file purely to chase deprecations. If the migration is non-trivial (signature change, callsite cascade), ask first.

---

## Performance — Always First

This is the top priority. Every hot path (per-tick, per-block, per-entity, per-player, event handlers) is scrutinized for allocations and lookups.

- **Hoist invariants out of loops.** Resolve services, config values, and constants once above the loop, never per iteration.
- **`x >> 4` for block→chunk coords**, not `block.getChunk().getX()` (allocates a Chunk).
- **Avoid per-iteration allocation.** Reuse buffers/arrays. Don't create `new Location(...)`, lists, or boxed objects inside tight loops when you can mutate or reuse.
- **No accidentally quadratic loops** — no nested iteration over online players, no per-block work inside per-player work.
- **Cache service/handle lookups** (`getServer().getServicesManager()`, registry lookups, `getWorld(name)`) before hot loops — each is a map lookup.
- **Prefer primitive-keyed maps / arrays** over boxed `HashMap<Integer,…>` in hot paths; box integers outside the JDK cache (-128..127) once and reuse.
- **`ArrayList` for sequential appends**, never `CopyOnWriteArrayList` (O(n²)).
- **Cheap guard first.** Order conditions cheapest-and-most-likely-to-fail first; skip no-op work (e.g. `multiplier == 1.0`) entirely.
- **Don't subscribe to events you can scope tighter.** Filter by material/region/world early and return fast; the common case must be branch-out cheap.
- **Batch high-frequency work — don't fire one call per event.** Per-event progress/currency/stat hooksop each trigger a write, recompute, or redraw; at hot-path frequency that compounds fast. Accumulate deltas in a thread-safe per-player buffer and flush on a short interval or boundary (logout, level-up, sell-cycle end) — one flush of N updates beats N writes. When adding such a hook, check whether it can ride an existing batch flush.

When a change touches a hot path, say so and note the cost you avoided.

---

## Folia Compatibility & Thread Safety

The server uses regionized threading. There is **no single main thread.** Code must be correct under Folia or it will crash/corrupt state.

**Schedulers — use the right one. Never `Bukkit.getScheduler()` / `BukkitRunnable` (broken on Folia).**

- World/block/entity ops at a location → `RegionScheduler`:
  ```java
  Bukkit.getRegionScheduler().execute(plugin, location, () -> block.setType(Material.STONE));
  ```
- Ops following a specific entity → `EntityScheduler` (handles the entity changing regions):
  ```java
  entity.getScheduler().run(plugin, task -> entity.teleport(target), null);
  ```
- Server-global work with no location context → `GlobalRegionScheduler`:
  ```java
  Bukkit.getGlobalRegionScheduler().execute(plugin, () -> { /* global state */ });
  ```
- Async (no Bukkit API inside) → `AsyncScheduler`.

**Bukkit API only on the owning region's thread.** Entities, blocks, inventories, particles, world reads/writes belong on the region thread that owns that location/entity. **Never touch Bukkit API from an async task.**

**Shared mutable state must be thread-safe.** Use the lightest correct type:
- `ConcurrentHashMap` for shared maps — never `HashMap`/`LinkedHashMap` for a field touched by more than one region/thread.
- `ConcurrentHashMap.newKeySet()` for shared sets.
- `merge()` for atomic counter increments — `getOrDefault + 1 + put` is a TOCTOU race.
- `computeIfAbsent` / `putIfAbsent` for check-set, never `containsKey` + `put`.
- `AtomicInteger`/`AtomicLong`/`LongAdder` for shared counters; `AtomicReference` for swap.
- **Local variables that never escape a single region tick can use plain `HashSet`/`ArrayList`** — non-concurrent and faster when the value never escapes.

Don't assume two events run on the same thread. Cross-region data sharing goes through a concurrent structure or the correct scheduler.

---

## Code Quality

Ordinary code-smell scrutiny when writing or reviewing:

- Null-safety on **external** returns (Bukkit lookups like `getPlayer`, `getWorld`, config reads, registry finds). Internal code can trust its own invariants.
- Resource leaks — listeners not unregistered on disable, scheduled tasks left running, display entities not removed.
- Mutable shared state without thread-safety (see above).
- Accidentally quadratic loops.
- Broad `catch (Exception e)` that swallows context. Rethrow, log with context, or scope the catch.
- Misleading identifiers (`get…` that mutates, `enabled` that means the opposite).
- Dead branches and unreachable returns introduced by your own changes.

Don't flag: events/commands with many parameters (API signatures), duplicate string literals, hardcoded dependency versions, `TODO` comments, legitimate singletons.

---

## Plugin Conventions

- `UWorldGuard` is the `JavaPlugin` main; `UWorldGuardLoader` is the `PluginLoader` (runtime library downloads). There is no bootstrapper — if you need registration that must happen before enable (e.g. PacketEvents load), add a `PluginBootstrap` and wire it into `paper-plugin.yml`.
- This is a `paper-plugin.yml` plugin (not `plugin.yml`) — `load: STARTUP`. Declare dependencies in `paper-plugin.yml`, not via `getPlugin()` hacks.
- Pass `plugin` into every scheduler call; don't reach for a static instance unless one already exists.
- Register listeners/tasks with handles you can clean up in `onDisable()`.

## Commands — Cloud

Use the **Cloud command framework** (`org.incendo.cloud`, Paper module) — annotation-driven, not `Commands.create()` / Brigadier trees by hand, and not the legacy `getCommand(...)` / `plugin.yml` `commands:` block. Build one `PaperCommandManager` (async-completions, register it once at enable) and parse annotated handlers.

```java
@Command("region claim <name>")
@Permission("uworldguard.region.claim")
public void claim(Player sender, @Argument("name") String name) { ... }
```

- One `@Permission` node per command — Cloud removes it from suggestions for unauthorized callers and it's auditable. Gate with `@Permission`, **not** `isOp()` in the body.
- Multiple `@Command` annotations on one method = aliases.
- Register the manager and parse annotations once; don't rebuild per command.
- Caption/exception handling goes through Cloud's handlers, not ad-hoc `try/catch` in each method.

## Messages — Adventure + MiniMessage

Use Adventure `Component` + MiniMessage. **Never legacy `§` codes, never string concatenation for dynamic content.**

```java
player.sendMessage(MiniMessage.miniMessage().deserialize(
        "<red>You don't have permission to claim here."));
```

For dynamic content use tag resolvers (`Placeholder.unparsed` / `Placeholder.component`), not string interpolation:

```java
player.sendMessage(MiniMessage.miniMessage().deserialize(
        "<gray>Region <aqua><name></aqua> claimed.",
        Placeholder.unparsed("name", regionName)));
```

Use `<!i>` in item lore to suppress default italic. Lore is a `List<Component>`.

---

## Packets — PacketEvents

Use **PacketEvents** (`com.github.retrooper.packetevents`) for packet-level work — wrappers, listeners, sending synthetic packets. Don't hand-roll NMS/ProtocolLib.

- Initialize in the **bootstrap** (`PacketEvents.getAPI()` load) and terminate on disable — it must be ready before listeners register.
- Register listeners via `PacketEventsAPI.getEventManager().registerListener(...)` with an explicit `PacketListenerPriority`.
- **Packet listeners run on Netty I/O threads, not a region thread.** Treat every handler as async: no Bukkit API inside (no entity/world/inventory access). To act on the world, hand off to the correct Folia scheduler (`RegionScheduler` / `entity.getScheduler()`). Shared state touched from a listener must be concurrent.
- Keep handlers allocation-light — they fire per packet, the hottest path in the plugin. Read only the fields you need, cancel early, return fast.
- Use PacketEvents wrapper classes (`WrapperPlayServer…` / `WrapperPlayClient…`) rather than raw packet objects.

---

## Comments

No decorative section dividers (`// ─── region logic ───`). Comment only where the logic isn't self-evident.

---

## Commit Messages

Title: short imperative description, single line. Scope tag if useful (`[Regions]`, `[Folia]`).

Non-trivial changes: blank line, then 1–2 short paragraphs — **Problem** (concrete: class, symptom, impact) then **Fix** (mechanism, key invariant). Trivial commits (version bumps, cosmetics) stay title-only.

**Never add `Co-Authored-By:` trailers or emoji.**

---

## Summary — Do / Don't

| Do                                                      | Don't                                           |
|---------------------------------------------------------|-------------------------------------------------|
| Java only, explicit types                               | `var`, dynamic dispatch                         |
| `RegionScheduler` / `EntityScheduler` / `GlobalRegion…` | `Bukkit.getScheduler()`, `BukkitRunnable`       |
| Bukkit API on the owning region thread                  | Bukkit API from an async task                   |
| `ConcurrentHashMap` for shared state                    | `HashMap` for shared mutable state              |
| `merge()` / `computeIfAbsent`                           | `getOrDefault + 1 + put`, `containsKey` + `put` |
| Hoist invariants above hot loops                        | Re-resolving services/config per iteration      |
| `x >> 4` for block→chunk                                | `block.getChunk().getX()`                       |
| Cloud `@Command` / `@Permission` annotations            | `plugin.yml` `commands:` / `getCommand(...)`    |
| `@Permission(...)` to gate commands                     | `isOp()` inside a command handler               |
| PacketEvents wrappers; hand off to Folia scheduler      | Bukkit API inside a packet listener (I/O thread)|
| PacketEvents for packet work                            | hand-rolled NMS / ProtocolLib                   |
| Null-check external Bukkit returns                      | trusting `getPlayer`/`getWorld` to be non-null  |
