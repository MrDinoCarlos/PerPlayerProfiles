# PerPlayerProfiles

PerPlayerProfiles 1.0.0 lets one Minecraft account use multiple independent server profiles on Bukkit, Spigot and Paper. Separate exports cover Minecraft 1.20.X, 1.21.X, 26.1.X, 26.2.X and 26.3.X.

Each profile can have its own inventory, experience, location, statistics, play-time limits, PIN, visual icon, respawn point, and configuration. It is designed for servers where one account may be shared by different people, used for different characters, or controlled with time-limited access.

## Main Features

- Multiple profiles per Minecraft account.
- Works with premium and non-premium/offline-mode accounts.
- One master profile per account.
- The master profile can be renamed without losing master permissions.
- The master profile always appears first in profile lists.
- New profiles start completely clean.
- Independent saved state per profile.
- Visual GUI-based profile selector.
- Short command alias: `/ppp`.
- Active profile names can be used as player targets in configured commands such as `/tp`, `/msg`, `/give`, and `/gamemode`.
- Profile switches are logged as profile changes instead of fake disconnect/connect events.
- Multi-language support.
- Visual numeric PIN pad with custom heads.
- Configurable profile icons using custom heads.
- Per-profile play-time limits.
- Automatic play-time renewal.
- Automatic server kick when time reaches 0.
- Visual PIN error feedback.
- Compatibility with GUI login/security plugins such as ServerPassword.
- Safe config updates without overwriting user settings.
- Native custom screens on Paper 1.21.7+ and compatible Java clients, enabled by default.
- Character cards with profile type, protection, lifetime play time, level, deaths, kills, remaining time and renewal.
- Paginated profiles and head catalog, with search and head import directly from the menu.
- PBKDF2-SHA256 credentials matching the ServerPassword format, with automatic upgrades after successful legacy PIN verification.
- Automatic translation updates that preserve customized messages and empty lore lists.
- Background Modrinth checks with optional administrator chat notices and mandatory console results.
- Tab completion for every subcommand, aliases, profile names, menu modes and common time values.

## Compatibility

### Supported Versions

| Export | API baseline | Java | Menu |
| --- | --- | --- | --- |
| `1.20.X` | Spigot 1.20.1 | 17+; server versions 1.20.5+ require 21 | Inventory |
| `1.21.X` | Spigot 1.21, separate Paper 1.21.7 dialog adapter | 21+ | Native on supported Paper, otherwise inventory |
| `26.1.X` | Spigot 26.1 | 25+ | Native on supported Paper, otherwise inventory |
| `26.2.X` | Spigot 26.2 | 25+ | Native on supported Paper, otherwise inventory |
| `26.3.X` | Spigot 26.3 | 25+ | Native on supported Paper, otherwise inventory |

Install only the JAR matching your server range. The 1.21 and 26.x sources are compiled separately because Bukkit API class/interface shapes changed after 1.20. Native screens require the Paper dialog API and a compatible Java client; Spigot uses inventory menus. ViaVersion clients are checked individually, and Geyser players use inventory menus. This plugin uses the standard Bukkit scheduler and does not declare Folia support.

### External Plugins

The plugin declares a soft dependency for:

```yml
ServerPassword
```

If ServerPassword is installed and enabled, PerPlayerProfiles waits until the player has completed the server password authentication before loading profile state or opening the profile selector.

The plugin also waits if another GUI is already open when the player joins. This prevents PerPlayerProfiles from overriding login, authentication, or security menus.

## Installation

1. Build or download the plugin `.jar`.
2. Copy the `.jar` into your server plugins folder:

```text
plugins/
```

3. Restart the server.
4. Edit the generated config file:

```text
plugins/PerPlayerProfiles/config.yml
```

5. Restart again after changing configuration values.

### Jar Variants

Run `gradlew.bat clean exportRelease` with JDKs 17, 21 and 25 installed. The compiled JARs appear in `build/libs/`; ready-to-share files, sources, license and SHA-256 checksums appear in `exports/1.0.0/`:

- `PerPlayerProfiles-1.0.0-1.20.X.jar`
- `PerPlayerProfiles-1.0.0-1.21.X.jar`
- `PerPlayerProfiles-1.0.0-26.1.X.jar`
- `PerPlayerProfiles-1.0.0-26.2.X.jar`
- `PerPlayerProfiles-1.0.0-26.3.X.jar`

## Commands

### Main Command

```text
/profiles
```

Opens the main profile GUI.

### Aliases

```text
/ppp
/pprofile
/perfil
/perfiles
```

### Legacy Subcommands

