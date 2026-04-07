package com.example.deathrun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public class DeathrunCommand implements CommandExecutor, TabCompleter {

    private final GameManager gm;

    public DeathrunCommand(GameManager gm) {
        this.gm = gm;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // Berechtigungs-Check (Konsole hat immer Zugriff)
        if (sender instanceof Player player && !player.hasPermission("deathrun.admin")) {
            player.sendMessage(Component.text("Keine Berechtigung.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {

            // ── Nur für Spieler (braucht Position) ───────────────────────────
            case "buildcage", "removecage" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Dieser Befehl erfordert einen Spieler im Spiel.");
                    return true;
                }
                if (args[0].equalsIgnoreCase("buildcage")) gm.buildCage(player);
                else                                        gm.removeCage(player);
            }

            // ── Konsole & Spieler ─────────────────────────────────────────────
            case "open"   -> gm.openServer(sender);
            case "close"  -> gm.closeServer(sender);
            case "start"  -> gm.startGame(sender);
            case "stop"   -> gm.stopGame(sender);
            case "pause"  -> gm.togglePause(sender);
            case "status" -> gm.showStatus(sender);
            case "goto"   -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Dieser Befehl erfordert einen Spieler.");
                    return true;
                }
                gm.handleGoto(player);
            }

            case "setcorridor" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Nutzung: /dr setcorridor <Blöcke>", NamedTextColor.RED));
                    return true;
                }
                try { gm.setCorridorWidth(sender, Integer.parseInt(args[1])); }
                catch (NumberFormatException e) { sender.sendMessage(Component.text("Ungültige Zahl.", NamedTextColor.RED)); }
            }

            case "setdirection" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Nutzung: /dr setdirection <NORTH|SOUTH|EAST|WEST>", NamedTextColor.RED));
                    return true;
                }
                gm.setDirection(sender, args[1]);
            }

            case "settime" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Nutzung: /dr settime <Minuten> (0 = kein Limit)", NamedTextColor.RED));
                    return true;
                }
                try { gm.setMaxTime(sender, Integer.parseInt(args[1])); }
                catch (NumberFormatException e) { sender.sendMessage(Component.text("Ungültige Zahl.", NamedTextColor.RED)); }
            }

            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(Component.text("── Deathrun Befehle ──", NamedTextColor.GOLD));
        help(s, "/dr buildcage",          "Baut den Glaskäfig & setzt Spawn/Messpunkt [Spieler]");
        help(s, "/dr removecage",         "Entfernt den Käfig [Spieler]");
        help(s, "/dr open",               "Gibt den Server für alle Spieler frei");
        help(s, "/dr close",              "Sperrt den Server (nur OPs)");
        help(s, "/dr setcorridor <n>",    "Setzt die Korridorbreite (±n Blöcke)");
        help(s, "/dr setdirection <dir>", "Laufrichtung: NORTH/SOUTH/EAST/WEST");
        help(s, "/dr settime <Min>",      "Maximale Spielzeit (0 = kein Limit)");
        help(s, "/dr start",              "Startet das Deathrun");
        help(s, "/dr stop",               "Bricht das Deathrun ab / Reset nach Ende");
        help(s, "/dr pause",              "Pausiert/Setzt das Deathrun fort");
        help(s, "/dr goto",               "Teleportiert zum Gewinner-Ort [Spieler]");
        help(s, "/dr status",             "Zeigt aktuelle Konfiguration");
    }

    private void help(CommandSender s, String cmd, String desc) {
        s.sendMessage(Component.text("  " + cmd, NamedTextColor.YELLOW)
            .append(Component.text(" – " + desc, NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("buildcage","removecage","open","close","setcorridor","setdirection","settime","start","stop","pause","goto","status")
                .stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setdirection")) {
            return List.of("NORTH","SOUTH","EAST","WEST");
        }
        return List.of();
    }
}
