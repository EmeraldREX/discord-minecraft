package dev.emeraldrex.discordminecraft.velocity;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

record PluginConfig(String token, long guildId, long channelId, int linkCodeMinutes,
                    String minecraftFormat, String discordFormat) {
    static PluginConfig load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve("config.yml");
        if (Files.notExists(file)) {
            try (InputStream resource = PluginConfig.class.getResourceAsStream("/config.yml")) {
                if (resource == null) throw new IOException("Bundled config.yml is missing");
                Files.copy(resource, file);
            }
        }
        Map<String, Object> root;
        try (var reader = Files.newBufferedReader(file)) {
            root = new Yaml().load(reader);
        }
        Map<?, ?> discord = section(root, "discord");
        Map<?, ?> messages = section(root, "messages");
        String environmentToken = System.getenv("DISCORD_BOT_TOKEN");
        String token = environmentToken == null || environmentToken.isBlank() ? text(discord, "token") : environmentToken;
        return new PluginConfig(token, environmentId("DISCORD_GUILD_ID", id(discord, "guild-id")),
                environmentId("DISCORD_CHANNEL_ID", id(discord, "channel-id")),
                number(root, "link-code-minutes", 10), text(messages, "minecraft-format"),
                text(messages, "discord-format"));
    }

    boolean discordConfigured() { return !token.isBlank() && guildId > 0 && channelId > 0; }

    private static Map<?, ?> section(Map<String, Object> root, String key) throws IOException {
        Object value = root.get(key);
        if (value instanceof Map<?, ?> map) return map;
        throw new IOException("Missing config section: " + key);
    }
    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }
    private static long id(Map<?, ?> map, String key) {
        String value = text(map, key).trim();
        try { return value.isEmpty() ? 0 : Long.parseUnsignedLong(value); }
        catch (NumberFormatException ignored) { return 0; }
    }
    private static long environmentId(String name, long fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return fallback;
        try { return Long.parseUnsignedLong(value.trim()); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static int number(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
