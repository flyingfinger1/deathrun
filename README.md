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

All commands require the `deathrun.admin` permission. Console can run all commands except those marked `[Player]`.

| Command | Description |
|---------|-------------|
| `/dr buildcage` | Builds the cage at your position, sets spawn & measurement point `[Player]` |
| `/dr removecage` | Removes the cage `[Player]` |
| `/dr open` | Opens the server for all players |
| `/dr close` | Locks the server (OPs only) |
| `/dr start` | Starts the countdown |
| `/dr stop` | Aborts the current game / resets after game ends |
| `/dr pause` | Pauses or resumes the game |
| `/dr goto` | Teleports to the winner's location `[Player]` |
| `/dr setcorridor <n>` | Sets corridor half-width in blocks (default: 30) |
| `/dr setdirection <dir>` | Sets run direction: `NORTH` / `SOUTH` / `EAST` / `WEST` |
| `/dr settime <min>` | Sets time limit in minutes (0 = no limit) |
| `/dr status` | Shows current configuration |

## Configuration

`plugins/Deathrun/config.yml`:

```yaml
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

## Scoreboard States

| State | Display |
|-------|---------|
| Server locked, no cage | Setup instructions for OP |
| Server locked, cage built | Prompt to `/dr open` or `/dr removecage` |
| Server open, waiting | "Waiting for /dr start" + player count |
| Countdown | Live countdown + participant list |
| Race running | Top 5, personal rank/distance/EW-deviation, timer |
| Game ended | Top 5 + winner + personal rank, persists until `/dr stop` |

## Server List MOTD

The server list entry changes automatically based on game state:

- `[Wartung]` — Server locked (setup phase)
- `[Countdown]` — Countdown running
- `[Läuft]` — Race in progress
- `[Ende]` — Game ended, results available

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

## License

MIT
