package jp.kamei.worldstatesync.model;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StateCodecTest {
    private final StateCodec codec = new StateCodec();

    @Test
    void roundTripsStateWithoutItemsOrEffects() throws Exception {
        PlayerState original = new PlayerState(
                new ItemStack[41],
                new ItemStack[27],
                17,
                0.625F,
                493,
                12,
                3.5F,
                1.25F,
                16.75D,
                4.0D,
                List.of()
        );

        PlayerState decoded = codec.decode(codec.encode(original));

        assertEquals(41, decoded.inventoryContents().length);
        assertEquals(27, decoded.enderChestContents().length);
        assertEquals(original.level(), decoded.level());
        assertEquals(original.experienceProgress(), decoded.experienceProgress());
        assertEquals(original.totalExperience(), decoded.totalExperience());
        assertEquals(original.foodLevel(), decoded.foodLevel());
        assertEquals(original.saturation(), decoded.saturation());
        assertEquals(original.exhaustion(), decoded.exhaustion());
        assertEquals(original.health(), decoded.health());
        assertEquals(original.absorptionAmount(), decoded.absorptionAmount());
        assertEquals(original.potionEffects(), decoded.potionEffects());
    }

    @Test
    void rejectsAnUnreasonableInventorySize() {
        PlayerState invalid = new PlayerState(
                new ItemStack[129],
                new ItemStack[27],
                0,
                0.0F,
                0,
                20,
                5.0F,
                0.0F,
                20.0D,
                0.0D,
                List.of()
        );

        assertThrows(IOException.class, () -> codec.encode(invalid));
    }
}
