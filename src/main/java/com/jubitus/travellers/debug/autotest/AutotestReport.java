package com.jubitus.travellers.debug.autotest;

import net.minecraftforge.fml.common.FMLLog;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/** One report file per server session in logs/travellers/autotest/; every autotest check appends to it. */
public final class AutotestReport {

    private static File file;

    private AutotestReport() {
    }

    public static File file() {
        if (file == null) {
            File dir = com.jubitus.travellers.debug.DebugFiles.dir(com.jubitus.travellers.debug.DebugFiles.AUTOTEST);
            dir.mkdirs();
            file = new File(dir, new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".txt");
        }
        return file;
    }

    public static void append(String title, List<String> lines) {
        try (PrintWriter out = new PrintWriter(new FileWriter(file(), true))) {
            out.println("== " + new SimpleDateFormat("HH:mm:ss").format(new Date()) + " " + title);
            for (String line : lines) out.println(line);
        } catch (IOException e) {
            FMLLog.log.error("[Travellers/Autotest] could not write {}", file(), e);
        }
    }
}
