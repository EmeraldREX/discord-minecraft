package dev.emeraldrex.discordminecraft.velocity;

import com.github.ucchyocean.lc3.LunaChat;
import com.github.ucchyocean.lc3.channel.Channel;
import com.github.ucchyocean.lc3.member.ChannelMember;
import com.github.ucchyocean.lc3.messaging.BukkitChatMessage;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import dev.emeraldrex.discordminecraft.store.Account;
import dev.emeraldrex.discordminecraft.store.AccountStore;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Plugin(id = "discordminecraft", name = "DiscordMinecraft", version = "0.1.6",
        description = "Authenticated bidirectional Discord and LunaChat bridge", authors = {"EmeraldREX"},
        dependencies = {@com.velocitypowered.api.plugin.Dependency(id = "lunachat")})
public final class DiscordMinecraftPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private PluginConfig config;
    private AccountStore store;
    private JDA jda;
    private DiscordListener discord;

    @Inject public DiscordMinecraftPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe public void onInitialize(ProxyInitializeEvent ignored) {
        try {
            config = PluginConfig.load(dataDirectory);
            store = new AccountStore(dataDirectory.resolve("accounts.db"), Clock.systemUTC());
            CommandMeta meta = proxy.getCommandManager().metaBuilder("discord").aliases("dc").plugin(this).build();
            proxy.getCommandManager().register(meta, new BridgeCommand(this));
            if (config.discordConfigured()) startDiscord();
            else logger.warn("Discord is disabled until token, guild-id and channel-id are set in {}", dataDirectory.resolve("config.yml"));
        } catch (Exception exception) {
            logger.error("DiscordMinecraft could not start", exception);
        }
    }

    private void startDiscord() throws InterruptedException {
        discord = new DiscordListener(this);
        jda = JDABuilder.createDefault(config.token(), GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(discord).build().awaitReady();
        Guild guild = jda.getGuildById(config.guildId());
        if (guild == null) throw new IllegalStateException("Configured Discord guild is not accessible");
        var channel = jda.getTextChannelById(config.channelId());
        if (channel == null || channel.getGuild().getIdLong() != guild.getIdLong())
            throw new IllegalStateException("Configured Discord channel is not an accessible text channel in the configured guild");
        guild.updateCommands().addCommands(Commands.slash("minecraft", "Minecraft account integration")
                .addSubcommands(new SubcommandData("link", "Link your Minecraft account")
                        .addOption(OptionType.STRING, "code", "Code shown by /discord link", true))).queue();
        logger.info("Discord bridge connected as {}", jda.getSelfUser().getName());
    }

    @Subscribe(order = PostOrder.LAST)
    public void onLunaChatPluginMessage(PluginMessageEvent event) {
        if (discord == null || !event.getIdentifier().getId().equals("lunachat:message")) return;
        BukkitChatMessage chat = BukkitChatMessage.fromByteArray(event.getData());
        if (chat == null || chat.member() == null || chat.message() == null) return;
        ChannelMember member = chat.member();
        Player receiver = event.getTarget() instanceof Player targetPlayer
                ? targetPlayer
                : event.getSource() instanceof ServerConnection sourceServer ? sourceServer.getPlayer() : null;
        if (receiver == null || !receiver.getUsername().equalsIgnoreCase(member.getName())) return;
        Player player = receiver;
        runAsync(() -> {
            Account account = store.findByMinecraft(player.getUniqueId()).orElse(null);
            if (account == null) {
                logger.info("Skipping LunaChat message from {}: Discord account is not linked", player.getUsername());
                return;
            }
            if (!account.sendEnabled()) {
                logger.info("Skipping LunaChat message from {}: send toggle is OFF", player.getUsername());
                return;
            }
            String server = player.getCurrentServer().map(connection -> connection.getServerInfo().getName())
                    .orElse(member.getServerName());
            Channel channel = LunaChat.getAPI().getDefaultChannel(player.getUsername());
            String channelName = channel == null ? "LunaChat" : channel.getName();
            logger.info("Forwarding LunaChat message from {} on {}/{} to Discord", player.getUsername(),
                    server, channelName);
            discord.sendMinecraftMessage(server, channelName, member.getDisplayName(), chat.message());
        });
    }

    void receiveDiscordMessage(long discordId, String discordName, String message) throws SQLException {
        Account sender = store.findByDiscord(discordId).orElse(null);
        if (sender == null || !sender.sendEnabled()) return;
        String safeName = sanitizeMinecraft(discordName, 64);
        String safeMessage = sanitizeMinecraft(message, 1_000);
        String rendered = config.minecraftFormat().replace("{name}", safeName).replace("{message}", safeMessage);
        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(rendered);
        for (Account receiver : store.receiveEnabledAccounts()) {
            proxy.getPlayer(receiver.minecraftId()).ifPresent(player -> player.sendMessage(component));
        }
    }

    private static String sanitizeMinecraft(String value, int maximumLength) {
        String safe = value.replace('&', '＆').replace('\n', ' ').replace('\r', ' ');
        return safe.length() <= maximumLength ? safe : safe.substring(0, maximumLength - 3) + "...";
    }

    void runAsync(ThrowingRunnable task) {
        executor.submit(() -> {
            try { task.run(); }
            catch (Exception exception) { logger.error("Asynchronous bridge operation failed", exception); }
        });
    }

    @Subscribe public void onShutdown(ProxyShutdownEvent ignored) {
        if (jda != null) jda.shutdown();
        executor.shutdown();
        if (store != null) try { store.close(); } catch (SQLException exception) { logger.error("Could not close database", exception); }
    }

    PluginConfig config() { return config; }
    AccountStore store() { return store; }
    JDA jda() { return jda; }
    void logDiscordSendFailure(Throwable error) { logger.error("Discord message send failed", error); }
    @FunctionalInterface interface ThrowingRunnable { void run() throws Exception; }
}
