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
 * Lädt Sprachdateien und stellt lokalisierte Strings bereit.
 *
 * Modus:
 *  - language: de / en / ...  → eine globale Sprache für alle
 *  - language: auto            → pro Spieler die Client-Sprache (player.locale())
 *                                 Konsole und Broadcasts nutzen "en" als Fallback
 */
public final class Messages {

    /** Bekannte, mitgelieferte Sprachdateien (alle im JAR enthaltenen lang/*.yml) */
    private static final String[] BUNDLED = {
        "de", "en", "fr", "es", "pt", "it", "nl", "pl",
        "ru", "zh", "ja", "ko", "tr", "sv", "cs", "uk",
        "hu", "ro", "fi"
    };

    private static boolean autoMode    = false;
    private static String  defaultLang = "de";

    /** Cache: Sprachcode → geladene YamlConfiguration */
    private static final Map<String, YamlConfiguration> cache = new HashMap<>();

    private Messages() {}

    // ── Laden ─────────────────────────────────────────────────────────────────

    public static void load(Plugin plugin, String language) {
        autoMode = "auto".equalsIgnoreCase(language);
        cache.clear();

        if (autoMode) {
            // Alle mitgelieferten Sprachen vorausladen
            for (String lang : BUNDLED) extractAndLoad(plugin, lang);

            // Zusätzliche Dateien im Plugin-Ordner einlesen
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
            defaultLang = "en"; // Konsolen-Fallback im Auto-Modus
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
     * Extrahiert lang/<language>.yml aus dem JAR und lädt sie.
     *
     * Bundled-Sprachen (im JAR enthalten) werden bei jedem Plugin-Start
     * überschrieben, damit neue Keys (z.B. {4} in tablist.entry) auf dem
     * Server immer aktuell sind. Eigene Dateien, die nicht im JAR liegen,
     * werden nur angelegt, nie überschrieben.
     */
    private static String extractAndLoad(Plugin plugin, String language) {
        File langDir  = new File(plugin.getDataFolder(), "lang");
        File langFile = new File(langDir, language + ".yml");

        InputStream resource = plugin.getResource("lang/" + language + ".yml");
        boolean isBundled = resource != null;

        if (isBundled) {
            // Bundled-Datei immer aus dem JAR überschreiben (hält Keys aktuell)
            langDir.mkdirs();
            try (InputStream in = resource; OutputStream out = new FileOutputStream(langFile)) {
                in.transferTo(out);
            } catch (IOException e) {
                plugin.getLogger().severe("Sprachdatei konnte nicht gespeichert werden: " + e.getMessage());
                return null;
            }
        } else if (!langFile.exists()) {
            // Benutzerdefinierte Datei: nur anlegen, nie überschreiben
            return null;
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(langFile);
        cache.put(language, cfg);
        return language;
    }

    // ── Sprachauflösung ───────────────────────────────────────────────────────

    private static YamlConfiguration getLang(String language) {
        YamlConfiguration cfg = cache.get(language);
        if (cfg != null) return cfg;
        // Sprachpräfix prüfen: "de_DE" → "de"
        if (language.length() > 2) {
            cfg = cache.get(language.substring(0, 2));
            if (cfg != null) return cfg;
        }
        return cache.getOrDefault(defaultLang,
            cache.isEmpty() ? new YamlConfiguration() : cache.values().iterator().next());
    }

    private static String playerLang(Player player) {
        if (!autoMode) return defaultLang;
        return player.locale().getLanguage(); // z.B. "de", "en", "fr"
    }

    // ── Interne Formatierung ──────────────────────────────────────────────────

    private static String format(YamlConfiguration cfg, String key, Object... args) {
        String value = cfg.getString(key);
        if (value == null) value = "§c[?" + key + "]";
        for (int i = 0; i < args.length; i++) {
            value = value.replace("{" + i + "}", args[i] == null ? "" : String.valueOf(args[i]));
        }
        return value;
    }

    // ── Öffentliche API ───────────────────────────────────────────────────────

    /** Standardsprache – für Broadcasts, Konsole und non-player Kontexte. */
    public static String str(String key, Object... args) {
        return format(getLang(defaultLang), key, args);
    }

    /** Spieler-spezifische Sprache (Client-Locale bei auto, sonst Standardsprache). */
    public static String str(Player player, String key, Object... args) {
        if (player == null) return str(key, args);
        return format(getLang(playerLang(player)), key, args);
    }

    /** CommandSender: Spieler-Locale falls verfügbar, sonst Standardsprache. */
    public static String str(CommandSender sender, String key, Object... args) {
        if (sender instanceof Player p) return str(p, key, args);
        return str(key, args);
    }

    public static Component comp(String key, Object... args) {
        return LegacyComponentSerializer.legacySection().deserialize(str(key, args));
    }

    public static Component comp(Player player, String key, Object... args) {
        return LegacyComponentSerializer.legacySection().deserialize(str(player, key, args));
    }

    public static Component comp(CommandSender sender, String key, Object... args) {
        return LegacyComponentSerializer.legacySection().deserialize(str(sender, key, args));
    }

    public static boolean isAutoMode() { return autoMode; }
}
