package jp.kamei.worldstatesync.model;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.io.BukkitObjectInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class StateCodec {
    private static final int MAGIC = 0x57535331;
    private static final int FORMAT_VERSION = 3;
    private static final int MAX_COMPRESSED_BYTES = 16 * 1024 * 1024;
    private static final long MAX_UNCOMPRESSED_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_ITEM_BYTES = 16 * 1024 * 1024;
    private static final int MAX_INVENTORY_SLOTS = 128;
    private static final int MAX_EFFECTS = 256;
    private static final int MAX_HIDDEN_EFFECT_DEPTH = 16;

    public byte[] encode(PlayerState state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes);
             DataOutputStream output = new DataOutputStream(gzip)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            writeItemArray(output, state.inventoryContents());
            writeItemArray(output, state.enderChestContents());

            output.writeInt(state.level());
            output.writeFloat(state.experienceProgress());
            output.writeInt(state.totalExperience());
            output.writeInt(state.foodLevel());
            output.writeFloat(state.saturation());
            output.writeFloat(state.exhaustion());
            output.writeDouble(state.health());
            output.writeDouble(state.absorptionAmount());

            if (state.potionEffects().size() > MAX_EFFECTS) {
                throw new IOException("Too many potion effects: " + state.potionEffects().size());
            }
            output.writeInt(state.potionEffects().size());
            for (PotionEffect effect : state.potionEffects()) {
                writePotionEffect(output, effect, 0);
            }
        }

        byte[] payload = bytes.toByteArray();
        if (payload.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("Compressed player state is too large: " + payload.length);
        }
        return payload;
    }

    public PlayerState decode(byte[] payload) throws IOException, ClassNotFoundException {
        if (payload.length == 0 || payload.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("Invalid compressed payload length: " + payload.length);
        }

        try (ByteArrayInputStream bytes = new ByteArrayInputStream(payload);
             GZIPInputStream gzip = new GZIPInputStream(bytes);
             LimitedInputStream limited = new LimitedInputStream(gzip, MAX_UNCOMPRESSED_BYTES);
             BufferedInputStream buffered = new BufferedInputStream(limited)) {
            buffered.mark(8);
            int firstWord = new DataInputStream(buffered).readInt();
            buffered.reset();
            if (firstWord == MAGIC) {
                return decodeCurrent(new DataInputStream(buffered));
            }
            return decodeLegacy(buffered);
        }
    }

    private PlayerState decodeCurrent(DataInputStream input) throws IOException {
        int magic = input.readInt();
        if (magic != MAGIC) {
            throw new IOException("Invalid player state magic");
        }
        int version = input.readInt();
        if (version != FORMAT_VERSION) {
            throw new IOException("Unsupported player state format: " + version);
        }

        ItemStack[] contents = readItemArray(input, "inventory");
        ItemStack[] enderChestContents = readItemArray(input, "ender chest");
        int level = input.readInt();
        float experienceProgress = input.readFloat();
        int totalExperience = input.readInt();
        int foodLevel = input.readInt();
        float saturation = input.readFloat();
        float exhaustion = input.readFloat();
        double health = input.readDouble();
        double absorptionAmount = input.readDouble();

        int effectCount = checkedCount("effects", input.readInt(), MAX_EFFECTS);
        List<PotionEffect> effects = new ArrayList<>(effectCount);
        for (int index = 0; index < effectCount; index++) {
            effects.add(readPotionEffect(input, 0));
        }

        return new PlayerState(
                contents,
                enderChestContents,
                level,
                experienceProgress,
                totalExperience,
                foodLevel,
                saturation,
                exhaustion,
                health,
                absorptionAmount,
                effects
        );
    }

    @SuppressWarnings("deprecation")
    private PlayerState decodeLegacy(InputStream inputStream) throws IOException, ClassNotFoundException {
        try (BukkitObjectInputStream input = new BukkitObjectInputStream(inputStream)) {
            int magic = input.readInt();
            if (magic != MAGIC) {
                throw new IOException("Invalid legacy player state magic");
            }
            int version = input.readInt();
            if (version < 1 || version > 2) {
                throw new IOException("Unsupported legacy player state format: " + version);
            }

            int inventoryLength = checkedCount("inventory", input.readInt(), MAX_INVENTORY_SLOTS);
            ItemStack[] contents = new ItemStack[inventoryLength];
            for (int index = 0; index < inventoryLength; index++) {
                Object value = input.readObject();
                if (value != null && !(value instanceof ItemStack)) {
                    throw new IOException("Invalid inventory entry at slot " + index);
                }
                contents[index] = (ItemStack) value;
            }

            ItemStack[] enderChestContents;
            if (version >= 2) {
                int enderChestLength = checkedCount("ender chest", input.readInt(), MAX_INVENTORY_SLOTS);
                enderChestContents = new ItemStack[enderChestLength];
                for (int index = 0; index < enderChestLength; index++) {
                    Object value = input.readObject();
                    if (value != null && !(value instanceof ItemStack)) {
                        throw new IOException("Invalid ender chest entry at slot " + index);
                    }
                    enderChestContents[index] = (ItemStack) value;
                }
            } else {
                enderChestContents = new ItemStack[27];
            }

            int level = input.readInt();
            float experienceProgress = input.readFloat();
            int totalExperience = input.readInt();
            int foodLevel = input.readInt();
            float saturation = input.readFloat();
            float exhaustion = input.readFloat();
            double health = version >= 2 ? input.readDouble() : 20.0D;
            double absorptionAmount = version >= 2 ? input.readDouble() : 0.0D;

            int effectCount = checkedCount("effects", input.readInt(), MAX_EFFECTS);
            List<PotionEffect> effects = new ArrayList<>(effectCount);
            for (int index = 0; index < effectCount; index++) {
                Object value = input.readObject();
                if (!(value instanceof PotionEffect effect)) {
                    throw new IOException("Invalid potion effect entry at index " + index);
                }
                effects.add(effect);
            }

            return new PlayerState(
                    contents,
                    enderChestContents,
                    level,
                    experienceProgress,
                    totalExperience,
                    foodLevel,
                    saturation,
                    exhaustion,
                    health,
                    absorptionAmount,
                    effects
            );
        }
    }

    private static void writeItemArray(DataOutputStream output, ItemStack[] items) throws IOException {
        if (items.length > MAX_INVENTORY_SLOTS) {
            throw new IOException("Too many inventory slots: " + items.length);
        }
        output.writeInt(items.length);
        for (ItemStack item : items) {
            output.writeBoolean(item != null);
            if (item == null) {
                continue;
            }
            byte[] itemBytes = item.serializeAsBytes();
            if (itemBytes.length > MAX_ITEM_BYTES) {
                throw new IOException("Serialized item is too large: " + itemBytes.length);
            }
            output.writeInt(itemBytes.length);
            output.write(itemBytes);
        }
    }

    private static ItemStack[] readItemArray(DataInputStream input, String name) throws IOException {
        int length = checkedCount(name, input.readInt(), MAX_INVENTORY_SLOTS);
        ItemStack[] items = new ItemStack[length];
        for (int index = 0; index < length; index++) {
            if (!input.readBoolean()) {
                continue;
            }
            int itemLength = checkedCount(name + " item bytes", input.readInt(), MAX_ITEM_BYTES);
            byte[] itemBytes = new byte[itemLength];
            input.readFully(itemBytes);
            items[index] = ItemStack.deserializeBytes(itemBytes);
        }
        return items;
    }

    private static void writePotionEffect(DataOutputStream output, PotionEffect effect, int depth) throws IOException {
        if (depth > MAX_HIDDEN_EFFECT_DEPTH) {
            throw new IOException("Potion effect hidden chain is too deep");
        }
        output.writeUTF(effect.getType().getKey().toString());
        output.writeInt(effect.getDuration());
        output.writeInt(effect.getAmplifier());
        output.writeBoolean(effect.isAmbient());
        output.writeBoolean(effect.hasParticles());
        output.writeBoolean(effect.hasIcon());
        PotionEffect hidden = effect.getHiddenPotionEffect();
        output.writeBoolean(hidden != null);
        if (hidden != null) {
            writePotionEffect(output, hidden, depth + 1);
        }
    }

    private static PotionEffect readPotionEffect(DataInputStream input, int depth) throws IOException {
        if (depth > MAX_HIDDEN_EFFECT_DEPTH) {
            throw new IOException("Potion effect hidden chain is too deep");
        }
        String typeName = input.readUTF();
        NamespacedKey key = NamespacedKey.fromString(typeName);
        PotionEffectType type = key == null ? null : Registry.POTION_EFFECT_TYPE.get(key);
        if (type == null) {
            throw new IOException("Unknown potion effect type: " + typeName);
        }
        int duration = input.readInt();
        int amplifier = input.readInt();
        boolean ambient = input.readBoolean();
        boolean particles = input.readBoolean();
        boolean icon = input.readBoolean();
        PotionEffect hidden = input.readBoolean() ? readPotionEffect(input, depth + 1) : null;
        return new PotionEffect(type, duration, amplifier, ambient, particles, icon, hidden);
    }

    private static int checkedCount(String name, int count, int maximum) throws IOException {
        if (count < 0 || count > maximum) {
            throw new IOException("Invalid " + name + " count: " + count);
        }
        return count;
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long maximum;
        private long count;

        private LimitedInputStream(InputStream input, long maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                addCount(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                addCount(read);
            }
            return read;
        }

        private void addCount(int added) throws IOException {
            count += added;
            if (count > maximum) {
                throw new IOException("Uncompressed player state exceeds " + maximum + " bytes");
            }
        }
    }
}
