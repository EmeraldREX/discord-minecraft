package dev.emeraldrex.discordminecraft.store;

import java.util.UUID;

public record Account(UUID minecraftId, String minecraftName, long discordId,
                      boolean sendEnabled, boolean receiveEnabled) {
}
