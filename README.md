# DiscordMinecraft

Velocity 上の LunaChat と Discord を、Minecraft アカウント認証付きで双方向接続するプラグインです。Velocity が全バックエンドの LunaChat 発言を集約するため、Paper ごとに本プラグインを追加する必要はありません。既存の LunaChat と SimpleAnnounce は変更しません。

## 要件

- Java 25
- Velocity 4.3.0
- Paper 26.3 バックエンド
- LunaChat 3.0.20 を Velocity と各 Paper に導入済み
- Discord Bot Token、Guild ID、連携用 Text Channel ID

## ビルド

```bash
./gradlew clean build
```

生成物は `velocity/build/libs/velocity-0.1.1.jar` です。Velocity の `plugins/` に配置してください。`DISCORD_BOT_TOKEN`、`DISCORD_GUILD_ID`、`DISCORD_CHANNEL_ID` 環境変数を推奨し、未設定の項目だけ `plugins/discordminecraft/config.yml` の値を使います。Bot には連携チャンネルの View Channel、Send Messages、Read Message History 権限と Message Content Intent が必要です。

## 利用方法

1. Minecraft で `/discord link` を実行します。
2. Discord で `/minecraft link code:<表示されたコード>` を実行します。
3. 認証直後は送信・受信ともに OFF です。
4. `/discord send on` と `/discord receive on` で必要な方向だけ有効化します。

`/discord status` で状態確認、`/discord unlink` で解除できます。未認証ユーザーの発言はどちらの方向にも中継されません。Discord からの発言者は Minecraft 側の送信トグル、受信者は受信トグルで個別に制御されます。
