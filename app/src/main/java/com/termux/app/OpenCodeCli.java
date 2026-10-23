package com.termux.app;

import android.content.Context;
import android.os.SystemClock;

import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Runs the native OpenCode launcher from the Android app process.
 *
 * <p>The launcher needs the Termux environment (PREFIX, HOME, PATH, …) and the bionic preload
 * variables must not leak into the glibc process. This is the same environment that the installer
 * uses for its own commands.</p>
 */
public final class OpenCodeCli {

    private static final long POLL_INTERVAL_MS = 50L;

    private OpenCodeCli() {
    }

    /**
     * Run {@code opencode <args>} and return its combined stdout/stderr.
     *
     * @throws Exception when the process could not be started or did not finish in time
     */
    public static String run(Context context, long timeoutMs, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(OpenCodeBootstrap.OPENCODE_BIN_PATH);
        cmd.addAll(Arrays.asList(args));
        String commandName = joinCommand(args);

        ProcessBuilder builder = new ProcessBuilder(cmd);
        Map<String, String> processEnv = builder.environment();
        processEnv.clear();
        try {
            processEnv.putAll(new TermuxShellEnvironment().getEnvironment(context, false));
        } catch (Exception ignored) {
            processEnv.put("HOME", OpenCodeBootstrap.HOME);
            processEnv.put("PREFIX", OpenCodeBootstrap.PREFIX);
            processEnv.put("PATH", OpenCodeBootstrap.PREFIX + "/bin");
            processEnv.put("TMPDIR", OpenCodeBootstrap.PREFIX + "/tmp");
        }
        processEnv.put("TERM", "xterm-256color");
        processEnv.put("LANG", "en_US.UTF-8");
        processEnv.put("COLORTERM", "truecolor");
        processEnv.remove("LD_PRELOAD");
        processEnv.remove("LD_LIBRARY_PATH");

        builder.directory(new File(OpenCodeBootstrap.HOME));
        builder.redirectErrorStream(true);

        final Process process = builder.start();
        final StringBuilder output = new StringBuilder();

        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append('\n');
                    }
                }
            } catch (Exception ignored) {
                // The process was killed or its output closed, nothing else to read.
            }
        }, "opencode-cli-reader");
        reader.setDaemon(true);
        reader.start();

        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (true) {
            try {
                process.exitValue();
                break;
            } catch (IllegalThreadStateException e) {
                if (SystemClock.uptimeMillis() > deadline) {
                    process.destroy();
                    throw new Exception("opencode " + commandName + " timed out");
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    process.destroy();
                    throw new Exception("opencode " + commandName + " was interrupted");
                }
            }
        }

        reader.join(1000L);
        synchronized (output) {
            return output.toString();
        }
    }

    private static String joinCommand(String[] args) {
        StringBuilder command = new StringBuilder();
        for (String arg : args) {
            if (command.length() > 0) command.append(' ');
            command.append(arg);
        }
        return command.toString();
    }
}