The plugin is primarily designed around GUI management, but basic command support is also available:

```text
/profiles list
/profiles create <name> [pin]
/profiles use <name> [pin]
/profiles delete <name> [masterPin]
/profiles lock <name> <pin>
/profiles unlock <name> <currentPin>
/profiles setmasterpassword <pin>
/profiles setlimit <profile> <maxMinutes> <renewEveryHours> [masterPin]
/profiles settings [profile]
/profiles icon [profile]
/profiles mode <auto|modern|legacy>
/profiles language [auto|en|es|de|fr]
/profiles admin resetpin <player|uuid> <profile>
/profiles admin removepin <player|uuid> <profile>
/profiles reload
```

`switch`, `remove`, `info`, `icons` and `lang` are also accepted. Press Tab for options and profile names. Authorized administrators also get stored account names and their profiles. PIN arguments never receive suggestions. `/profiles use <name>` opens a private PIN pad when a PIN is needed, so a password does not have to appear in command history. `/profiles reload` requires `perplayerprofiles.admin`, adds missing configuration and translation keys, and reloads editable settings. Restart after changing scheduler intervals.

## Menu Presentation

```yml
gui:
  mode: AUTO
  show-statistics: true
  custom-screens:
    profiles-per-page: 3
    icons-per-page: 3
  custom-heads:
    allow-import: true
  transition:
    enabled: true
    duration-ticks: 12
```

`AUTO` and `MODERN` select native screens whenever supported; `LEGACY` and `INVENTORY` select the inventory interface. Unsupported servers or clients fall back automatically. `/profiles mode` stores an account preference; a server configured for legacy inventory keeps that presentation for everyone.

Native screens cover selection, settings, profile creation and renaming, PIN entry, time limits, icon browsing/search and head import with preview. PIN screens use a compact three-column numeric keypad, masked slots, backspace, clear and error feedback in the same style as ServerPassword. Sensitive actions still require the master PIN when configured. Required selection blocks gameplay until a profile is selected and cannot be bypassed with unrelated commands or a closed menu.

The native selector shows several real 3D player-head items together, with the original skin and hat layer rendered by Minecraft. Each compact row has the profile name, status badge, Select character and Details links. Hover over the head, name or Details to see the full summary. Locked profiles have a red name and `[PIN]`; exhausted profiles have a red name and `[!]`, with selection disabled. Details opens information, statistics, appearance and security.

`gui.custom-screens.profiles-per-page` and `icons-per-page` control when another page is needed (1–3, default 3). Navigation is hidden when all entries fit; multi-page menus show the current page in the title. The icon picker also shows several 3D heads together, with an individual Use icon link for each. These small pages keep the selector compact at ordinary GUI scales.

The plugin sends actual `PLAYER_HEAD` items, rather than drawing faces with font pixels. Minecraft resolves the textures on the client; the plugin no longer downloads or rasterizes skins. No resource pack is required. Native dialog items retain their inventory rendering size: changing the surrounding slot's dimensions does not enlarge the model, as documented in [Minecraft's dialog item format](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-6). Larger item models require a resource pack.

Details include lifetime play time separately from the renewable time budget, creation date and last connection. Old profiles with no recorded creation date show Unknown. Lifetime totals survive renames and budget resets. Native screens show validation and action errors inside the current screen; invalid text stays in the field so it can be corrected. Inventory lore refreshes every second.

Switching characters plays a short transition after the new state is loaded. Continue skips it; opening another profile screen cancels pending animation frames. Disable it with `gui.transition.enabled` or adjust `duration-ticks` (clamped to 4–40). Legacy mode uses a brief title instead.

## Modrinth Updates

```yml
updates:
  notify-admins: true
```

