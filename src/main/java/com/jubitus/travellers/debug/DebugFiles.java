package com.jubitus.travellers.debug;

import net.minecraftforge.fml.common.Loader;

import java.io.File;

/** The one place that decides where the mod writes diagnostic files: {@code <game dir>/logs/travellers/<topic>/}. */
public final class DebugFiles {

    public static final String TRACE = "trace";
    public static final String PATHS = "paths";
    public static final String PROFILE = "profile";
    public static final String DESTINATION = "destination";
    public static final String STABLE = "stable";
    public static final String TERRAIN = "terrain";
    public static final String AUTOTEST = "autotest";
    public static final String DIALOGUE = "dialogue";
    public static final String DUMPS = "dumps";
    public static final String EXPORTS = "exports";

    /** Shown to players in messages and config comments. */
    public static final String ROOT_NAME = "logs/travellers";

    private DebugFiles() {
    }

    public static File gameDir() {
        File config = Loader.instance().getConfigDir();
        File parent = config == null ? null : config.getAbsoluteFile().getParentFile();
        return parent != null ? parent : new File(".").getAbsoluteFile();
    }

    public static File root() {
        return new File(gameDir(), ROOT_NAME);
    }

    /** {@code logs/travellers/<topic>}, created on demand. */
    public static File dir(String topic) {
        File dir = new File(root(), topic);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** A file directly in {@code logs/travellers}, its folder created on demand. */
    public static File file(String name) {
        File root = root();
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        return new File(root, name);
    }
}
