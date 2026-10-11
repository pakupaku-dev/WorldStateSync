package jp.kamei.worldstatesync.config;

import org.bukkit.World;

import java.util.Map;

public final class ScopeResolver {
    private final Map<String, String> mappings;

    public ScopeResolver(Map<String, String> mappings) {
        this.mappings = mappings;
    }

    public String resolve(World world) {
        return mappings.getOrDefault(world.getName(), world.getName());
    }
}
