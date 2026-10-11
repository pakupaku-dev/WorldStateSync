package jp.kamei.worldstatesync.model;

import com.google.gson.Gson;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.*;

/** Read-only projection captured on the server thread; never used to restore state. */
public final class PortalSnapshot {
    private static final Gson JSON = new Gson();
    private static NamespacedKey placedKey(String server) {
        return new NamespacedKey("worldstatesync", "placed_" + UUID.nameUUIDFromBytes(server.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    public static void recordPlacement(Player player, Material material, String server) {
        var key = placedKey(server);
        String raw = player.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        var counts = raw == null ? new com.google.gson.JsonObject() : com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
        String name = material.getKey().toString();
        counts.addProperty(name, counts.has(name) ? counts.get(name).getAsLong() + 1 : 1L);
        player.getPersistentDataContainer().set(key, PersistentDataType.STRING, counts.toString());
    }
    public static String capture(Player player, String server) {
        PlayerState state = PlayerState.capture(player);
        Map<String, Object> out = stateValues(state);
        out.put("health", player.getHealth());
        Map<String, Object> stats = new TreeMap<>();
        for (Statistic stat : Statistic.values()) {
            Map<String, Integer> values = new TreeMap<>();
            if (stat.getType() == Statistic.Type.UNTYPED) {
                stats.put(stat.name().toLowerCase(Locale.ROOT), player.getStatistic(stat));
            } else if (stat.getType() == Statistic.Type.ENTITY) {
                for (EntityType type : EntityType.values()) {
                    if (type == EntityType.UNKNOWN) continue;
                    try { int n = player.getStatistic(stat, type); if (n > 0) values.put(type.getKey().toString(), n); }
                    catch (IllegalArgumentException ignored) { }
                }
                stats.put(stat.name().toLowerCase(Locale.ROOT), values);
            } else {
                for (Material type : Material.values()) {
                    if (type.isLegacy() || (stat.getType() == Statistic.Type.BLOCK ? !type.isBlock() : !type.isItem())) continue;
                    try { int n = player.getStatistic(stat, type); if (n > 0) values.put(type.getKey().toString(), n); }
                    catch (IllegalArgumentException ignored) { }
                }
                stats.put(stat.name().toLowerCase(Locale.ROOT), values);
            }
        }
        String placed = player.getPersistentDataContainer().get(placedKey(server), PersistentDataType.STRING);
        stats.put("placed_blocks", placed == null ? Map.of() : com.google.gson.JsonParser.parseString(placed));
        out.put("statistics", stats);
        return JSON.toJson(out);
    }
    public static String fromState(PlayerState state) { return JSON.toJson(stateValues(state)); }
    private static Map<String, Object> stateValues(PlayerState state) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inventory", items(state.inventoryContents()));
        out.put("ender_chest", items(state.enderChestContents()));
        out.put("level", state.level()); out.put("experience", state.totalExperience());
        out.put("experience_progress", state.experienceProgress());
        out.put("health", state.health()); out.put("food", state.foodLevel());
        out.put("saturation", state.saturation()); out.put("absorption", state.absorptionAmount());
        out.put("effects", state.potionEffects().stream().map(e -> Map.of("type", e.getType().getKey().toString(), "amplifier", e.getAmplifier(), "ticks", e.getDuration())).toList());
        return out;
    }
    private static List<Object> items(ItemStack[] contents) {
        List<Object> result = new ArrayList<>();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) { result.add(null); continue; }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slot", slot); row.put("type", item.getType().getKey().toString()); row.put("amount", item.getAmount());
            var meta = item.getItemMeta();
            if (meta.hasDisplayName()) row.put("name", PlainTextComponentSerializer.plainText().serialize(meta.displayName()));
            if (meta.hasLore()) row.put("lore", meta.lore().stream().map(PlainTextComponentSerializer.plainText()::serialize).toList());
            if (meta instanceof Damageable damage) { row.put("damage", damage.getDamage()); row.put("max_durability", item.getType().getMaxDurability()); }
            Map<String, Integer> enchants = new TreeMap<>();
            item.getEnchantments().forEach((e, level) -> enchants.put(e.getKey().toString(), level));
            if (meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta book) book.getStoredEnchants().forEach((e, level) -> enchants.put(e.getKey().toString(), level));
            row.put("enchantments", enchants); result.add(row);
        }
        return result;
    }
}
