package com.termux.app.terminal;

import android.os.Handler;
import android.os.Looper;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

/**
 * Sends a prompt to a terminal session the same way a real terminal paste followed by Enter does.
 *
 * <p>The text is handed to {@link TerminalEmulator#paste(String)}, which wraps it in bracketed
 * paste markers when the running program (eg. the OpenCode TUI) has enabled that mode. This lets
 * the program receive the whole prompt as a single paste instead of a burst of individual
 * key presses. The Enter key is sent a little later so the program has time to process the
 * paste before the prompt is submitted.</p>
 */
public final class TerminalPromptSender {

    private static final long MIN_ENTER_DELAY_MS = 80;
    private static final long MAX_ENTER_DELAY_MS = 800;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mPendingEnter;

    /** Paste {@code text} into {@code session} and submit it with Enter shortly afterwards. */
    public void send(final TerminalSession session, String text) {
        if (session == null || text == null || text.isEmpty()) return;

        // If another prompt is still waiting for its Enter, submit it first. Otherwise two quick
        // taps would merge both prompts into a single one.
        flushPendingEnter();

        TerminalEmulator emulator = session.getEmulator();
        if (emulator != null) {
            emulator.paste(text);
        } else {
            session.write(text);
        }

        final Runnable enter = () -> {
            if (mPendingEnter == enter) mPendingEnter = null;
            if (session.isRunning()) session.write("\r");
        };

        mPendingEnter = enter;
        mHandler.postDelayed(enter, enterDelayFor(text));
    }

    /** Cancel a pending Enter and send it right away, if there is one. */
    public void flushPendingEnter() {
        Runnable pending = mPendingEnter;
        if (pending == null) return;
        mHandler.removeCallbacks(pending);
        mPendingEnter = null;
        pending.run();
    }

    /** Give longer prompts a little more time to be processed by the terminal program. */
    private static long enterDelayFor(String text) {
        long delay = MIN_ENTER_DELAY_MS + text.length() / 4L;
        return Math.min(delay, MAX_ENTER_DELAY_MS);
    }
}
