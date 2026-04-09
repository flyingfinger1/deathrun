package de.flyingfinger.minecraft.deathrun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Processes all subcommands of the {@code /dr} command and provides
 * tab completion. Commands without admin permission are restricted to
 * {@code /dr goto}.
 */
public class DeathrunCommand implements CommandExecutor, TabCompleter {

    private final GameManager gm;

    /**
     * Creates a new command handler backed by the given GameManager.
     * @param gm the central GameManager instance to which commands are delegated
     */
    public DeathrunCommand(GameManager gm) {
        this.gm = gm;
    }

    /** Dispatches incoming {@code /dr} commands to the corresponding GameManager methods. */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";

        // /dr goto is allowed for all players (no admin required)
        if (sub.equals("goto")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Messages.comp("cmd.goto.player-only"));
                return true;
            }
            gm.handleGoto(player);
            return true;
        }

        // All other commands require deathrun.admin (console always has access)
        if (sender instanceof Player player && !player.hasPermission("deathrun.admin")) {
            player.sendMessage(Messages.comp(player, "cmd.no-permission"));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (sub) {

            // ── Players only (requires position) ───────────────────────────
            case "buildcage", "removecage" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Messages.comp("cmd.player-only"));
                    return true;
                }
                if (args[0].equalsIgnoreCase("buildcage")) gm.buildCage(player);
                else                                        gm.removeCage(player);
            }

            // ── Console & players ─────────────────────────────────────────────
            case "open"   -> gm.openServer(sender);
            case "close"  -> gm.closeServer(sender);
            case "start"  -> gm.startGame(sender);
            case "stop"   -> gm.stopGame(sender);
            case "pause"  -> gm.togglePause(sender);
            case "status" -> gm.showStatus(sender);

            case "setcorridor" -> {
                if (args.length < 2) {
                    sender.sendMessage(Messages.comp(sender, "cmd.setcorridor.usage"));
                    return true;
                }
                try { gm.setCorridorWidth(sender, Integer.parseInt(args[1])); }
                catch (NumberFormatException e) { sender.sendMessage(Messages.comp(sender, "cmd.invalid-number")); }
            }

            case "setdirection" -> {
                if (args.length < 2) {
                    sender.sendMessage(Messages.comp(sender, "cmd.setdirection.usage"));
                    return true;
                }
                gm.setDirection(sender, args[1]);
            }

            case "settime" -> {
                if (args.length < 2) {
                    sender.sendMessage(Messages.comp(sender, "cmd.settime.usage"));
                    return true;
                }
                try { gm.setMaxTime(sender, Integer.parseInt(args[1])); }
                catch (NumberFormatException e) { sender.sendMessage(Messages.comp(sender, "cmd.invalid-number")); }
            }

            default -> sendHelp(sender);
        }
        return true;
    }

    /**
     * Sends the full command help to the sender.
     * @param s recipient of the help message
     */
    private void sendHelp(CommandSender s) {
        s.sendMessage(Component.text(Messages.str(s, "cmd.help.header"), NamedTextColor.GOLD));
        help(s, "/dr buildcage",          Messages.str(s, "cmd.help.buildcage"));
        help(s, "/dr removecage",         Messages.str(s, "cmd.help.removecage"));
        help(s, "/dr open",               Messages.str(s, "cmd.help.open"));
        help(s, "/dr close",              Messages.str(s, "cmd.help.close"));
        help(s, "/dr setcorridor <n>",    Messages.str(s, "cmd.help.setcorridor"));
        help(s, "/dr setdirection <dir>", Messages.str(s, "cmd.help.setdirection"));
        help(s, "/dr settime <min>",      Messages.str(s, "cmd.help.settime"));
        help(s, "/dr start",              Messages.str(s, "cmd.help.start"));
        help(s, "/dr stop",               Messages.str(s, "cmd.help.stop"));
        help(s, "/dr pause",              Messages.str(s, "cmd.help.pause"));
        help(s, "/dr goto",               Messages.str(s, "cmd.help.goto"));
        help(s, "/dr status",             Messages.str(s, "cmd.help.status"));
    }

    /**
     * Sends a single formatted help line.
     * @param s    recipient
     * @param cmd  command (yellow)
     * @param desc description (gray)
     */
    private void help(CommandSender s, String cmd, String desc) {
        s.sendMessage(Component.text("  " + cmd, NamedTextColor.YELLOW)
            .append(Component.text(" – " + desc, NamedTextColor.GRAY)));
    }

    /** Provides tab completion suggestions for subcommand and direction argument. */
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
