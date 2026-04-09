package de.flyingfinger.minecraft.deathrun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads language files and provides localized strings.
 *
 * Mode:
 *  - language: de / en / ...  → one global language for everyone
 *  - language: auto            → per-player client language (player.locale())
 *                                 Console and broadcasts use "en" as fallback
 */
public final class Messages {

    /** Known bundled language files (all lang/*.yml files included in the JAR) */
    private static final String[] BUNDLED = {
        "de", "en", "fr", "es", "pt", "it", "nl", "pl",
        "ru", "zh", "ja", "ko", "tr", "sv", "cs", "uk",
        "hu", "ro", "fi"
    };

    private static boolean autoMode    = false;
    private static String  defaultLang = "de";

    /** Cache: language code → loaded YamlConfiguration */
    private static final Map<String, YamlConfiguration> cache = new HashMap<>();

    private Messages() {}

    // ── Loading ─────────────────────────────────────────────────────────────────

    /**
     * Loads the language file(s) and initializes the message system.
     * @param plugin   the plugin whose JAR and data folder is used for loading
     * @param language language code (e.g. {@code "de"}, {@code "en"}) or {@code "auto"}
     */
    public static void load(Plugin plugin, String language) {
        autoMode = "auto".equalsIgnoreCase(language);
        cache.clear();

        if (autoMode) {
            // Preload all bundled languages
            for (String lang : BUNDLED) extractAndLoad(plugin, lang);

            // Read additional files in the plugin folder
            File langDir = new File(plugin.getDataFolder(), "lang");
            if (langDir.isDirectory()) {
                File[] files = langDir.listFiles((d, n) -> n.endsWith(".yml"));
                if (files != null) {
                    for (File f : files) {
                        String lang = f.getName().replace(".yml", "");
                        if (!cache.containsKey(lang)) extractAndLoad(plugin, lang);
                    }
                }
            }
            defaultLang = "en"; // console fallback in auto mode
        } else {
            if (extractAndLoad(plugin, language) == null) {
                plugin.getLogger().warning("Sprache '" + language + "' nicht gefunden, nutze 'de'.");
                language = "de";
                extractAndLoad(plugin, language);
            }
            defaultLang = language;
        }
    }

    /**
     * Extracts lang/<language>.yml from the JAR and loads it.
     *
     * Bundled languages (included in the JAR) are overwritten on every plugin start
     * so that new keys (e.g. {4} in tablist.entry) are always up to date on the server.
     * Custom files not in the JAR are only created, never overwritten.
     */
    private static String extractAndLoad(Plugin plugin, String language) {
        File langDir  = new File(plugin.getDataFolder(), "lang");
        File langFile = new File(langDir, language + ".yml");

        InputStream resource = plugin.getResource("lang/" + language + ".yml");
        boolean isBundled = resource != null;

        if (isBundled) {
            // Always overwrite bundled file from the JAR (keeps keys up to date)
            langDir.mkdirs();
            try (InputStream in = resource; OutputStream out = new FileOutputStream(langFile)) {
                in.transferTo(out);
            } catch (IOException e) {
                plugin.getLogger().severe("Sprachdatei konnte nicht gespeichert werden: " + e.getMessage());
                return null;
            }
        } else if (!langFile.exists()) {
            // Custom file: only create, never overwrite
            return null;
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(langFile);
        cache.put(language, cfg);
        return language;
    }

    // ── Language resolution ───────────────────────────────────────────────────

    /**
     * Returns the loaded YAML configuration for the specified language code.
     * Falls back via the language prefix (e.g. {@code "de_DE"} → {@code "de"}) and then
     * to the default language.
     * @param language the player's language code
     * @return matching YAML configuration
     */
    private static YamlConfiguration getLang(String language) {
        YamlConfiguration cfg = cache.get(language);
        if (cfg != null) return cfg;
        // Check language prefix: "de_DE" → "de"
        if (language.length() > 2) {
            cfg = cache.get(language.substring(0, 2));
            if (cfg != null) return cfg;
        }
        return cache.getOrDefault(defaultLang,
            cache.isEmpty() ? new YamlConfiguration() : cache.values().iterator().next());
    }

    /**
     * Returns the player's language code (client locale only in auto mode,
     * otherwise the default language).
     * @param player the requesting player
     * @return language code (e.g. {@code "de"})
     */
    private static String playerLang(Player player) {
        if (!autoMode) return defaultLang;
        return player.locale().getLanguage(); // e.g. "de", "en", "fr"
    }

    // ── Internal formatting ──────────────────────────────────────────────────

    /**
     * Reads a key from the configuration and replaces placeholders ({0}, {1}, …).
     * @param cfg  the language YAML configuration
     * @param key  the message key
     * @param args placeholder values in order
     * @return fully formatted string (with legacy color codes)
     */
    private static String format(YamlConfiguration cfg, String key, Object... args) {
        String value = cfg.getString(key);
        if (value == null) value = "§c[?" + key + "]";
        for (int i = 0; i < args.length; i++) {
            value = value.replace("{" + i + "}", args[i] == null ? "" : String.valueOf(args[i]));
        }
        return value;
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Returns a localized string using the default language (for broadcasts, console, and non-player contexts).
     * @param key  the message key
     * @param args placeholder values in order
     * @return formatted localized string
     */
    public static String str(String key, Object... args) {
        return format(getLang(defaultLang), key, args);
    }

    /**
     * Returns a localized string using the player's language (client locale in auto mode, otherwise default language).
     * @param player the player whose language is used (may be {@code null})
     * @param key    the message key
     * @param args   placeholder values in order
     * @return formatted localized string
     */
    public static String str(Player player, String key, Object... args) {
        if (player == null) return str(key, args);
        return format(getLang(playerLang(player)), key, args);
    }

    /**
     * Returns a localized string using the sender's language (player locale if available, otherwise default language).
     * @param sender the command sender whose language is used
     * @param key    the message key
     * @param args   placeholder values in order
     * @return formatted localized string
     */
    public static String str(CommandSender sender, String key, Object... args) {
        if (sender instanceof Player p) return str(p, key, args);
        return str(key, args);
    }

    /**
     * Returns a localized message as an Adventure component (default language).
     * @param key  message key
     * @param args placeholder values
     * @return deserialized legacy component
     */
    public static Component comp(String key, Object... args) {
        return LegacyComponentSerializer.legacySection().deserialize(str(key, args));
    }

    /**
     * Returns a localized message as an Adventure component (player language).
     * @param player player whose language is used
     * @param key    message key
     * @param args   placeholder values
     * @return deserialized legacy component
     */
    public static Component comp(Player player, String key, Object... args) {
        return LegacyComponentSerializer.legacySection().deserialize(str(player, key, args));
    }

    /**
     * Returns a localized message as an Adventure component (sender language).
     * @param sender player or console as recipient
     * @param key    message key
     * @param args   placeholder values
     * @return deserialized legacy component
     */
    public static Component comp(CommandSender sender, String key, Object... args) {
        return LegacyComponentSerializer.legacySection().deserialize(str(sender, key, args));
    }

    /**
     * Returns whether the per-player client locale is active.
     * @return {@code true} if the client locale is used per player
     */
    public static boolean isAutoMode() { return autoMode; }
}
