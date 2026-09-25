# uWorldGuard

World Guard... but out of this world.

Region protection for Paper and Folia. Claim an area, decide what's allowed inside it, and the
plugin enforces it. Over 165 flags, a GUI for people who don't like commands, and an API for
people who do.

> **Need help?** Join our support Discord: https://tricrotism.com/support

|               |                          |
|---------------|--------------------------|
| **Minecraft** | 1.21.11+                 |
| **Server**    | Paper (Folia-compatible) |
| **Java**      | 25 or newer              |
| **Version**   | 1.1.2                    |

---

## For server owners

### Installing

1. Drop `uWorldGuard-1.1.2.jar` into your `plugins/` folder.
2. Restart the server. **Not** `/reload` — this plugin loads at startup.
3. On first start it downloads a few libraries it needs. That's normal and only happens once.

That's it. Nothing else is required — no database to set up, no other plugins to install.

> **Java 25 is required.** If the server won't start and the log mentions `UnsupportedClassVersionError`,
> your Java is too old. Nothing else will fix it.

### Your first region in 60 seconds

```
/wg define spawn
```

...but first you need to select the area. Hold a **wooden axe** (the default wand), then:

- **Left-click** a block for corner 1
- **Right-click** a block for corner 2
- Run `/wg define spawn`

Now nobody but you can build there. To let a friend build too:

```
/wg addmember spawn Steve
```

To trust a whole permission group instead of one player, prefix it with `g:`:

```
/wg addmember spawn g:staff
```

That trusts anyone holding the `group.staff` permission, which is what Vault-backed permissions
plugins grant every member of a group. It only matches players who are online, since an offline
player has no permissions to check.

To change what's allowed, open the menu instead of memorising flag names:

```
/wg menu spawn
```

> If you have **WorldEdit** installed, uWorldGuard uses your WorldEdit selection instead and the
> wooden axe wand is ignored. Both work — you don't have to choose.

### How regions actually decide things

Three ideas, and everything else follows from them:

**Owners and members.** Owners can do everything in the region and manage it. Members can build.
Everyone else is an outsider. Outsiders are the ones flags usually restrict.

**Flags.** Each flag is one rule — `pvp`, `mob-spawning`, `chest-access`. Set it and the plugin
enforces it. Unset flags fall back to normal server behaviour.

**Priority.** Regions overlap. The one with the **highest priority** wins.
A `pvp deny` shop inside a `pvp allow` arena works — give the shop a higher priority:

```
/wg priority shop 10
```

**Parents** let a region inherit its parent's flags, so you set a rule once and every child gets it:

```
/wg parent shop mall
```

### Bypassing your own rules

Bypassing takes **both** halves — the node *and* the command:

1. Hold `uworldguard.bypass`. It is **off by default, including for operators**.
2. Run `/wg bypass` to arm it.

Holding the node on its own does nothing. A protection plugin that silently stops protecting against
whoever happens to hold a node is worse than one that makes you ask, so you stay an ordinary player
until you say otherwise.

```
/wg bypass
```

Bypass never survives a session — it turns itself off when you log out, and revoking the node takes
effect immediately even if the player is already armed.

### Commands

Every command works as `/uworldguard`, `/uwg`, `/worldguard`, `/wg`, `/region`, `/regions`, or `/rg`. Use whichever you
like.

`/wg` on its own is the place to start: three commands to try first, then one clickable row per section.
`/wg help <section>` prints that section, `/wg help all` prints everything.

| Command                                                      | What it does                                                                |
|--------------------------------------------------------------|-----------------------------------------------------------------------------|
| `/wg`                                                        | Start here — sections, and the three commands worth knowing first           |
| `/wg help <section>`                                         | The commands in one section (`regions`, `flags`, `people`, …) or `all`      |
| `/wg define <id>`                                            | Create a region from your selection, a polygon if the selection is one      |
| `/wg define <id> cylinder <radiusX> <radiusZ> <minY> <maxY>` | Create a cylinder where you're standing                                     |
| `/wg define <id> sphere <radiusX> <radiusY> <radiusZ>`       | Create a sphere where you're standing                                       |
| `/wg define <id> polygon <minY> <maxY>`                      | Create a polygon from a WorldEdit selection, with a Y range of your own     |
| `/wg redefine <id> [shape] [...]`                            | Reshape a region, keeping its flags, members and priority                   |
| `/wg select <id>`                                            | Load a region's bounds back into your selection, ready to reshape           |
| `/wg remove <id>`                                            | Delete a region                                                             |
| `/wg list [page]`                                            | List regions in this world                                                  |
| `/wg here`                                                   | What region am I standing in?                                               |
| `/wg info <id>`                                              | Show a region's owners, members, flags, priority                            |
| `/wg flag <id> <flag> [value]`                               | Set a flag (leave value blank to clear it, `-g` to limit who it applies to) |
| `/wg priority <id> <priority>`                               | Set which region wins when they overlap                                     |
| `/wg priority <a>><b>`                                       | Order regions instead of guessing numbers — `shop>spawn` means shop wins    |
| `/wg priority`                                               | Same thing as a dialog: pick a region, above or below, and what to beat     |
| `/wg parent <id> [parent]`                                   | Inherit flags from another region — name no parent to stop inheriting       |
| `/wg owner <add\|remove> <id> <player>`                      | Manage owners                                                               |
| `/wg member <add\|remove> <id> <player>`                     | Manage members                                                              |
| `/wg menu [id]`                                              | Open the region browser, or one region's flag editor                        |
| `/wg settings`                                               | Edit messages and cooldowns in-game                                         |
| `/wg bypass`                                                 | Toggle your own bypass                                                      |
| `/wg reload`                                                 | Reload config and messages                                                  |