Public versions are checked asynchronously after startup and every six hours against [Per Player Profiles on Modrinth](https://modrinth.com/plugin/ppps). Notices are filtered to the current Minecraft version and server loader. Setting `notify-admins: false` only suppresses the join chat notice for `perplayerprofiles.admin`; console results and connection errors always remain enabled. Notifications wait for ServerPassword authentication. No updates are downloaded or installed.

## Profile System

Each player account can have multiple profiles.

The maximum number of profiles is configured with:

```yml
profiles:
  max-per-account: 3
```

## Master Profile

The first profile created for an account is the master profile.

By default, a new account's master profile uses the real Minecraft player name instead of the literal name `master`.

The master profile:

- Can create profiles.
- Can delete profiles.
- Can configure play-time limits.
- Can change, remove, or reset profile PINs.
- Can configure the master PIN.
- Can edit profile icons and names.
- Can be renamed without losing master permissions.
- Always appears first in the selector.
- Cannot be deleted.
- Is not kicked by play-time limits.

The master profile is internally tracked as the master profile, not just by its name. This means it remains the master even if renamed.

## Normal Profiles

A normal profile:

- Can be selected from the GUI.
- Has its own saved state.
- Can have its own PIN.
- Can change or remove its own PIN.
- Can change its own icon.
- Can change its own name.
- Can have a play-time limit configured by the master profile.

## Saved State Per Profile

Each profile stores its own player state:

- Last location.
- Respawn location.
- Bed spawn location.
- Full inventory.
- Cursor item.
- Armor.
- Offhand and extra inventory slots.
- Ender chest.
- Health.
- Food level.
- Saturation.
- Exhaustion.
- Level.
- Experience.
- Total experience.
- Game mode.
- Allow-flight status.
- Flying status.
- Fire ticks.
- Remaining air.
- Potion effects.
- Basic untyped statistics.
- Typed block, item and entity statistics, including counters absent from the next profile being reset to zero.
- Discovered recipes.
- Advancements.

On the first upgrade from older shared recipe/typed-statistic storage, the player's existing global progress is assigned to their last active profile (or master when unknown). Other profiles begin with empty independent progress. This migration runs once per missing field and does not overwrite progress already saved in the new format.

Loading or switching characters restores advancements and the exact discovered recipe set as a silent client snapshot. It does not repeat advancement/recipe notifications, completion/discovery events, XP, loot or advancement reward functions. Normal notifications, events and rewards still work for progress earned while playing. No world gamerules are changed. Partial advancement criteria and removed datapack entries are handled without mixing characters' progress.

`./gradlew progressProbeJar` builds a separate test plugin in `build/integration/tools`, never bundled in the release JARs. It requires an isolated server bound to `127.0.0.1` with `-Dperplayerprofiles.integration=local-smoke`, uses synthetic players and checks actual server events and outbound packets through repeated switches, an empty character, partial criteria, a saved-YAML reconnect and subsequent genuine unlocks. It shuts down that fixture after the checks; do not install it in a live server.

## Clean New Profiles

Newly created profiles do not copy the active profile state.

A new profile starts with:

- Empty inventory.
- Empty armor.
- Empty offhand.
- Empty ender chest.
- 0 XP.
- Level 0.
- Full health.
- Full hunger.
- No potion effects.
- Basic statistics set to 0.
- Initial location at the world spawn.
- No custom respawn point.

This prevents item duplication between profiles.

## Profile Selector

The selector can automatically open when the player joins:

```yml
profiles:
  open-selector-on-join: true
  require-selection-on-join: true
```

Rules:

- If the account only has one profile, the selector does not open.
- If `require-selection-on-join` is enabled and the account has more than one profile, the last valid profile is loaded first and the selector still opens.
- If the player presses ESC without selecting a profile, the GUI opens again.
- The player must select a profile.
- If a login/security GUI is open, the selector waits.

### Waiting For Login Plugins

```yml
profiles:
  selector-auth-wait-seconds: 60

compatibility:
  server-password:
    wait-for-auth: true
```

This prevents PerPlayerProfiles from opening its menu before the player finishes server authentication.

## PIN System

Profiles can be protected with numeric PINs.

PIN length is configured with:

```yml
security:
  pin:
    min-length: 4
    max-length: 9
```

Rules:

- Numbers only.
- Minimum length is clamped between 4 and 9.
- Maximum length is clamped between 4 and 9.
- Default minimum: 4.
- Default maximum: 9.

### Profile PIN

Each profile can:

- Set a PIN.
- Change its PIN.
- Remove its PIN.

A normal user must know the current PIN to change or remove it.

### Master PIN

The master PIN is managed from the master profile menu.

The master profile can:

- Change the master PIN.
- Use the master PIN for sensitive actions.
- Change other profile PINs.
- Remove other profile PINs.
- Reset a profile PIN so the user can set a new one.

### Administrator Recovery

Players with `perplayerprofiles.admin` and the server console can recover another account's profile, including while its owner is offline:

```text
/profiles admin resetpin <player|uuid> <profile>
/profiles admin removepin <player|uuid> <profile>
```

`resetpin` deletes the old profile hash and persists a requirement for its owner to choose a new PIN before entering. If the owner is currently playing that profile, gameplay is held by required selection until they configure the PIN or select another character. `removepin` removes profile PIN protection without requiring a replacement. Neither action changes the separate account master PIN or ServerPassword credentials. Recovery invalidates open PIN forms, clears the failed-attempt cooldown and records the administrator, account UUID, profile and action without logging PINs or hashes. Names resolve only to stored accounts; UUIDs support older records without a known player name.

### Creating Profiles And Master PIN

By default, creating a new profile does not require the master PIN:

```yml
profiles:
  require-master-pin-to-create: false
```

If set to `true`, creating profiles requires the master PIN when one exists.

## PIN GUI

PINs are entered through a visual numeric GUI.

The PIN GUI includes:

- Number buttons with custom heads.
- Length display.
- Backspace button.
- Cancel button.
- Confirm button.
- Visual error feedback.

### Visual PIN Errors

If the PIN is wrong or invalid, the display turns red and the native screen shows the error directly above the keypad, with immediate retry. Legacy mode also uses chat and a title. Attempt cooldowns are shared by both presentations and commands.

## Profile Icons

Each profile can have a visual icon.

Profile icons are configured with:

```yml
gui:
  profile-icons:
    default: baby_turtle
    icons:
      baby_turtle:
        name: 'Baby Turtle'
        texture: '...'
```

Default profile icons:

- Baby Turtle
- Baby Mule
- Baby Goat
- Baby Squid
- Baby Pig
- Baby Sheep
- Baby Cow
- Baby Fox

Add catalog entries in config, or open the icon menu to search all configured entries. Both menus paginate the catalog, including the eighth default head. The import form accepts a `textures.minecraft.net/texture/...` URL, a texture hash, or a Base64 skin texture copied from Minecraft Heads. Native screens show a preview before confirming. Selecting a catalog entry replaces the imported icon. Arbitrary remote URLs and websites are rejected; importing does not scrape external catalogs or require an API key.

## Custom Heads

The plugin uses custom heads for:

- PIN pad numbers.
- Create profile button.
- Delete profile button.
- Lock profile button.
- Unlock profile button.
- Settings button.
- Plus buttons.
- Minus buttons.
- Renewal icon.
- Confirm/save buttons.
- Profile icons.

They can be edited in:

```yml
gui:
  heads:
    create: '...'
    minus: '...'
    renew: '...'
    confirm: '...'
```

## Play-Time Limits

The master profile can configure play-time limits for other profiles.

Each profile can have:

- Maximum play time.
- Renewal interval.
- Used-time counter.
- Countdown until it can be played again.

### Example

A profile can be configured with:

- 60 minutes of play time.
- Renewal every 24 hours.

When the player uses all 60 minutes:

- The player is kicked from the server.
- The kick screen shows the affected profile.
- The kick screen shows when the profile renews.

### Time GUI

The time GUI allows the master profile to:

- Increase or decrease maximum minutes.
- Increase or decrease renewal hours.
- Save the configuration.
- Reset used time.
- View current time settings from the clock icon.

### Time Reset

The master profile can reset:

- Used play time.
- Renewal countdown.

This makes the profile available again according to the configured limit.

## Time Warnings

When a limited profile is close to running out of time, a visual countdown appears:

- From 5 minutes: orange.
- From 1 minute: red.
- From 30 seconds: blinking red.

The countdown uses the ActionBar. Spigot/Paper vanilla cannot draw arbitrary small text in the top-left corner without a resource pack or client-side mod.

## Time Expiration Kick

When the remaining time reaches 0:

- The player is kicked from the server.
- The plugin does not switch the player back to the master profile.
- A clear kick screen is shown:

```text
MAX PLAY TIME REACHED
Profile: <profile>
Renews in: <time>
Come back when this profile is available again.
```

## Languages

Included languages:

- English: `en`
- Spanish: `es`
- German: `de`
- French: `fr`

Language is configured with:

```yml
language: en
```

This is the console language and fallback for unsupported client locales. Each player can use the Language button or `/profiles language` to choose `en`, `es`, `de`, `fr` or `auto`. Auto follows their Minecraft client language and falls back to the server language. The choice is saved per account and applies to messages, native screens and inventory menus without changing other players' language.

Language files are stored separately:

```text
plugins/PerPlayerProfiles/lang/en.yml
plugins/PerPlayerProfiles/lang/es.yml
plugins/PerPlayerProfiles/lang/de.yml
plugins/PerPlayerProfiles/lang/fr.yml
```

## Safe Config Updates

The plugin updates `config.yml` safely.

When a new plugin version adds new config options:

- Missing keys are added.
- Existing user values are preserved.
- User settings are not overwritten.
- `lang/en.yml`, `es.yml`, `de.yml` and `fr.yml` follow the same missing-key merge.
- Custom translations, false/zero values, empty lore lists, unknown keys and user comments are retained.
- Invalid YAML is reported and preserved instead of being replaced by defaults.
- Writes use a temporary file and atomic replacement where supported.

This allows plugin updates without manually rebuilding the whole configuration file.

## Data Files

Profile data is stored under:

```text
plugins/PerPlayerProfiles/accounts/
```

Each account has:

- A main profile metadata file.
- A folder containing individual state files for each profile.

## Security

PINs and the master PIN use one-way PBKDF2-HMAC-SHA256, 600,000 iterations, a random 16-byte salt and a 256-bit derived key, matching ServerPassword's credential format. These are password hashes, not reversible encryption. The plugin stores account metadata and state in YAML files; no SQL database is required.

Old `120000:salt:hash` credentials still work. After a successful check, the verified credential is upgraded and saved without changing the PIN, profile, inventory or statistics. Malformed hashes fail closed; comparisons are constant-time, iteration counts are bounded and temporary password buffers are cleared.

`security.pin.max-attempts` defaults to 5 and `security.pin.cooldown-seconds` to 60. Commands and both menus share the attempt budget; reconnecting does not clear an active cooldown. Restarting the server clears these in-memory cooldowns. Modern actions use random, player-bound, single-use screen tokens in the `perplayerprofiles` namespace, separate from ServerPassword.

## Use Cases

- Family servers where multiple people share one account.
- Shared accounts with controlled access.
- Time-limited profiles for children.
- Different characters inside the same Minecraft account.
- Offline-mode servers where one identity may need several profiles.
- Separating inventory and progress without requiring multiple accounts.


## Important Notes

- New profiles do not copy items from the active profile.
- The initial master profile keeps the player's existing state the first time the account is initialized.
- Profiles created before the clean-profile fix may already contain duplicated saved state if they were saved that way.
- To clean an old duplicated profile, delete its saved state file or recreate the profile.
- The mandatory selector only opens when the account has more than one profile.
- Profile state loading and the selector wait for ServerPassword if it is installed and enabled.

## License

Copyright (C) 2026 MrDinoCarlos. Licensed under **CC-BY-NC-SA-4.0**, matching ServerPassword. See [LICENSE](LICENSE) for the complete legal text and [NOTICE](NOTICE) for attribution. Both files are included in the plugin JARs and exports. Dependencies and third-party materials retain their own licenses.

## Verification

`gradlew.bat build` compiles all five ranges, checks the common sources against Paper 26.3 and runs tests for configuration/translation upgrades, hashes and migration, PIN cooldowns, limits, command completion, native flows and Modrinth behavior.

Profile target integration preserves the server's original command registrations, permissions and parsers. On Paper versions exposing the Brigadier command tree and outgoing suggestion events, profile names are merged into native completions, including namespaced aliases. The Bukkit completion event remains available for other servers. Teleport coordinates, selectors, item suggestions and game modes continue to use the native implementation.

`gradlew.bat commandProbeJar` builds the separate development plugin in `tools/integration` for an isolated loopback server started with `-Dperplayerprofiles.integration=local-smoke`. It checks native completions and aliases before and after `/ppp reload`, native command execution, real Paper suggestion events and client tree isolation, and then stops that test server. It is never included in the runtime JARs. The crash was reproduced on Paper 26.3 build 223. `tools/command-completion-test.cjs` checks profile and native suggestions through a real client connection on the 26.1.2 fixture with the operator account `PppUiV2Bot` and its existing `AlphaHero` profile.

`gradlew.bat dialogTest -PpaperServerJar=<full-Paper-server-JAR>` additionally checks dialog construction against a real Paper implementation. `tools/smoke-test.cjs` exercises both plugins on a local Paper test server using Mineflayer; the fixture must use ServerPassword test PIN `1234` and a fresh account. Set `MINEFLAYER_MODULE` if Mineflayer is installed elsewhere. This script is a development check and is not bundled in runtime JARs.

Set `PPP_TEST_ADMIN=true` with an operator test account to include recovery checks. The smoke test saves dialog packets in `build/reports/profile-ui`; use `PPP_TEST_REPORTS` to choose another directory. It verifies native player-head items, multiple profiles on one page and individual inline actions. `tools/preview-dialogs.ps1 -ClientJar <local-Minecraft-client.jar>` renders layout previews using locally installed client assets and rejects layouts exceeding the reference content height. Its 3D head preview approximates the client's item renderer using the three visible skin faces and overlay. These are previews, not client screenshots; Minecraft assets are never redistributed. The remaining selected ideas are tracked in [ROADMAP.md](ROADMAP.md).
