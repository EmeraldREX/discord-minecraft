package dev.emeraldrex.discordminecraft.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.emeraldrex.discordminecraft.store.Account;
import net.kyori.adventure.text.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

final class BridgeCommand implements SimpleCommand {
    private final DiscordMinecraftPlugin plugin;
    BridgeCommand(DiscordMinecraftPlugin plugin) { this.plugin = plugin; }

    @Override public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("This command can only be used by a player."));
            return;
        }
        String[] args = invocation.arguments();
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        plugin.runAsync(() -> handle(player, action, args));
    }

    private void handle(Player player, String action, String[] args) throws Exception {
        switch (action) {
            case "link" -> {
                String code = plugin.store().createLinkCode(player.getUniqueId(), player.getUsername(),
                        Duration.ofMinutes(plugin.config().linkCodeMinutes()));
                tell(player, "Discord で /minecraft link code:" + code + " を実行してください。コードは "
                        + plugin.config().linkCodeMinutes() + " 分間有効です。");
            }
            case "send", "receive" -> {
                if (args.length < 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
                    tell(player, "/discord " + action + " <on|off>");
                    return;
                }
                boolean enabled = args[1].equalsIgnoreCase("on");
                boolean changed = action.equals("send")
                        ? plugin.store().setSend(player.getUniqueId(), enabled)
                        : plugin.store().setReceive(player.getUniqueId(), enabled);
                tell(player, changed ? action + " を " + (enabled ? "ON" : "OFF") + " にしました。"
                        : "先に /discord link で認証してください。");
            }
            case "unlink" -> tell(player, plugin.store().unlink(player.getUniqueId())
                    ? "Discord 連携を解除しました。" : "Discord 連携は認証されていません。");
            case "status" -> {
                Account account = plugin.store().findByMinecraft(player.getUniqueId()).orElse(null);
                tell(player, account == null ? "未認証です。/discord link で認証を開始できます。"
                        : "認証済み / 送信: " + onOff(account.sendEnabled()) + " / 受信: " + onOff(account.receiveEnabled()));
            }
            default -> tell(player, "/discord <link|status|send on|off|receive on|off|unlink>");
        }
    }

    private static String onOff(boolean value) { return value ? "ON" : "OFF"; }
    private static void tell(Player player, String message) { player.sendMessage(Component.text("[Discord] " + message)); }

    @Override public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) return CompletableFuture.completedFuture(List.of("link", "status", "send", "receive", "unlink"));
        if (args.length == 2 && (args[0].equalsIgnoreCase("send") || args[0].equalsIgnoreCase("receive")))
            return CompletableFuture.completedFuture(List.of("on", "off"));
        return CompletableFuture.completedFuture(List.of());
    }
}
