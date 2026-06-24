# Deathrun Plugin

[![JavaDoc](https://img.shields.io/badge/JavaDoc-latest-blue)](https://flyingfinger1.github.io/deathrun/)
[![Release](https://img.shields.io/github/v/release/flyingfinger1/deathrun)](https://github.com/flyingfinger1/deathrun/releases/latest)
[![License](https://img.shields.io/github/license/flyingfinger1/deathrun)](LICENSE)
[![Paper](https://img.shields.io/badge/Paper-1.20.2%2B-f96854)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://adoptium.net)

A Minecraft Deathrun plugin for **Paper 1.20.2+**. Players race in one cardinal direction — every heart lost is permanent, the corridor is enforced by a moving WorldBorder, and the scoreboard tracks live distances for all participants.

## Concept

All players start inside a glass cage. When the countdown ends, the lime-glass front wall opens and everyone sprints in the configured run direction (e.g. North). Health regeneration is completely disabled — every point of damage is permanent. A per-player WorldBorder follows each runner and deals damage outside the corridor. The player who travels the furthest wins.

## Requirements

- Paper 1.20.2 or higher (see [Version Compatibility](#version-compatibility))
- Java 21
- Maven 3.6+

## Version Compatibility

| JAR | Paper versions | Branch |
|-----|---------------|--------|
| `Deathrun-x.x.x.jar` | 1.20.2 – latest | `main` |
| `Deathrun-x.x.x-legacy.jar` | 1.16.5 – 1.20.1 | `legacy-1.16` |

The legacy JAR is functionally identical with two visual differences: the cage floor uses **Obsidian** instead of Reinforced Deepslate, and scoreboard titles use legacy color formatting.

## Building

```bash
mvn clean package
```

The compiled JAR is placed in `target/Deathrun.jar`. Copy it to your server's `plugins/` folder.

If you don't have Maven installed, you can download it from [maven.apache.org](https://maven.apache.org/download.cgi) or install it via a package manager:

```bash
# macOS (Homebrew)
brew install maven

# Windows (Chocolatey)
choco install maven

# Debian / Ubuntu
sudo apt install maven
```

## Setup Workflow

1. Start the server — only OPs can join (maintenance mode shown in server list)
2. Join as OP, stand at the desired cage location, run `/dr buildcage`
   - Cage is built, spawn point and measurement start are set automatically
   - All mobs and item drops in the area are removed automatically
3. Optionally adjust direction with `/dr setdirection` — the indicator wall moves live
4. Run `/dr open` to allow all players to join
5. Players join and see the lobby scoreboard
6. Run `/dr start` to begin the countdown
7. After the race, use `/dr stop` to reset for the next round

## Commands

Most commands require the `deathrun.admin` permission (default: OP). `/dr goto` is available to all players.

| Command | Permission | Description |
|---------|------------|-------------|
| `/dr buildcage` | `deathrun.admin` | Builds the cage at your position, sets spawn & measurement point `[Player]` |
| `/dr removecage` | `deathrun.admin` | Removes the cage and restores the original terrain `[Player]` |
| `/dr open` | `deathrun.admin` | Opens the server for all players |
| `/dr close` | `deathrun.admin` | Locks the server (OPs only) |
| `/dr start` | `deathrun.admin` | Starts the countdown |
| `/dr stop` | `deathrun.admin` | Aborts the current game / resets after game ends |
| `/dr pause` | `deathrun.admin` | Pauses or resumes the game |
| `/dr goto` | everyone | Teleports to the winner's location (only available after game ends) `[Player]` |
| `/dr setcorridor <n>` | `deathrun.admin` | Sets corridor half-width in blocks (default: 30) |
| `/dr setdirection <dir>` | `deathrun.admin` | Sets run direction: `NORTH` / `SOUTH` / `EAST` / `WEST` |
| `/dr settime <min>` | `deathrun.admin` | Sets time limit in minutes (0 = no limit) |
| `/dr status` | `deathrun.admin` | Shows current configuration |

## Configuration

`plugins/Deathrun/config.yml`:

```yaml
# Language / Sprache
#   de, en, fr, es, ...        – one language for all players
#   auto                       – each player sees their Minecraft client language
language: de

server-name: "DeathRun"        # Scoreboard title
direction: NORTH               # Run direction
corridor-width: 30             # WorldBorder half-width (blocks left/right)
countdown: 10                  # Countdown in seconds before race start

# PvP settings
pvp: false                     # Enable PvP between participants
pvp-delay: 30                  # Seconds after race start before PvP activates (0 = immediately)
pvp-countdown: 5               # Seconds of countdown warning before PvP activates

max-time: 0                    # Time limit in minutes (0 = no limit)
border-damage-per-block: 0.5   # Damage per block outside border per second
border-damage-buffer: 0.0      # Grace distance before border deals damage

# Cage settings
cage:
  radius: 3                    # Base cage radius (actual width = radius × 3)
  built: false                 # Set automatically by the plugin – do not edit

# Set automatically by /dr buildcage:
start:
  world: world
  x: 0.0
  y: 64.0
  z: 0.0
spawn:
  world: world
  x: 0.5
  y: 64.0
  z: 0.5
  yaw: 180.0
```
## Cage Behaviour

- **Building:** `/dr buildcage` clears the area (no item/plant drops), removes all mobs and animals in the zone, and builds the cage with a lime-glass wall facing the run direction.
- **Direction indicator:** The lime-glass wall always faces the run direction. Running `/dr setdirection` while a cage is standing updates the wall in-place without rebuilding.
- **Terrain restoration:** `/dr removecage` restores the original blocks using a pre-build snapshot. If the server was restarted since the cage was built, the fallback applies: walls and ceiling → air, floor top → grass, floor below → dirt.
- **Block protection:** Cage blocks are indestructible at all times for all players. During pause, no blocks can be broken or placed by participants either.
- **Persistence:** The cage state is saved to `config.yml` (`cage.built`). After a server restart the plugin re-registers all cage positions automatically — `/dr start`, `/dr removecage`, and block protection all work immediately without rebuilding.

## PvP Delay

When `pvp: true` is set, PvP is **not** active immediately at race start. The delay system works as follows:

1. At race start: *"⚔ PvP activates in 30 seconds!"*
2. At `pvp-delay - pvp-countdown` seconds: per-second countdown broadcast begins
3. At `pvp-delay` seconds: *"⚔ PvP is now active!"*

PvP is only ever active during an **active, unpaused race**. It is automatically disabled before the race, during pause, and after the game ends. The delay timer also pauses when the game is paused and resumes on unpause. Set `pvp-delay: 0` to activate PvP immediately at the GO signal.

## Day/Night & Weather Cycle

The day/night and weather cycles are controlled automatically:

| State | Cycle |
|-------|-------|
| IDLE / STARTING / ENDED | Frozen |
| RUNNING (active) | Running |
| RUNNING (paused) | Frozen |

## Multilingual Support

The plugin ships with 19 languages. All bundled language files are extracted to `plugins/Deathrun/lang/` and **overwritten on every server start** to keep them up to date. If you want to customise a bundled language, create a new file with a different code (e.g. `en_custom.yml`) — those are never overwritten.

| Code | Language | Code | Language |
|------|----------|------|----------|
| `de` | Deutsch | `ru` | Русский |
| `en` | English | `zh` | 简体中文 |
| `fr` | Français | `ja` | 日本語 |
| `es` | Español | `ko` | 한국어 |
| `pt` | Português | `tr` | Türkçe |
| `it` | Italiano | `sv` | Svenska |
| `nl` | Nederlands | `cs` | Čeština |
| `pl` | Polski | `hu` | Magyar |
| `uk` | Українська | `ro` | Română |
| `fi` | Suomi | | |

**Modes:**
- `language: de` (or any code) — everyone sees that language, regardless of client settings
- `language: auto` — each player sees texts in their Minecraft client language; falls back to English if unavailable

**Adding a custom language:** Place a new `xx.yml` file (e.g. `ar.yml`) in `plugins/Deathrun/lang/` using the same key structure as `en.yml`. With `language: auto` it is picked up automatically on the next server start.

## Scoreboard & Tab List

**Scoreboard states:**

| State | Display |
|-------|---------|
| Server locked, no cage | Setup instructions for OP |
| Server locked, cage built | Prompt to `/dr open` or `/dr removecage` |
| Server open, waiting | "Waiting for /dr start" + player count |
| Countdown | Live countdown + participant list |
| Race running | Top 5, personal rank / distance / lateral offset (L/R) / timer |
| Game ended | Top 5 + winner + personal rank, persists until `/dr stop` |

**Tab list** shows each participant's rank, name, distance, alive/dead icon, and current hearts (color-coded: green > 6 ❤, yellow 3–6 ❤, red < 3 ❤). Dead players show no heart count.

## Server List MOTD

The server list entry changes automatically based on game state (text follows the configured language):

- `[Maintenance]` — Server locked (setup phase)
- `[Countdown]` — Countdown running
- `[Running]` — Race in progress
- `[Ended]` — Game ended, results available

## Game Rules

- **Health:** Regeneration is fully disabled. All damage is permanent.
- **Corridor:** A per-player WorldBorder follows each runner and deals configurable damage outside the corridor.
- **Score:** Blocks traveled in the run direction from the cage's outer wall.
- **End condition:** Game ends when all players are dead, or the last survivor overtakes the highest dead player's score.
- **Disconnects:** A disconnected player is marked as absent (not dead) and can reconnect to continue racing.
- **Late joiners:** Blocked during an active game. Admins can always join.
- **After game ends:** No damage, no mob targeting. A clickable chat message lets everyone teleport to the winner's position.

## Pause Mode

`/dr pause` freezes the game for all participants:

- Movement locked (vertical movement still allowed)
- No damage, no mob targeting
- Day/night and weather cycle frozen
- PvP delay timer frozen
- All inventory interaction, item use, attacking, projectiles, drops, pickups and block placing/breaking blocked
- Visible `⏸ PAUSE` action bar for all participants

## Package

`de.flyingfinger.minecraft.deathrun`

## License

MIT
