package com.odel.content.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Runs the command-line converters (LibreOffice, ffmpeg) with a time limit; remembers which ones are installed. */
public final class Tools {
    private static final ConcurrentHashMap<String, Boolean> PRESENT = new ConcurrentHashMap<>();

    private Tools() {}

    public record Result(int exit, String output, boolean timedOut) {
        public boolean ok() { return exit == 0 && !timedOut; }
    }

    public static boolean available(String command) {
        return PRESENT.computeIfAbsent(command, c -> {
            try {
                return run(List.of(c, "--version"), null, 30).ok() || run(List.of(c, "-version"), null, 30).ok();
            } catch (IOException e) {
                return false;
            }
        });
    }

    public static Result run(List<String> cmd, Path dir, int seconds) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (dir != null) pb.directory(dir.toFile());
        Process p = pb.start();
        try {
            boolean finished = p.waitFor(seconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new Result(-1, "", true);
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Result(p.exitValue(), out.length() > 2000 ? out.substring(0, 2000) : out, false);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            return new Result(-1, "", true);
        }
    }
}
