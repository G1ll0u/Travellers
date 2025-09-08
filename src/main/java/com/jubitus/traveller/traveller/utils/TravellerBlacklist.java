package com.jubitus.traveller.traveller.utils;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;

import java.util.*;
import java.util.stream.Collectors;

public class TravellerBlacklist {

        private final Set<String> namespaceWildcards = new HashSet<>();          // "modid"
        private final Set<ResourceLocation> idSet = new HashSet<>();             // exact "modid:path"
        private final List<Class<? extends Entity>> classSet = new ArrayList<>();// exact classes

        public void reload(String[] idRules, String[] classRules) {
            namespaceWildcards.clear();
            idSet.clear();
            classSet.clear();

            // IDs & namespace wildcards
            for (String raw : idRules) {
                if (raw == null) continue;
                String s = raw.trim().toLowerCase(Locale.ROOT);
                if (s.isEmpty()) continue;

                if (s.endsWith(":*")) {
                    String ns = s.substring(0, s.length() - 2);
                    if (!ns.isEmpty()) namespaceWildcards.add(ns);
                    continue;
                }
                try {
                    ResourceLocation rl = new ResourceLocation(s);
                    idSet.add(rl);
                } catch (Exception ignored) { /* bad entry; skip */ }
            }

            // Classes
            for (String raw : classRules) {
                if (raw == null) continue;
                String name = raw.trim();
                if (name.isEmpty()) continue;

                try {
                    Class<?> c = Class.forName(name);
                    if (Entity.class.isAssignableFrom(c)) {
                        @SuppressWarnings("unchecked")
                        Class<? extends Entity> ec = (Class<? extends Entity>) c;
                        classSet.add(ec);
                    }
                } catch (Throwable ignored) { /* class not present; skip */ }
            }
        }

        public boolean isBlacklisted(Entity e) {
            // Match by namespace wildcard
            ResourceLocation key = EntityList.getKey(e);
            if (key != null) {
                if (namespaceWildcards.contains(key.getNamespace())) return true;
                if (idSet.contains(key)) return true;
            }

            // Match by class or subclass
            for (Class<? extends Entity> cls : classSet) {
                if (cls.isInstance(e)) return true;
            }
            return false;
        }

        @Override public String toString() {
            return "TravellerBlacklist{nsWildcards=" + namespaceWildcards +
                    ", ids=" + idSet.stream().map(ResourceLocation::toString).collect(Collectors.toList()) +
                    ", classes=" + classSet.stream().map(Class::getName).collect(Collectors.toList()) + "}";
        }
    }

