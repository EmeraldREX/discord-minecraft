package dev.emeraldrex.discordminecraft.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AccountStoreTest {
    @TempDir Path temp;

    @Test void linkingStartsWithBothDirectionsDisabledAndTogglesRemainIndependent() throws Exception {
        UUID player = UUID.randomUUID();
        try (AccountStore store = new AccountStore(temp.resolve("test.db"), Clock.systemUTC())) {
            String code = store.createLinkCode(player, "Alex", Duration.ofMinutes(10));
            Account linked = store.claimLinkCode(code.toLowerCase(), 42L).orElseThrow();
            assertFalse(linked.sendEnabled());
            assertFalse(linked.receiveEnabled());
            assertTrue(store.setSend(player, true));
            Account updated = store.findByMinecraft(player).orElseThrow();
            assertTrue(updated.sendEnabled());
            assertFalse(updated.receiveEnabled());
            assertTrue(store.receiveEnabledAccounts().isEmpty());
        }
    }

    @Test void expiredAndSingleUseCodesCannotAuthenticate() throws Exception {
        Clock fixed = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        try (AccountStore store = new AccountStore(temp.resolve("test.db"), fixed)) {
            String expired = store.createLinkCode(UUID.randomUUID(), "Expired", Duration.ZERO);
            assertTrue(store.claimLinkCode(expired, 1L).isEmpty());
            String valid = store.createLinkCode(UUID.randomUUID(), "Valid", Duration.ofMinutes(1));
            assertTrue(store.claimLinkCode(valid, 2L).isPresent());
            assertTrue(store.claimLinkCode(valid, 3L).isEmpty());
        }
    }

    @Test void relinkingAlwaysResetsBothDirectionsToDisabled() throws Exception {
        UUID player = UUID.randomUUID();
        try (AccountStore store = new AccountStore(temp.resolve("test.db"), Clock.systemUTC())) {
            String firstCode = store.createLinkCode(player, "Alex", Duration.ofMinutes(10));
            store.claimLinkCode(firstCode, 42L).orElseThrow();
            store.setSend(player, true);
            store.setReceive(player, true);

            String secondCode = store.createLinkCode(player, "Alex", Duration.ofMinutes(10));
            Account relinked = store.claimLinkCode(secondCode, 42L).orElseThrow();
            assertFalse(relinked.sendEnabled());
            assertFalse(relinked.receiveEnabled());
        }
    }

    @Test void networkBanSurvivesAccountUnlinkUntilExplicitlyPardoned() throws Exception {
        UUID player = UUID.randomUUID();
        try (AccountStore store = new AccountStore(temp.resolve("test.db"), Clock.systemUTC())) {
            String code = store.createLinkCode(player, "Alex", Duration.ofMinutes(10));
            store.claimLinkCode(code, 42L).orElseThrow();
            store.ban(player, "Alex", "Discord ban", "discord");
            assertEquals("Discord ban", store.banReason(player).orElseThrow());
            store.unlink(player);
            assertEquals("Discord ban", store.banReason(player).orElseThrow());
            assertTrue(store.unban(player));
            assertTrue(store.banReason(player).isEmpty());
        }
    }
}
