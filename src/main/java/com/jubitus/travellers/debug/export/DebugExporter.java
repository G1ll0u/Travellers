package com.jubitus.travellers.debug.export;

import com.jubitus.travellers.debug.DebugFiles;
import com.jubitus.travellers.debug.cmd.CultureDumpCommand;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the support bundle of `/travellersdebug export`: logs, Travellers traces and dumps, the Travellers config and an
 * info file, in one zip that never exceeds {@link #MAX_ZIP_BYTES}.
 */
public final class DebugExporter {

    /** Hard ceiling on the finished zip (under 10 MB, with a margin). */
    public static final long MAX_ZIP_BYTES = 9_800_000L;

    private static final long MB = 1024L * 1024L;
    private static final long MAIN_LOG_TAIL = 16 * MB;
    private static final long TRAVELLERS_FILE_TAIL = 2 * MB;
    private static final long TRAVELLERS_TOTAL = 24 * MB;
    private static final long CRASH_REPORT_TAIL = MB;
    private static final long CONFIG_FILE_MAX = 512 * 1024L;
    private static final long ARCHIVE_MAX = 2 * MB;
    private static final int MAX_TRAVELLERS_FILES = 60;
    private static final int MAX_CRASH_REPORTS = 2;
    private static final int MAX_ARCHIVES = 2;
    private static final long CRASH_REPORT_MAX_AGE_MS = 14L * 24 * 3600 * 1000;
    private static final double GIVE_UP_SCALE = 1.0 / 128;
    private static final Set<String> CONFIG_EXTENSIONS = new HashSet<>(Arrays.asList(
            "cfg", "properties", "json", "txt", "toml", "conf", "xml", "lang"));
    private static final Set<String> LOG_EXTENSIONS = new HashSet<>(Arrays.asList(
            "log", "txt", "csv", "tsv", "metrics", "properties"));

    private DebugExporter() {
    }

    public static final class Result {
        public final File zip;
        public final int fileCount;
        public final List<String> notes;

        Result(File zip, int fileCount, List<String> notes) {
            this.zip = zip;
            this.fileCount = fileCount;
            this.notes = notes;
        }
    }

    /** One file going into the zip; {@code tailBytes} limits it to its last bytes. */
    private static final class Source {
        final String entryName;
        final File file;
        final long tailBytes;

        Source(String entryName, File file, long tailBytes) {
            this.entryName = entryName;
            this.file = file;
            this.tailBytes = tailBytes;
        }
    }

    public static Result export(MinecraftServer server) throws IOException {
        List<String> early = new ArrayList<>();
        try {
            CultureDumpCommand.write(new File(DebugFiles.dir(DebugFiles.DUMPS), "culture_dump.txt"));
        } catch (Throwable t) {
            early.add("culture dump failed: " + t);
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        File zip = new File(DebugFiles.dir(DebugFiles.EXPORTS), "travellers-export_" + stamp + ".zip");
        for (double scale = 1.0; scale >= GIVE_UP_SCALE; scale /= 2) {
            List<String> notes = new ArrayList<>(early);
            List<Source> sources = collect(scale, notes);
            write(zip, sources, notes, server, scale);
            if (zip.length() <= MAX_ZIP_BYTES) return new Result(zip, sources.size() + 1, notes);
        }
        //noinspection ResultOfMethodCallIgnored
        zip.delete();
        throw new IOException("the logs are too large to fit in " + (MAX_ZIP_BYTES / 1_000_000) + " MB even when cut down");
    }

    // ---------------------------------------------------------------- what goes in

    private static List<Source> collect(double scale, List<String> notes) {
        File game = DebugFiles.gameDir();
        List<Source> out = new ArrayList<>();
        long mainTail = (long) (MAIN_LOG_TAIL * scale);

        addIfFile(out, "logs/latest.log", new File(game, "logs/latest.log"), mainTail);
        addIfFile(out, "logs/debug.log", new File(game, "logs/debug.log"), mainTail);

        long now = System.currentTimeMillis();
        for (File f : newestFirst(listFiles(new File(game, "crash-reports"), x -> x.getName().endsWith(".txt")), MAX_CRASH_REPORTS)) {
            if (now - f.lastModified() <= CRASH_REPORT_MAX_AGE_MS) {
                out.add(new Source("crash-reports/" + f.getName(), f, (long) (CRASH_REPORT_TAIL * scale)));
            }
        }

        if (scale >= 0.5) {
            for (File f : newestFirst(listFiles(new File(game, "logs"), x -> x.getName().endsWith(".log.gz")), MAX_ARCHIVES)) {
                if (f.length() <= ARCHIVE_MAX) out.add(new Source("logs/" + f.getName(), f, Long.MAX_VALUE));
                else notes.add("skipped " + f.getName() + " (" + f.length() / 1024 + " KB, too big)");
            }
        }

        collectTravellersLogs(out, notes, scale);
        collectConfig(out, new File(Loader.instance().getConfigDir().getAbsolutePath()), notes);
        return out;
    }

    private static void collectTravellersLogs(List<Source> out, List<String> notes, double scale) {
        File root = DebugFiles.root();
        List<File> all = new ArrayList<>();
        walk(root, all, f -> !isUnder(f, new File(root, DebugFiles.EXPORTS)) && LOG_EXTENSIONS.contains(extension(f)));
        all.sort(newestFirstOrder());
        long budget = (long) (TRAVELLERS_TOTAL * scale);
        long perFile = (long) (TRAVELLERS_FILE_TAIL * scale);
        int taken = 0;
        int skipped = 0;
        for (File f : all) {
            long size = Math.min(f.length(), perFile);
            if (taken >= MAX_TRAVELLERS_FILES || size > budget) {
                skipped++;
                continue;
            }
            budget -= size;
            taken++;
            out.add(new Source(relativeTo(root, f, "logs/travellers/"), f, perFile));
        }
        if (skipped > 0) notes.add(skipped + " older file(s) in logs/travellers left out (size or count limit)");
    }

    private static void collectConfig(List<Source> out, File configDir, List<String> notes) {
        File[] children = configDir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.getName().toLowerCase(Locale.ROOT).startsWith("travellers")) continue;
            List<File> files = new ArrayList<>();
            walk(child, files, f -> CONFIG_EXTENSIONS.contains(extension(f)));
            for (File f : files) {
                if (f.length() > CONFIG_FILE_MAX) {
                    notes.add("skipped config/" + relativeTo(configDir, f, "") + " (too big)");
                    continue;
                }
                out.add(new Source("config/" + relativeTo(configDir, f, ""), f, Long.MAX_VALUE));
            }
        }
    }

    // ---------------------------------------------------------------- zip writing

    private static void write(File zip, List<Source> sources, List<String> notes, MinecraftServer server, double scale)
            throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)))) {
            zos.setLevel(9);
            List<String> truncated = new ArrayList<>();
            byte[] info = buildInfo(server, sources, notes, scale).getBytes(StandardCharsets.UTF_8);
            zos.putNextEntry(new ZipEntry("export-info.txt"));
            zos.write(info);
            zos.closeEntry();
            for (Source s : sources) {
                try {
                    copy(zos, s, truncated);
                } catch (IOException e) {
                    truncated.add(s.entryName + " could not be read: " + e.getMessage());
                }
            }
            if (!truncated.isEmpty()) {
                zos.putNextEntry(new ZipEntry("export-truncated.txt"));
                zos.write(String.join("\n", truncated).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
    }

    private static void copy(ZipOutputStream zos, Source s, List<String> truncated) throws IOException {
        long length = s.file.length();
        long skip = Math.max(0, length - s.tailBytes);
        ZipEntry entry = new ZipEntry(s.entryName);
        entry.setTime(s.file.lastModified());
        zos.putNextEntry(entry);
        try (RandomAccessFile in = new RandomAccessFile(s.file, "r")) {
            if (skip > 0) {
                in.seek(skip);
                int b;
                while ((b = in.read()) != -1 && b != '\n') { /* resume on a whole line */ }
                zos.write(("[... first " + skip + " bytes of " + length + " left out by /travellersdebug export ...]\n")
                        .getBytes(StandardCharsets.UTF_8));
                truncated.add(s.entryName + ": kept the last " + (length - skip) + " of " + length + " bytes");
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
        }
        zos.closeEntry();
    }

    private static String buildInfo(MinecraftServer server, List<Source> sources, List<String> notes, double scale) {
        StringBuilder sb = new StringBuilder();
        sb.append("Travellers support export\n");
        sb.append("Created: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date())).append('\n');
        sb.append("Side: ").append(server.isDedicatedServer() ? "dedicated server" : "local world").append('\n');
        sb.append("Minecraft: ").append(server.getMinecraftVersion()).append('\n');
        sb.append("Java: ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor")).append(")\n");
        sb.append("OS: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
                .append(' ').append(System.getProperty("os.arch")).append('\n');
        sb.append("Max memory: ").append(Runtime.getRuntime().maxMemory() / MB).append(" MB\n");
        sb.append("Size cut level: ").append(scale == 1.0 ? "none" : "logs reduced to 1/" + Math.round(1 / scale)).append('\n');
        sb.append("\nMods:\n");
        for (ModContainer mod : Loader.instance().getActiveModList()) {
            sb.append("  ").append(mod.getModId()).append(' ').append(mod.getVersion()).append('\n');
        }
        sb.append("\nFiles in this zip:\n");
        for (Source s : sources) sb.append("  ").append(s.entryName).append('\n');
        if (!notes.isEmpty()) {
            sb.append("\nNotes:\n");
            for (String n : notes) sb.append("  ").append(n).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- file helpers

    private static void addIfFile(List<Source> out, String entry, File f, long tail) {
        if (f.isFile()) out.add(new Source(entry, f, tail));
    }

    private static List<File> listFiles(File dir, java.io.FileFilter filter) {
        File[] files = dir.listFiles(f -> f.isFile() && filter.accept(f));
        return files == null ? Collections.emptyList() : Arrays.asList(files);
    }

    private static List<File> newestFirst(List<File> files, int limit) {
        List<File> sorted = new ArrayList<>(files);
        sorted.sort(newestFirstOrder());
        return sorted.size() > limit ? sorted.subList(0, limit) : sorted;
    }

    private static Comparator<File> newestFirstOrder() {
        return (a, b) -> Long.compare(b.lastModified(), a.lastModified());
    }

    private static void walk(File dir, List<File> out, java.io.FileFilter keep) {
        File[] children = dir.listFiles();
        if (children == null) {
            if (dir.isFile() && keep.accept(dir)) out.add(dir);
            return;
        }
        for (File c : children) {
            if (c.isDirectory()) walk(c, out, keep);
            else if (keep.accept(c)) out.add(c);
        }
    }

    private static boolean isUnder(File f, File dir) {
        return f.getAbsolutePath().startsWith(dir.getAbsolutePath() + File.separator);
    }

    private static String extension(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String relativeTo(File root, File f, String prefix) {
        String rel = root.toURI().relativize(f.toURI()).getPath();
        return prefix + rel;
    }
}