The spellings this replaced still work and always will, they are just kept out of the help so the list stays short:
`define-cylinder`, `define-sphere`, `define-polygon` and their `redefine-` twins,
`addowner`, `removeowner`, `addmember`, `removemember`, `setparent` and `removeparent`. If you have those in a command
block or a wiki page, nothing needs changing.

### Permissions

| Node                           | Grants                                            |
|--------------------------------|---------------------------------------------------|
| `uworldguard.region.define`    | `define`, in every shape                          |
| `uworldguard.region.redefine`  | `redefine`, in every shape                        |
| `uworldguard.region.remove`    | `remove`                                          |
| `uworldguard.region.select`    | `select`                                          |
| `uworldguard.region.list`      | `list`                                            |
| `uworldguard.region.info`      | `info`, `here`                                    |
| `uworldguard.region.flag`      | `flag`                                            |
| `uworldguard.region.priority`  | `priority`                                        |
| `uworldguard.region.setparent` | `parent`                                          |
| `uworldguard.region.members`   | Owner and member commands                         |
| `uworldguard.menu`             | `menu`                                            |
| `uworldguard.settings`         | `settings`                                        |
| `uworldguard.reload`           | `reload`                                          |
| `uworldguard.notify`           | See `notify-enter` / `notify-leave` announcements |
| `uworldguard.bypass`           | Ignore region protection — **default: off**       |

Every node except `uworldguard.bypass` defaults to operators. Commands you lack permission for
don't appear in tab-completion.

### Flags

Set with `/wg flag <region> <flag> <value>`, or through `/wg menu <region>` if you'd rather click.
Clear a flag by leaving the value off.

Value types you'll see:

| Type    | Accepts                                       | Example                                                  |
|---------|-----------------------------------------------|----------------------------------------------------------|
| State   | `allow` / `deny`                              | `/wg flag spawn pvp deny`                                |
| Boolean | `true` / `false`                              | `/wg flag spawn fly true`                                |
| Number  | a number                                      | `/wg flag spawn walk-speed 0.3`                          |
| Text    | any string (MiniMessage where it's a message) | `/wg flag spawn greeting "<green>Welcome!"`              |
| List    | comma-separated                               | `/wg flag spawn deny-item-drops DIAMOND,NETHERITE_INGOT` |

State flags also take a **group** with `-g`, limiting who the rule applies to:

```
/wg flag spawn pvp deny -g nonmembers
```

Groups: `all` · `members` · `owners` · `nonmembers` · `nonowners` · `none`.
WorldGuard's spellings work too, so `non-members` and `non_members` are both accepted.

<details>
<summary><b>Protection</b> — who can touch what (41 flags)</summary>

`build` · `block-break` · `block-place` · `interact` · `use` · `chest-access` · `pvp` ·
`damage-animals` · `fall-damage` · `ride` · `sleep` · `tnt` · `lighter` · `end-crystal-place` ·
`end-crystal-interact` · `worldedit` · `pistons` · `passthrough` · `entity-item-frame-destroy` ·
`entity-painting-destroy` · `entity-armor-stand-destroy` · `vehicle-place` · `vehicle-destroy` · `potion-splash` ·
`firework-damage` · `use-anvil` · `respawn-anchors` · `use-dripleaf` · `sign-edit` · `tnt-prime` ·
`armor-stand-manipulate` · `mannequin-manipulate` · `vault-use` · `bucket-entity` ·
`bucket-fill` · `bucket-empty` · `shear` · `leash` · `name-entity` · `flower-pot` · `lectern`

`sign-edit` matters because signs are editable after placement since 1.20. `vault-use` and
`mannequin-manipulate` cover the trial-chamber vault (1.21) and the mannequin (26.x).

`bucket-fill` and `bucket-empty` govern scooping and pouring on their own. Set either one and it decides; leave both
unset and buckets fall back to `block-break` / `block-place` and the material lists, so configs written before these
flags existed are unaffected.

`passthrough` is the odd one: it makes a region *not* apply protection, letting lower-priority
regions decide instead.
</details>

<details>
<summary><b>Entry &amp; exit</b> — who gets in, and what happens when they do (15 flags)</summary>

`entry` · `exit` · `entry-min-level` · `entry-max-level` · `player-count-limit` ·
`teleport-on-entry` · `teleport-on-exit` · `command-on-entry` · `command-on-exit` ·
`console-command-on-entry` · `console-command-on-exit` · `respawn-location` · `join-location` ·
`exit-override` · `exit-via-teleport`

`exit-via-teleport` closes the obvious hole in `exit`: without it, any `/tp`, home or warp walks
straight out of a region you can't walk out of. `exit-override` is the escape hatch that lets
someone leave anyway, so a mis-set `exit` doesn't trap people.

`player-count-limit` caps how many players may be inside at once. Everyone standing in the region
counts towards the number, but members, owners and anyone with bypass are never turned away — so a
region full of its own members is closed to outsiders while its members still come and go. It is
checked when someone crosses in, on foot or riding; nobody already inside is ever pushed out by it.

`command-on-*` runs as the player; `console-command-on-*` runs as the server.
`entry-min-level` / `entry-max-level` accept a number, or a PlaceholderAPI `%placeholder%` if you
have PAPI installed — useful for gating on a level from another plugin.
</details>

<details>
<summary><b>Mobs &amp; explosions</b> (21 flags)</summary>

`mob-spawning` · `deny-spawn` · `mob-damage` · `creeper-explosion` · `other-explosion` ·
`enderman-grief` · `ghast-fireball` · `wither-damage` · `enderdragon-block-damage` ·
`ravager-grief` · `snowman-trails` · `breeze-charge-explosion` · `lightning` · `mob-drops` ·
`exp-drops` · `raid` · `entity-transform` · `breed` · `tame` · `door-break` · `copper-golem`

`raid` stops raids starting — usually the one flag a spawn town wants. `copper-golem` gates
building one (26.x); player-built golems are outside `mob-spawning` by design.

`mob-spawning` is a blanket toggle over *natural* spawns. `deny-spawn` is a list of entity types
that may never appear, whatever put them there — spawn egg, spawner, or another plugin:
`/wg flag spawn deny-spawn CREEPER,minecraft:phantom`.
</details>

<details>
<summary><b>Environment</b> — fire, fluids, growth, decay (26 flags)</summary>

`fire-spread` · `lava-fire` · `lava-flow` · `water-flow` · `snow-fall` · `snow-melt` · `ice-form` ·
`ice-melt` · `leaf-decay` · `crop-growth` · `vine-growth` · `crop-trample` · `frostwalker` ·
`frosted-ice-melt` · `grass-growth` · `mycelium-spread` · `mushroom-growth` · `sculk-growth` ·
`rock-growth` · `coral-fade` · `copper-fade` · `moisture-change` · `soil-dry` · `tree-growth` ·
`sponge-absorb` · `chunk-unload`

`tree-growth` is the one growth that reaches well past the block it started on — a sapling planted
just outside can otherwise push its canopy straight through the border.

`moisture-change` freezes farmland hydration in both directions; `soil-dry` stops only the drying
half, which is the one that reverts farmland to dirt and breaks the crop above it.
</details>

<details>
<summary><b>Movement</b> (6 flags)</summary>

`enderpearl` · `chorus-fruit-teleport` · `glide` · `nether-portals` · `chambered-enderpearl` ·
`portal-create`

`nether-portals` governs travelling through a portal; `portal-create` governs lighting a new one.
</details>

<details>
<summary><b>Messages &amp; sound</b> (14 flags)</summary>

`greeting` · `farewell` · `greeting-title` · `farewell-title` · `chat-prefix` · `chat-suffix` ·
`deny-message` · `entry-deny-message` · `exit-deny-message` · `play-sounds` · `notify-enter` ·
`notify-leave` · `send-chat` · `receive-chat`

`notify-enter` / `notify-leave` announce crossings to staff holding `uworldguard.notify`. The flag
decides which regions announce; the wording lives in `messages.yml` under the same two names, where
`<player>` and `<region>` are filled in — reword it, recolour it, or set it to `false` to silence
the announcements without unsetting the flags.
`send-chat` stops people talking inside a region; `receive-chat` stops them hearing it.

`deny-message` replaces the configured refusal text for build and interact denials inside the
region; `%what%` in it expands to what was refused. `greeting-title` / `farewell-title` split on a
literal `
` into title and subtitle.

All message flags are [MiniMessage](https://docs.advntr.dev/minimessage/) — `<red>`, `<bold>`,
gradients, the lot. `chat-prefix` / `chat-suffix` wrap what a player says while they're inside.
</details>

<details>
<summary><b>Player state</b> — applied continuously while inside (24 flags)</summary>

`invincible` · `godmode` · `heal-amount` · `heal-min-health` · `heal-max-health` · `game-mode` ·
`give-effects` · `blocked-effects` · `hide-players` · `walk-speed` · `fly-speed` · `fly` ·
`keep-inventory` · `keep-exp` · `disable-collision` · `natural-health-regen` ·
`natural-hunger-drain` · `heal-delay` · `feed-delay` · `feed-amount` · `min-food` · `max-food` ·
`time-lock` · `weather-lock`

`time-lock` takes `day`/`night`/`noon`/`midnight` or a raw tick count; `weather-lock` takes `clear`
or `downfall`. Both are client-side, so one player in an eternal-night arena doesn't change the sky
for everyone else.
</details>

<details>
<summary><b>Items &amp; commands</b> (23 flags)</summary>

`disable-completely` · `disable-throw` · `wind-charge` · `villager-trade` · `permit-workbenches` ·
`inventory-craft` · `item-drop` · `item-pickup` · `deny-item-drops` · `deny-item-pickup` ·
`item-durability` · `allow-block-place` · `deny-block-place` · `allow-block-break` ·
`deny-block-break` · `blocked-cmds` · `allowed-cmds` · `crafter` · `hopper-transfer` ·
`dispense` · `enchant` · `brew` · `smelt`

`hopper-transfer` is judged at the destination, so it stops a hopper chain reaching under the
border to drain a region's chests. `crafter` covers the 1.21 auto-crafting block.

`item-drop` / `item-pickup` are blanket allow/deny; `deny-item-drops` / `deny-item-pickup` name
specific materials instead.

The `allow-*` / `deny-*` block lists are the fine-grained escape hatch: `deny-block-break` blocks
those materials **even for members**, and `allow-block-break` permits them **even for outsiders**.
Handy for a survival spawn where anyone may harvest crops but nobody may break stone.

`blocked-cmds` is a deny-list. `allowed-cmds`, if you set it, is exclusive — anything not on the
list is refused.
</details>

### Configuration

`config.yml` ships with comments explaining every option. The four that matter:

**Storage.** YAML by default — one file per world, no setup. Switch to SQL if you'd rather:

```yaml
storage:
    type: sql
    auto-save-minutes: 5
    sql:
        enabled: true
        url: "jdbc:sqlite:plugins/uWorldGuard/regions.db"
```

**Movement detection.** This is the setting to reach for if your server is struggling.
`PlayerMoveEvent` fires many times per second per player, and it's the most expensive thing any
region plugin does.

```yaml
movement:
    mode: EVENT          # or TASK
    task-interval-ticks: 4
```

| Mode              | Cost scales with          | Trade-off                                                                                                                         |
|-------------------|---------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `EVENT` (default) | how much players move     | Exact — a denied entry is cancelled before the player moves                                                                       |
| `TASK`            | player count and interval | Cheaper and predictable, but a denied crossing rubber-bands the player back afterwards, and effects can lag by up to one interval |

`TASK` also sweeps roughly once a second for anyone standing inside a region that refuses them —
which catches logging in inside a no-entry region, or a region being created around someone.
Neither is a crossing, so neither mode would otherwise notice.

**Turning owners and members off.** If your server decides everything with flags and never uses the trust lists, stop
consulting them:

```yaml
regions:
    membership-grants-trust: false
```

Read that literally before setting it. Owners and members stop being trusted *anywhere*, so a region with no `build`
flag denies every player including its own owner, and the only ways left to build there are a flag that allows it or
`/wg bypass`. The lists are still stored and still editable, so setting it back to `true` restores exactly what was
there. The plugin warns at every startup while it is off.

**Per-world event skipping.** If another plugin owns interactions in a creative or minigame world,
tell uWorldGuard to stay out of the way there:

```yaml
worlds:
    creative:
        events:
            whitelist-mode: false     # false = skip the listed events
            disabled:
                - BlockBreakEvent
                - BlockPlaceEvent
```

Events are named by their Bukkit class. Apply changes with `/wg reload`.

**Interaction whitelist.** `interact` and `use` are all-or-nothing: deny them and every right-click in the region goes
with them. List the blocks that should stay usable anyway:

```yaml
interaction:
    whitelist:
        - NOTE_BLOCK
        - ANVIL
        - ENCHANTING_TABLE
```

Anything left off the list keeps behaving as it did — deny `interact` with the list above and trapdoors are protected
while note blocks, anvils and enchanting tables still work. Only the blanket check is skipped: flags naming a specific
block (`chest-access`, `use-anvil`, `permit-workbenches`,
`lectern`, `sign-edit`) still apply, so whitelisting a note block doesn't open every chest.

A single world can allow more than the global list does:

```yaml
worlds:
    creative:
        interaction:
            whitelist:
                - LEVER
```

The two add up — a block is exempt if either list names it. There's no way to take a globally whitelisted block back for
one world, so if only some worlds should allow it, put it in their lists rather than the global one. Apply changes with
`/wg reload`.

### Messages

`messages.yml` controls what players are told. Every entry is MiniMessage, and every entry can be
turned off by setting it to `false` or `""`.

```yaml
cooldown-seconds: 3

messages:
    no-permission: "<red>You don't have permission to do that here."
    entry-denied: "<red>You cannot enter this area."
    exit-denied: "<red>You cannot leave this area."
```

`no-permission` is the shared "you can't do that" message. To word it differently for one specific
flag, add `no-permission-<flag>`:

```yaml
messages:
    no-permission: "<red>You don't have permission to do that here."
    no-permission-chest-access: "<red>This container is locked."
    no-permission-block-break: false          # silence just this one
```

The two levels disable independently — a per-flag `false` silences only that flag, while disabling
`no-permission` silences everything that has no override of its own. `cooldown-seconds` stops the
same message repeating at a player who's spam-clicking.

Flags that can send a denial: `block-break`, `block-place`, `interact`, `chest-access`,
`end-crystal-place`, `end-crystal-interact`, `villager-trade`, `permit-workbenches`,
`inventory-craft`, `disable-completely`, `disable-throw`, `deny-item-drops`, `blocked-cmds`.

> Editing a message through `/wg settings` rewrites `messages.yml` and **drops the comments** in it.
> Your values are safe; the explanatory comments aren't.

### Optional integrations

All optional — install them or don't, nothing breaks either way.

| Plugin             | What it adds                                                                                                                           |
|--------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| **WorldEdit**      | Use WorldEdit selections instead of the built-in wand; required for `define-polygon`; activates the WorldGuard API compatibility layer |
| **PlaceholderAPI** | `%placeholders%` in message flags and in `entry-min-level` / `entry-max-level`                                                         |
| **PacketEvents**   | Makes `disable-collision` work for players another plugin has put on a per-player scoreboard                                           |

**What PacketEvents changes.** One thing, and only for a niche case. uWorldGuard's no-collision team lives on the *main*
scoreboard, which is what the server itself reads — so collision is correctly disabled server-side for everyone, with or
without PacketEvents. But collision is also predicted on every client, and a player another plugin has moved to a
per-player scoreboard never receives that team. With PacketEvents, the team is sent to those clients directly.

It goes to every such client, not just the affected player: each client predicts collision for *its own* player, so a
bystander who does not know you are uncollidable keeps pushing themselves off you and rubber-bands against a server that
disagrees.

Known limitation: if another plugin moves a player onto a per-player scoreboard *while* they are already inside a
`disable-collision` region, their client is not re-sent the team until they leave and re-enter — there is no event to
notice the switch.

### WorldGuard compatibility

uWorldGuard can stand in for WorldGuard: plugins built against the WorldGuard 7 API (mcMMO, shop plugins, quest
plugins, …) link against a bundled compatibility layer that answers with uWorldGuard's regions and flags.

- **Requirements:** WorldEdit (or FAWE) must be installed — the WorldGuard API is built on WorldEdit types. Without it
  the layer stays off and uWorldGuard runs normally.
- **Remove `WorldGuard.jar`.** uWorldGuard *provides* the plugin name "WorldGuard"; the real plugin and the
  compatibility layer cannot coexist. uWorldGuard detects the conflict and keeps the layer off with a loud log message.
- Plugins that `depend`/`softdepend` on WorldGuard load and order correctly;
  `getPlugin("WorldGuard")` resolves to uWorldGuard.
- **Version checks:** a plugin that gates its WorldGuard hook on a version string starting with `7` would decline to
  hook against a 1.x plugin, even though the API is present, and Paper attaches no version to a *provided* plugin name.
  So uWorldGuard publishes the WorldGuard API level it implements (`7.0.18`) in place of its own version. It is one
  field per plugin, so it is all-or-nothing: `/version` reports it too. uWorldGuard's own log lines, its update check
  and bStats keep using the real version. PvPManager and BetonQuest are two that need this; set
  `compatibility.report-version` to `none` in `config.yml` to publish the real version instead, or to a version of your
  own. A few plugins read the version out of `plugin.yml` in the jar rather than from the plugin metadata, which a
  `paper-plugin.yml` plugin does not ship; uWorldGuard answers that read with a WorldGuard descriptor carrying the same
  published version. MythicMobs is one, and without it logs only `Failed to enable support for WorldGuard`.
- **Flags another plugin registers under a name uWorldGuard already uses** are accepted rather than rejected.
  uWorldGuard implements every WorldGuardExtraFlags flag natively, so on a strict registry that plugin cannot register a
  single one of its 25 flags and takes itself down at boot. The registration is a no-op instead: uWorldGuard keeps
  handling the flag, and the registrant's own handlers read nothing and do nothing. Two *plugins* claiming one new name
  still conflict, as on WorldGuard.
- **`DisallowedPVPEvent`** is fired when the `pvp` flag is about to deny an attack, and cancelling it lets the attack
  through, so a combat plugin can put its own rules above the flag. PvPManager registers a listener for it and refuses
  its whole WorldGuard hook when the class is absent. uWorldGuard also fires it for fire-aspect and flame-bow ignition,
  which it blocks in a `pvp: deny` region and WorldGuard does not, so an override reaches that too rather than applying
  to half the attack.
- **Session handlers** registered through `SessionManager.registerHandler` are driven by uWorldGuard's own movement
  tracker: `testMoveTo` and `onCrossBoundary` fire on every region crossing — walking, swimming, gliding, riding,
  mounting, teleporting and respawning each report their own `MoveType` — with `tick` once a second and `initialize`/
  `uninitialize` on join and quit. One deviation: the tick is once a second rather than every server tick.
- **Group flags** (`group.<name>` domains) resolve from the `group.<name>` permission node, which is what Vault-backed
  permissions plugins publish. Offline players never match.
- `/uwg compat` shows whether the layer is active, what API surface plugins have used, and which calls hit unimplemented
  stubs — check it first when a plugin misbehaves.
- This is **not** EngineHub's WorldGuard. Do not report issues with it to EngineHub — report them to uWorldGuard.

---

## For developers

### Getting the API

The API module is published to your local Maven repo:

```bash
./gradlew :api:publishToMavenLocal
```

```kotlin
repositories { mavenLocal() }

dependencies {
    compileOnly("com.tricrotism:uworldguard-api:1.1.2")
}
```

`compileOnly` is correct — the API classes ship **inside** the uWorldGuard jar and are loaded from
its classloader at runtime. Don't shade them.

Declare the dependency so load order is guaranteed:

```yaml
# paper-plugin.yml
dependencies:
    server:
        uWorldGuard:
            load: BEFORE
            required: true
            join-classpath: true
```

### Querying regions

```java
RegionQuery query = UWorldGuardApi.createQuery();

// Can this player build here?
boolean canBuild = query.testBuild(location, player);

// Is a state flag allowed here? (respects group qualifiers when you pass the player)
boolean pvp = query.testState(location, Flags.PVP, player);

// Read a typed flag value — null when unset
String greeting = query.queryValue(location, Flags.GREETING);
```

`UWorldGuardApi.isAvailable()` tells you whether the plugin is enabled; `regionContainer()` and
`createQuery()` throw `IllegalStateException` if it isn't. The container is also registered with
Bukkit's services manager if you prefer that lookup:

```java
RegionContainer container = getServer().getServicesManager().load(RegionContainer.class);
```

For repeated checks at one spot, resolve the set once instead of calling `query` per flag:

```java
ApplicableRegionSet set = query.getApplicableRegions(block);
if(!set.

testState(Flags.BLOCK_BREAK, player.getUniqueId())){
        // denied
        }
```

`RegionQuery` overloads accept `Location`, `Block`, `Entity`, or raw `(World, x, y, z)` — prefer the
raw form in hot paths to skip constructing a `Location`.

### Editing regions

Edit through a `RegionEditor`, not by calling setters on regions directly:

```java
RegionEditor editor = UWorldGuardApi.editor(world); // null while the world's regions are loading
if(editor ==null)return;

ProtectedRegion claim = new ProtectedCuboidRegion("claim-" + player.getName(), min, max);
switch(editor.

create(claim, player)){
        case APPLIED ->editor.

addPlayer(claim, RegionMembershipChangeEvent.Role.OWNER, player.getUniqueId(),player);
        case ALREADY_EXISTS ->player.

sendMessage("You already have a claim.");
    case CANCELLED ->{} // another plugin refused, and has already told the player why
default ->{}
        }

        editor.

setFlag(claim, Flags.PVP, State.DENY, player);
editor.

setFlag(claim, Flags.CHEST_ACCESS, State.DENY, RegionGroup.NON_MEMBERS, player); // value and group as one edit
```

The editor is what uWorldGuard's own commands and menus use, so an edit from your plugin behaves exactly like one an
operator made:

- It fires the matching edit event, so other plugins can veto your edits the same way they veto an operator's.
- It saves. A direct `region.setFlag(...)` is never written to disk unless you also call `markDirty()`, and until you
  do, checks that skip worlds where no region uses a flag can skip yours.
- It skips edits that change nothing, without firing anything.
- It returns an `EditResult` instead of throwing: `APPLIED`, `UNCHANGED`, `CANCELLED`, `NOT_FOUND`, `ALREADY_EXISTS` or
  `INVALID` (a bad id, a parent cycle, or a shape change aimed at the global region).

Pass the player the edit is for as the actor, and they are told when another plugin refuses it. Pass `null` for an edit
your plugin makes on its own behalf. Several priorities can be set as one edit with `setPriorities`, so a reorder is
applied completely or not at all.

### Finding overlapping regions

Before creating a claim, ask what it would touch:

```java
List<ProtectedRegion> overlapping = manager.getRegionsIntersecting(min, max);
```

Corners can come in either order, the global region is never included, and results are highest priority first. It
compares bounding boxes, so for cylinders, spheres and polygons a hit means *may* overlap. It walks every region in the
world, so call it when a claim is made, not on movement.

### Respecting bypass

```java
if(UWorldGuardApi.hasBypass(player)){
        return; // staff with /uwg bypass on: let them through, as uWorldGuard does
        }
```

True when the player has bypass switched on *and* still holds `uworldguard.bypass`. If your plugin enforces protection
of its own, checking this keeps staff from being waved through by uWorldGuard and stopped by you.

### Knowing when regions are ready

A world loaded into a running server has its regions read in the background, and until that finishes
`RegionContainer.get(world)` returns `null`, which looks exactly like a world with no regions. Build per-world caches on
`RegionsLoadedEvent` instead of `WorldLoadEvent`, and drop them on `RegionsUnloadedEvent`:

```java

@EventHandler
public void onRegionsLoaded(RegionsLoadedEvent event) {
    cache.put(event.getWorld().getUID(), buildIndex(event.getManager()));
}

@EventHandler
public void onRegionsUnloaded(RegionsUnloadedEvent event) {
    cache.remove(event.getWorld().getUID());
}
```

Worlds present at startup load while uWorldGuard enables, before your plugin does, so read those in your own
`onEnable`. For worlds loaded later, `RegionsLoadedEvent` is asynchronous.

### Region enter and exit events

Rather than diffing region sets on `PlayerMoveEvent` yourself, listen for the crossing:

```java

@EventHandler
public void onEnter(RegionEnterEvent event) {
    Player player = event.getPlayer();
    ProtectedRegion region = event.getRegion();
}
```

`RegionExitEvent` is the other direction, and both extend `RegionBoundaryEvent` if you want to catch them together. They
fire once per region actually crossed, so walking around inside a region produces nothing, and stepping into three
overlapping regions at once produces three events.

They are not cancellable. The crossing has already been allowed by the time they fire, because the
`entry` and `exit` flags decide that first. Turn a player back by teleporting them.

One threading note: these fire on the region thread that owns the destination, which under Folia is not a single shared
thread. The player and the region are safe to touch there; anything else needs a hop to its own owner.

### Region edit events

Every deliberate edit to a region fires a cancellable event before it is applied, so a plugin can veto one without
intercepting commands or reaching in with reflection:

| Event                         | Fired before                                   | Extra                                     |
|-------------------------------|------------------------------------------------|-------------------------------------------|
| `RegionCreateEvent`           | a region is added                              | the region, not yet in the world          |
| `RegionRedefineEvent`         | a region is reshaped                           | `getReplacement()`, the new shape         |
| `RegionRemoveEvent`           | a region is removed                            | last chance to read it                    |
| `RegionFlagChangeEvent`       | a flag is set, cleared, or narrowed to a group | `getFlag()`, old/new value, old/new group |
| `RegionPriorityChangeEvent`   | a priority changes                             | `getOldPriority()`, `getNewPriority()`    |
| `RegionParentChangeEvent`     | a parent is set, changed or cleared            | `getOldParent()`, `getNewParent()`        |
| `RegionMembershipChangeEvent` | an owner or member is added or removed         | `getRole()`, `getPlayer()`, `isAdding()`  |

```java

@EventHandler
public void onFlag(RegionFlagChangeEvent event) {
    if (event.getFlag() == Flags.PVP && !event.getActor().hasPermission("mine.pvp")) {
        event.setCancelMessage(Component.text("PvP is set by staff here."));
        event.setCancelled(true);
    }
}
```

All of them extend `RegionChangeEvent`, which carries the world, the region and the actor. They fire for edits made
through commands, through `/uwg menu`, and through any plugin using a `RegionEditor`, so a veto cannot be sidestepped by
clicking instead of typing. `getActor()` is null for an edit a plugin makes on its own behalf. Setting a cancel message
replaces the generic refusal shown to the actor.

A reorder (`/uwg priority shop>spawn`, the priority dialog, or `setPriorities`) fires one `RegionPriorityChangeEvent`
per region that moves, all before any is applied, and cancelling any one cancels the whole reorder.

These report edits, not history. Loading a world does not fire them, and neither does a WorldGuard import or a direct
`RegionManager` / `ProtectedRegion` setter, which is one more reason to edit through a `RegionEditor`.

Each event fires on the thread doing the edit and `isAsynchronous()` says which. Adding an owner or member by name is
usually async, because resolving the name reads player data off disk. Check before touching the Bukkit API from a
listener.

### Registering your own flags

Flags are a registry, so your plugin can add its own and they'll show up in commands, tab-completion
and the GUI like any built-in:

```java
public static final StateFlag MY_FLAG =
        Flags.register(FlagCategory.PROTECTION, new StateFlag("my-flag", true));
```

Register during your plugin's load or enable. Registering after regions have loaded is fine: stored values for a flag
nobody has registered yet are kept exactly as written and saved unchanged, and they take effect the moment the flag
registers. Registering a name that's already taken throws
`IllegalStateException`.

A flag belongs to the plugin that registered it. When that plugin disables, its flags are released:
their region values go back to being kept as written, and the name is free to register again. That is what makes
hot-swapping a plugin work, with a tool such as [Cork](https://github.com/xyzeva/cork)
or anything else that disables a plugin before loading its replacement. The reloaded copy registers the same names and
every region gets its values back. Plugins using the WorldGuard API get the same treatment, and their session handlers
are unregistered along with their flags, so a reload never leaves two copies of a handler running.

Flag types available: `StateFlag` (allow/deny + group), `BooleanFlag`, `IntegerFlag`, `DoubleFlag`,
`StringFlag`, `StringSetFlag`, `MaterialSetFlag`, `PotionEffectSetFlag`. Subclass `Flag<T>` for
anything else — you implement `parse`, `marshal` and `unmarshal`.

Each registered flag gets a dense `getIndex()`, stable for the JVM's lifetime, so you can key a bitset on it rather than
hashing. A flag re-registered after its plugin reloads gets its old index back.

### Region types

`ProtectedCuboidRegion`, `ProtectedCylinderRegion`, `ProtectedSphereRegion`,
`ProtectedPolygonRegion`, and `GlobalProtectedRegion` (a whole world, priority-wise below
everything). All extend `ProtectedRegion`. Get a world's `RegionManager` from
`RegionContainer.get(world)` — it returns `null` if that world's regions aren't loaded, so check.

### Threading

**This plugin is Folia-compatible, which constrains how you call it.**

- Region queries are thread-safe and allocation-light; call them from any thread.
- Anything you *do* with the answer — moving a player, changing a block, opening an inventory —
  belongs on the region thread that owns that location, via `RegionScheduler` or the entity's own
  `EntityScheduler`.
- There is no single main thread. `Bukkit.getScheduler()` and `BukkitRunnable` are broken under
  Folia; don't reach for them.

### Building

```bash
./gradlew shadowJar
```

Output: `plugin/build/libs/uWorldGuard-<version>.jar`. Requires JDK 25.

```bash
./gradlew runServer     # test server on the target Minecraft version
```

The project is two modules: `api/` (public, no NMS, published as `uworldguard-api`) and `plugin/`
(implementation). Cloud, InvUI, Caffeine and the SQLite driver are **not** shaded — they're
downloaded at boot by `UWorldGuardLoader`. Only bStats is shaded, relocated out of the way.

> **InvUI is version-coupled.** It's the one dependency tied to a specific Minecraft release, so a
> given uWorldGuard build targets one Minecraft version. Bumping Minecraft means bumping
> `invui` in `gradle/libs.versions.toml`, the coordinate in `UWorldGuardLoader`, the Paper
> dev bundle, and `api-version` in `paper-plugin.yml` together.

---

## License

See [LICENSE.md](LICENSE.md).

**WorldGuard API compatibility layer:** uWorldGuard ships a WorldGuard API compatibility layer:
the `com.sk89q.worldguard.*` classes bundled in the jar are an independent, clean-room reimplementation of WorldGuard
7's public API, provided solely for interoperability with plugins built against that API. This module is licensed
**LGPL-3.0-or-later**; its source lives in
[`wg-compat/`](wg-compat/) of the uWorldGuard repository, and the license texts ship inside the jar at
`META-INF/licenses/wg-compat/`. The remainder of uWorldGuard keeps its existing license. WorldGuard is a project of the
EngineHub team; uWorldGuard is not affiliated with or endorsed by EngineHub or sk89q, and "WorldGuard" is used only to
describe compatibility. uWorldGuard reports its own version (1.x), not a WorldGuard 7.x version.
