package com.jubitus.travellers.client.sound;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Preloads all Travellers OGG files from the mod JAR into memory at startup.
 * SafeCodecJOrbis calls redirect() in initialize() to serve sounds from
 * ByteArrayInputStream instead of a JarInputStream — eliminating the
 * JarFile shared-inflater concurrency issue that causes CodecJOrbis header
 * read failures when the JVM class loader accesses the same JAR simultaneously.
 */
@SideOnly(Side.CLIENT)
public final class TravellerSoundCache {

    private static final Logger LOG = LogManager.getLogger("TravellerSound");
    static final Map<String, byte[]> CACHE = new HashMap<>();
    private static final String ASSET_PREFIX = "assets/travellers/sounds/";

    private TravellerSoundCache() {}

    public static void preload() {
        File source = null;
        for (ModContainer mc : Loader.instance().getModList()) {
            if ("travellers".equals(mc.getModId())) {
                source = mc.getSource();
                break;
            }
        }
        if (source == null) {
            LOG.warn("[TravellerSoundCache] Could not find mod container; sounds load from JAR (freeze risk remains)");
            return;
        }

        try {
            if (source.isFile()) {
                preloadFromJar(source);
            } else {
                // Dev environment: source is the classes/resources directory
                File soundsDir = new File(source, ASSET_PREFIX.replace("/", File.separator));
                preloadFromDir(soundsDir, "sounds/");
            }
            LOG.info("[TravellerSoundCache] Preloaded {} OGG files into memory — JAR concurrency risk eliminated", CACHE.size());
        } catch (IOException e) {
            LOG.warn("[TravellerSoundCache] Preload failed: {}. Sounds load from JAR (freeze risk remains).", e.getMessage());
        }
    }

    private static void preloadFromJar(File jarFile) throws IOException {
        try (ZipFile zip = new ZipFile(jarFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(ASSET_PREFIX) || !name.endsWith(".ogg")) continue;
                // key = "sounds/fr/gilles/ambient/file.ogg"
                String key = name.substring("assets/travellers/".length());
                try (InputStream is = zip.getInputStream(entry)) {
                    CACHE.put(key, readAllBytes(is));
                }
            }
        }
    }

    private static void preloadFromDir(File dir, String relativeKey) {
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                preloadFromDir(f, relativeKey + f.getName() + "/");
            } else if (f.getName().endsWith(".ogg")) {
                try (InputStream is = new FileInputStream(f)) {
                    CACHE.put(relativeKey + f.getName(), readAllBytes(is));
                } catch (IOException e) {
                    LOG.warn("[TravellerSoundCache] Could not preload {}: {}", f.getName(), e.getMessage());
                }
            }
        }
    }

    /**
     * If url points to a Travellers OGG that is cached, returns a memory-backed URL.
     * Otherwise returns the original url unchanged.
     */
    public static URL redirect(URL url) {
        if (url == null || CACHE.isEmpty()) return url;
        String key = extractKey(url);
        if (key == null) return url;
        byte[] data = CACHE.get(key);
        if (data == null) return url;
        try {
            return new URL(null, "travellers-mem:///" + key, new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL u) {
                    return new URLConnection(u) {
                        @Override public void connect() {}
                        @Override
                        public InputStream getInputStream() {
                            // fresh stream each call — safe for multiple reads (header + data)
                            return new ByteArrayInputStream(data);
                        }
                    };
                }
            });
        } catch (Exception e) {
            return url;
        }
    }

    private static String extractKey(URL url) {
        String s = url.toExternalForm();
        // MC 1.12.2 sound URL: "mcsounddomain:travellers:sounds/..."
        final String mcPrefix = "mcsounddomain:travellers:";
        if (s.startsWith(mcPrefix)) {
            return s.substring(mcPrefix.length()); // "sounds/travellers/en/..."
        }
        // Fallback for JAR URLs in non-standard setups
        int idx = s.indexOf(ASSET_PREFIX);
        if (idx < 0) return null;
        String key = "sounds/" + s.substring(idx + ASSET_PREFIX.length());
        int hash = key.indexOf('#');
        if (hash >= 0) key = key.substring(0, hash);
        return key;
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(32768);
        byte[] tmp = new byte[8192];
        int n;
        while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }
}
