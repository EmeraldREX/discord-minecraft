package dev.emeraldrex.discordminecraft.velocity;

import dev.emeraldrex.discordminecraft.store.Account;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

final class DiscordListener extends ListenerAdapter {
    private final DiscordMinecraftPlugin plugin;
    DiscordListener(DiscordMinecraftPlugin plugin) { this.plugin = plugin; }

    @Override public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.getName().equals("minecraft") || !event.isFromGuild()
                || event.getGuild() == null || event.getGuild().getIdLong() != plugin.config().guildId()) return;
        String subcommand = event.getSubcommandName();
        if ("link".equals(subcommand)) {
            String code = event.getOption("code") == null ? "" : event.getOption("code").getAsString();
            event.deferReply(true).queue();
            plugin.runAsync(() -> {
                Account account = plugin.store().claimLinkCode(code, event.getUser().getIdLong()).orElse(null);
                event.getHook().editOriginal(account == null
                        ? "コードが無効か期限切れです。Minecraft で /discord link を再実行してください。"
                        : account.minecraftName() + " と認証しました。安全のため送信・受信は両方 OFF です。").queue();
            });
        }
    }

    @Override public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.getChannel().getIdLong() != plugin.config().channelId()) return;
        String message = event.getMessage().getContentDisplay().strip();
        if (message.isEmpty()) return;
        plugin.runAsync(() -> plugin.receiveDiscordMessage(event.getAuthor().getIdLong(),
                event.getMember() == null ? event.getAuthor().getName() : event.getMember().getEffectiveName(), message));
    }

    void sendMinecraftMessage(String server, String channel, String name, String message) {
        if (plugin.jda() == null) return;
        TextChannel target = plugin.jda().getTextChannelById(plugin.config().channelId());
        if (target == null) return;
        String rendered = plugin.config().discordFormat()
                .replace("{server}", safe(server)).replace("{channel}", safe(channel))
                .replace("{name}", safe(name)).replace("{message}", safe(message));
        if (rendered.length() > 2_000) rendered = rendered.substring(0, 1_997) + "...";
        target.sendMessage(rendered).setAllowedMentions(java.util.List.of()).queue(
                ignored -> { }, plugin::logDiscordSendFailure);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("@", "＠").replace("`", "ˋ");
    }
}
