package com.termux.app.terminal;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

/**
 * Sends a prompt to a terminal session the same way a real terminal paste followed by Enter does.
 *
 * <p>The text is handed to {@link TerminalEmulator#paste(String)}, which wraps it in bracketed
 * paste markers when the running program (e.g. the OpenCode TUI) has enabled that mode. This lets
 * the program receive the whole prompt as a single paste instead of a burst of individual
 * key presses. A carriage return is written immediately afterwards to submit the prompt.</p>
 */
public final class TerminalPromptSender {

    /** Paste {@code text} into {@code session} and submit it with Enter right away. */
    public void send(final TerminalSession session, String text) {
        if (session == null || text == null || text.isEmpty()) return;

        TerminalEmulator emulator = session.getEmulator();
        if (emulator != null) {
            emulator.paste(text);
        } else {
            session.write(text);
        }

        if (session.isRunning()) {
            session.write("\r");
        }
    }

    /** Kept for compatibility with existing call sites. */
    public void flushPendingEnter() {
        // No longer needed; sending is immediate.
    }
}
