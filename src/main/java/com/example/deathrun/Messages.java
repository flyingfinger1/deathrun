package com.example.deathrun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Lädt Sprachdateien aus plugins/Deathrun/lang/<language>.yml und
 * stellt Hilfsmethoden für lokalisierte Strings und Adventure-Komponenten bereit.
 */
public final class Messages {

    private static YamlConfiguration lang;

    private Messages() {}

    /**
     * Lädt die Sprachdatei für die angegebene Sprache.
     * Falls die Datei noch nicht im Plugin-Ordner existiert, wird sie aus den Ressourcen kopiert.
     * Unbekannte Sprache → Fallback auf Englisch (en).
     */
    public static void load(Plugin plugin, String language) {
        // Sicherstellen, dass die Datei im Daten-Ordner existiert
        File langDir  = new File(plugin.getDataFolder(), "lang");
        File langFile = new File(langDir, language + ".yml");

        if (!langFile.exists()) {
            // Aus JAR-Ressourcen extrahieren
            InputStream resource = plugin.getResource("lang/" + language + ".yml");
            if (resource == null) {
                plugin.getLogger().warning("Sprache '" + language + "' nicht gefunden, nutze 'de'.");
                language = "de";
                langFile = new File(langDir, "de.yml");
                resource = plugin.getResource("lang/de.yml");
            }
            if (resource != null) {
                langDir.mkdirs();
                try (InputStream in = resource;
                     OutputStream out = new FileOutputStream(langFile)) {
                    in.transferTo(out);
                } catch (IOException e) {
                    plugin.getLogger().severe("Sprachdatei konnte nicht gespeichert werden: " + e.getMessage());
                }
            }
        }

        lang = YamlConfiguration.loadConfiguration(langFile);

        // Defaults aus dem JAR einblenden (neue Keys in Sprachdateien werden so ergänzt)
        InputStream def = plugin.getResource("lang/" + language + ".yml");
        if (def != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(def, StandardCharsets.UTF_8));
            lang.setDefaults(defaults);
        }
    }

    /**
     * Gibt den lokalisierten String für den Schlüssel zurück.
     * Platzhalter {0}, {1}, ... werden durch die übergebenen Argumente ersetzt.
     */
    public static String str(String key, Object... args) {
        if (lang == null) return "§c[Messages not loaded: " + key + "]";
        String value = lang.getString(key);
        if (value == null) value = "§c[?" + key + "]";
        for (int i = 0; i < args.length; i++) {
            value = value.replace("{" + i + "}", args[i] == null ? "" : String.valueOf(args[i]));
        }
        return value;
    }

    /**
     * Gibt den lokalisierten String als Adventure-Component zurück.
     * §-Farbcodes werden dabei in Adventure-Formatierung umgewandelt.
     */
    public static Component comp(String key, Object... args) {
        return LegacyComponentSerializer.legacySection().deserialize(str(key, args));
    }
}
