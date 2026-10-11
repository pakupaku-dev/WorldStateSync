package jp.kamei.worldstatesync.model;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;

public record PlayerState(
        ItemStack[] inventoryContents,
        ItemStack[] enderChestContents,
        int level,
        float experienceProgress,
        int totalExperience,
        int foodLevel,
        float saturation,
        float exhaustion,
        double health,
        double absorptionAmount,
        List<PotionEffect> potionEffects
) {
    public static PlayerState capture(Player player) {
        ItemStack[] source = player.getInventory().getContents();
        ItemStack[] contents = new ItemStack[source.length];
        for (int index = 0; index < source.length; index++) {
            contents[index] = source[index] == null ? null : source[index].clone();
        }
        ItemStack[] enderChest = cloneContents(player.getEnderChest().getContents());
        double maxHealth = maximumHealth(player);
        double health = player.isDead() || player.getHealth() <= 0.0D ? maxHealth : player.getHealth();

        return new PlayerState(
                contents,
                enderChest,
                player.getLevel(),
                player.getExp(),
                player.getTotalExperience(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getExhaustion(),
                health,
                Math.max(0.0D, player.getAbsorptionAmount()),
                new ArrayList<>(player.getActivePotionEffects())
        );
    }

    public static PlayerState empty(Player player) {
        return new PlayerState(
                new ItemStack[player.getInventory().getContents().length],
                new ItemStack[player.getEnderChest().getContents().length],
                0,
                0.0F,
                0,
                20,
                5.0F,
                0.0F,
                maximumHealth(player),
                0.0D,
                List.of()
        );
    }

    public void apply(Player player) {
        int expectedLength = player.getInventory().getContents().length;
        if (inventoryContents.length != expectedLength) {
            throw new IllegalStateException(
                    "Inventory size mismatch: saved=" + inventoryContents.length + ", current=" + expectedLength
            );
        }
        int expectedEnderChestLength = player.getEnderChest().getContents().length;
        if (enderChestContents.length != expectedEnderChestLength) {
            throw new IllegalStateException(
                    "Ender chest size mismatch: saved=" + enderChestContents.length
                            + ", current=" + expectedEnderChestLength
            );
        }

        player.getInventory().setContents(cloneContents(inventoryContents));
        player.getEnderChest().setContents(cloneContents(enderChestContents));
        player.setLevel(level);
        player.setExp(experienceProgress);
        player.setTotalExperience(totalExperience);
        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
        player.setExhaustion(exhaustion);

        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        player.addPotionEffects(potionEffects);

        double maxHealth = maximumHealth(player);
        player.setHealth(Math.max(0.1D, Math.min(health, maxHealth)));
        player.setAbsorptionAmount(Math.max(0.0D, Math.min(absorptionAmount, 2_048.0D)));
    }

    public static void clear(Player player) {
        player.getInventory().setContents(new ItemStack[player.getInventory().getContents().length]);
        player.getEnderChest().setContents(new ItemStack[player.getEnderChest().getContents().length]);
        player.setLevel(0);
        player.setExp(0.0F);
        player.setTotalExperience(0);
        player.setFoodLevel(20);
        player.setSaturation(5.0F);
        player.setExhaustion(0.0F);
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        player.setHealth(maximumHealth(player));
        player.setAbsorptionAmount(0.0D);
    }

    private static ItemStack[] cloneContents(ItemStack[] source) {
        ItemStack[] clone = new ItemStack[source.length];
        for (int index = 0; index < source.length; index++) {
            clone[index] = source[index] == null ? null : source[index].clone();
        }
        return clone;
    }

    private static double maximumHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null || !Double.isFinite(attribute.getValue()) || attribute.getValue() <= 0.0D) {
            return 20.0D;
        }
        return attribute.getValue();
    }
}
