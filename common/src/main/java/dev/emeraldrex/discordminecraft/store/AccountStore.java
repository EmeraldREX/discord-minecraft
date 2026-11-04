package dev.emeraldrex.discordminecraft.store;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class AccountStore implements AutoCloseable {
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private final Connection connection;
    private final Clock clock;

    public AccountStore(Path database, Clock clock) throws SQLException {
        this.clock = clock;
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        initialize();
    }

    private void initialize() throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    minecraft_uuid TEXT PRIMARY KEY,
                    minecraft_name TEXT NOT NULL,
                    discord_id INTEGER NOT NULL UNIQUE,
                    send_enabled INTEGER NOT NULL DEFAULT 0,
                    receive_enabled INTEGER NOT NULL DEFAULT 0,
                    linked_at INTEGER NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS link_codes (
                    code TEXT PRIMARY KEY,
                    minecraft_uuid TEXT NOT NULL UNIQUE,
                    minecraft_name TEXT NOT NULL,
                    expires_at INTEGER NOT NULL
                )
                """);
        }
    }

    public synchronized String createLinkCode(UUID minecraftId, String name, Duration lifetime) throws SQLException {
        purgeExpiredCodes();
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM link_codes WHERE minecraft_uuid=?")) {
            delete.setString(1, minecraftId.toString());
            delete.executeUpdate();
        }
        for (int attempt = 0; attempt < 20; attempt++) {
            String code = randomCode();
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO link_codes(code,minecraft_uuid,minecraft_name,expires_at) VALUES(?,?,?,?)")) {
                insert.setString(1, code);
                insert.setString(2, minecraftId.toString());
                insert.setString(3, name);
                insert.setLong(4, clock.millis() + lifetime.toMillis());
                insert.executeUpdate();
                return code;
            } catch (SQLException exception) {
                if (!exception.getMessage().contains("UNIQUE")) throw exception;
            }
        }
        throw new SQLException("Could not allocate a unique link code");
    }

    public synchronized Optional<Account> claimLinkCode(String rawCode, long discordId) throws SQLException {
        purgeExpiredCodes();
        String code = rawCode.trim().toUpperCase();
        connection.setAutoCommit(false);
        try {
            UUID minecraftId;
            String minecraftName;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT minecraft_uuid,minecraft_name FROM link_codes WHERE code=?")) {
                query.setString(1, code);
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) {
                        connection.rollback();
                        return Optional.empty();
                    }
                    minecraftId = UUID.fromString(result.getString(1));
                    minecraftName = result.getString(2);
                }
            }
            try (PreparedStatement conflicts = connection.prepareStatement(
                    "DELETE FROM accounts WHERE minecraft_uuid=? OR discord_id=?")) {
                conflicts.setString(1, minecraftId.toString());
                conflicts.setLong(2, discordId);
                conflicts.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO accounts(minecraft_uuid,minecraft_name,discord_id,send_enabled,receive_enabled,linked_at)
                    VALUES(?,?,?,0,0,?)
                    """)) {
                insert.setString(1, minecraftId.toString());
                insert.setString(2, minecraftName);
                insert.setLong(3, discordId);
                insert.setLong(4, clock.millis());
                insert.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM link_codes WHERE code=?")) {
                delete.setString(1, code);
                delete.executeUpdate();
            }
            connection.commit();
            return findByMinecraft(minecraftId);
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public synchronized Optional<Account> findByMinecraft(UUID id) throws SQLException {
        return find("SELECT * FROM accounts WHERE minecraft_uuid=?", statement -> statement.setString(1, id.toString()));
    }

    public synchronized Optional<Account> findByDiscord(long id) throws SQLException {
        return find("SELECT * FROM accounts WHERE discord_id=?", statement -> statement.setLong(1, id));
    }

    public synchronized List<Account> receiveEnabledAccounts() throws SQLException {
        List<Account> accounts = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM accounts WHERE receive_enabled=1");
             ResultSet result = query.executeQuery()) {
            while (result.next()) accounts.add(readAccount(result));
        }
        return accounts;
    }

    public synchronized boolean setSend(UUID id, boolean enabled) throws SQLException {
        return updateToggle("send_enabled", id, enabled);
    }

    public synchronized boolean setReceive(UUID id, boolean enabled) throws SQLException {
        return updateToggle("receive_enabled", id, enabled);
    }

    public synchronized boolean unlink(UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM accounts WHERE minecraft_uuid=?")) {
            statement.setString(1, id.toString());
            return statement.executeUpdate() == 1;
        }
    }

    private boolean updateToggle(String column, UUID id, boolean enabled) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET " + column + "=? WHERE minecraft_uuid=?")) {
            statement.setBoolean(1, enabled);
            statement.setString(2, id.toString());
            return statement.executeUpdate() == 1;
        }
    }

    private Optional<Account> find(String sql, SqlBinder binder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readAccount(result)) : Optional.empty();
            }
        }
    }

    private Account readAccount(ResultSet result) throws SQLException {
        return new Account(UUID.fromString(result.getString("minecraft_uuid")),
                result.getString("minecraft_name"), result.getLong("discord_id"),
                result.getBoolean("send_enabled"), result.getBoolean("receive_enabled"));
    }

    private void purgeExpiredCodes() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM link_codes WHERE expires_at<=?")) {
            statement.setLong(1, clock.millis());
            statement.executeUpdate();
        }
    }

    private String randomCode() {
        StringBuilder value = new StringBuilder(8);
        for (int i = 0; i < 8; i++) value.append(ALPHABET.charAt(ThreadLocalRandom.current().nextInt(ALPHABET.length())));
        return value.toString();
    }

    @Override public synchronized void close() throws SQLException { connection.close(); }
    @FunctionalInterface private interface SqlBinder { void bind(PreparedStatement statement) throws SQLException; }
}
