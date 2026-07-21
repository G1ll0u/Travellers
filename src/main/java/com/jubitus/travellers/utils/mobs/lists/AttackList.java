package com.jubitus.travellers.utils.mobs.lists;

// Generic attack-list helper, reusable for raiders, normal travellers, etc.
public class AttackList {

    private final java.util.Set<String> namespaceWildcards = new java.util.HashSet<>();
    private final java.util.Set<net.minecraft.util.ResourceLocation> idSet = new java.util.HashSet<>();
    private final java.util.List<Class<? extends net.minecraft.entity.Entity>> classSet = new java.util.ArrayList<>();

    public void reload(String[] idRules, String[] classRules) {
        namespaceWildcards.clear();
        idSet.clear();
        classSet.clear();

        // IDs & namespace wildcards
        for (String raw : idRules) {
            if (raw == null) continue;
            String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (s.isEmpty()) continue;

            if (s.endsWith(":*")) {
                String ns = s.substring(0, s.length() - 2);
                if (!ns.isEmpty()) namespaceWildcards.add(ns);
                continue;
            }
            try {
                idSet.add(new net.minecraft.util.ResourceLocation(s));
            } catch (Exception ignored) {
                // bad entry; skip
            }
        }

        // Classes
        for (String raw : classRules) {
            if (raw == null) continue;
            String name = raw.trim();
            if (name.isEmpty()) continue;

            try {
                Class<?> c = Class.forName(name);
                if (net.minecraft.entity.Entity.class.isAssignableFrom(c)) {
                    @SuppressWarnings("unchecked")
                    Class<? extends net.minecraft.entity.Entity> ec =
                            (Class<? extends net.minecraft.entity.Entity>) c;
                    classSet.add(ec);
                }
            } catch (Throwable ignored) {
                // class not present / bad name; skip
            }
        }
    }

    /**
     * True if any rule was configured (id, namespace wildcard, or class) — lets a caller
     * fall back to a different list when this one was never actually configured.
     */
    public boolean hasRules() {
        return !namespaceWildcards.isEmpty() || !idSet.isEmpty() || !classSet.isEmpty();
    }

    /**
     * True if this concrete entity is listed (by ID, namespace wildcard, or class).
     */
    public boolean isListed(net.minecraft.entity.Entity e) {
        // ID matching
        net.minecraft.util.ResourceLocation key = net.minecraft.entity.EntityList.getKey(e);
        if (key != null) {
            if (namespaceWildcards.contains(key.getNamespace())) return true;
            if (idSet.contains(key)) return true;
        }

        // Class/subclass matching
        for (Class<? extends net.minecraft.entity.Entity> cls : classSet) {
            if (cls.isInstance(e)) return true;
        }
        return false;
    }
}
