package es.mrdino.perplayerprofiles.state;

import java.util.Collections;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerStateServiceTest {
    @Test void unavailableRespawnChunkPreservesLegacyPointAndSavesTheRestOfTheProfile() {
        Player player = mockPlayerForCapture();
        when(player.getBedSpawnLocation()).thenThrow(new IllegalStateException("Unloaded chunk"));
        Location previousRespawn = new Location(null, 1000, 64, -1000);
        YamlConfiguration previous = new YamlConfiguration();
        previous.set("bed-spawn", previousRespawn);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::advancementIterator).thenReturn(Collections.emptyIterator());
            bukkit.when(Bukkit::getLogger).thenReturn(mock(Logger.class));
            YamlConfiguration snapshot = new PlayerStateService().capture(player, () -> previous);

            assertEquals(previousRespawn, snapshot.getLocation("respawn-location"));
            assertEquals(previousRespawn, snapshot.getLocation("bed-spawn"));
            assertEquals(8, snapshot.getInt("level"));
            verify(player).getBedSpawnLocation();
        }
    }

    @Test void clearingTheRespawnPointDoesNotReuseThePreviousPoint() {
        Player player = mockPlayerForCapture();
        Supplier<YamlConfiguration> previousState = () -> fail("A successful lookup needs no previous snapshot");

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::advancementIterator).thenReturn(Collections.emptyIterator());
            YamlConfiguration snapshot = new PlayerStateService().capture(player, previousState);

            assertFalse(snapshot.contains("respawn-location"));
            assertFalse(snapshot.contains("bed-spawn"));
            verify(player).getBedSpawnLocation();
        }
    }

    private Player mockPlayerForCapture() {
        Player player = mock(Player.class);
        when(player.getInventory()).thenReturn(mock(PlayerInventory.class));
        when(player.getEnderChest()).thenReturn(mock(Inventory.class));
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getLevel()).thenReturn(8);
        return player;
    }

    @Test void unsupportedProgressImplementationFailsBeforeChangingInventoryOrPlayerState() {
        Player player = mock(Player.class);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new PlayerStateService().apply(player, new YamlConfiguration()));
        assertTrue(failure.getMessage().contains("silent profile progress"));
        verifyNoInteractions(player);
    }
}
