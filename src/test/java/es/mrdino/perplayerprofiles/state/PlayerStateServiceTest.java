package es.mrdino.perplayerprofiles.state;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerStateServiceTest {
    @Test void unsupportedProgressImplementationFailsBeforeChangingInventoryOrPlayerState() {
        Player player = mock(Player.class);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new PlayerStateService().apply(player, new YamlConfiguration()));
        assertTrue(failure.getMessage().contains("silent profile progress"));
        verifyNoInteractions(player);
    }
}
