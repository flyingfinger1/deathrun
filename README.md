# Deathrun Plugin

A Minecraft Deathrun plugin for **Paper 1.21.8**. Players race in one cardinal direction — every heart lost is permanent, the corridor is enforced by a moving WorldBorder, and the scoreboard tracks live distances for all participants.

## Concept

All players start inside a glass cage. When the countdown ends, the front wall opens and everyone sprints in the configured run direction (e.g. North). Health regeneration is completely disabled — every point of damage is permanent. A per-player WorldBorder follows each runner and deals damage outside the corridor. The player who travels the furthest wins.

## Requirements

- Paper 1.21.8
- Java 21
- Maven (auto-downloaded by `build.ps1` if not installed)

## Building

```powershell
.\build.ps1
```

The script downloads Maven 3.9.9 on first run, compiles the plugin, and copies the JAR to `server\plugins\` if that folder exists.

## Setup Workflow

1. Start the server — only OPs can join (maintenance mode shown in server list)
2. Join as OP, stand at the desired cage location, run `/dr buildcage`
   - Cage is built, spawn point and measurement start are set automatically
3. Run `/dr open` to allow all players to join
4. Players join and see the lobby scoreboard
5. Run `/dr start` to begin the countdown
6. After the race, use `/dr stop` to reset for the next round

## Commands

Most commands require the `deathrun.admin` permission (default: OP). `/dr goto` is available to all players.

| Command | Permission | Description |
|---------|------------|-------------|
| `/dr buildcage` | `deathrun.admin` | Builds the cage at your position, sets spawn & measurement point `[Player]` |
| `/dr removecage` | `deathrun.admin` | Removes the cage `[Player]` |
| `/dr open` | `deathrun.admin` | Opens the server for all players |
| `/dr close` | `deathrun.admin` | Locks the server (OPs only) |
| `/dr start` | `deathrun.admin` | Starts the countdown |
| `/dr stop` | `deathrun.admin` | Aborts the current game / resets after game ends |
| `/dr pause` | `deathrun.admin` | Pauses or resumes the game |
| `/dr goto` | everyone | Teleports to the winner's location `[Player]` |
| `/dr setcorridor <n>` | `deathrun.admin` | Sets corridor half-width in blocks (default: 30) |
| `/dr setdirection <dir>` | `deathrun.admin` | Sets run direction: `NORTH` / `SOUTH` / `EAST` / `WEST` |
| `/dr settime <min>` | `deathrun.admin` | Sets time limit in minutes (0 = no limit) |
| `/dr status` | `deathrun.admin` | Shows current configuration |

## Configuration

`plugins/Deathrun/config.yml`:

```yaml
# Language / Sprache
#   de, en, fr, es, ...  – one language for all players
#   auto                  – each player sees their Minecraft client language
language: de

server-name: "DeathRun"       # Scoreboard title
direction: NORTH               # Run direction
corridor-width: 30             # WorldBorder half-width (blocks left/right)
cage-radius: 3                 # Base cage radius (actual size = 3×)
countdown: 10                  # Countdown in seconds
pvp: false                     # PvP between participants
max-time: 0                    # Time limit in minutes (0 = no limit)
border-damage-per-block: 0.5   # Damage per block outside border
border-damage-buffer: 0.0      # Grace distance before border deals damage

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

## Multilingual Support

The plugin ships with 19 languages. All language files are extracted to `plugins/Deathrun/lang/` on first start and can be freely edited.

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
- `language: de` (or any code) — everyone sees that one language, regardless of client settings
- `language: auto` — each player sees the plugin texts in their Minecraft client language; falls back to English if the language is not available

**Adding a custom language:** Place a new `xx.yml` file (e.g. `ar.yml`) in `plugins/Deathrun/lang/` using the same key structure as `en.yml`. With `language: auto` it is picked up automatically on the next server start.

## Scoreboard States

| State | Display |
|-------|---------|
| Server locked, no cage | Setup instructions for OP |
| Server locked, cage built | Prompt to `/dr open` or `/dr removecage` |
| Server open, waiting | "Waiting for /dr start" + player count |
| Countdown | Live countdown + participant list |
| Race running | Top 5, personal rank/distance/lateral deviation, timer |
| Game ended | Top 5 + winner + personal rank, persists until `/dr stop` |

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
- **Disconnects:** A disconnected player is marked as absent (not dead) and can reconnect to continue racing. The game keeps running while alive players are offline.
- **Late joiners:** Blocked during an active game. Admins can always join.
- **After game ends:** No damage, no mob targeting. A clickable chat message lets everyone teleport to the winner's position.

## Pause Mode

`/dr pause` freezes the game for all participants:

- Movement locked (vertical movement still allowed)
- No damage
- Mobs do not target players
- Day/night cycle paused
- All inventory interaction, item use, attacking, projectiles, drops and pickups blocked
- Visible `⏸ PAUSE` action bar for all participants

## Package

`de.flyingfinger.minecraft.deathrun`

## License

MIT
