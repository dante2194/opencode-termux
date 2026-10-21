package com.termux.app.terminal;

import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.terminal.TerminalSession;

import java.nio.charset.StandardCharsets;

/**
 * A small input bar that is shown at the bottom of the activity.
 *
 * <p>The OpenCode TUI (a full screen terminal application) grabs the soft keyboard for itself,
 * which makes it hard to type a prompt. This view gives the user a dedicated text field: the text
 * is written to the pty of the current session using bracketed paste, exactly like a real
 * terminal would do it.</p>
 */
public class FloatingPromptInput {

    private static final String LOG_TAG = "FloatingPromptInput";

    /** Bracketed paste markers, see https://en.wikipedia.org/wiki/Bracketed-paste */
    private static final String BRACKETED_PASTE_START = "\u001b[200~";
    private static final String BRACKETED_PASTE_END = "\u001b[201~";

    private final TermuxActivity mActivity;

    private View mRootView;
    private EditText mInputView;
    private boolean mVisible = false;

    public FloatingPromptInput(TermuxActivity activity) {
        this.mActivity = activity;
    }

    /** Show the input bar if it is hidden, hide it otherwise. */
    public void toggle() {
        if (mVisible)
            hide();
        else
            show();
    }

    public void show() {
        if (mRootView == null) {
            View root = LayoutInflater.from(mActivity).inflate(R.layout.view_opencode_prompt,
                mActivity.getTermuxActivityRootView(), false);

            mInputView = root.findViewById(R.id.opencode_prompt_input);
            Button sendButton = root.findViewById(R.id.opencode_prompt_send);

            sendButton.setOnClickListener(v -> sendPrompt());
            mInputView.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_NULL) {
                    sendPrompt();
                    return true;
                }
                return false;
            });

            mActivity.getTermuxActivityRootView().addView(root);
            mRootView = root;
        }

        mRootView.setVisibility(View.VISIBLE);
        mVisible = true;

        mInputView.requestFocus();
    }

    public void hide() {
        if (mRootView == null) return;
        mRootView.setVisibility(View.GONE);
        mVisible = false;
    }

    public boolean isVisible() {
        return mVisible;
    }

    /** Write the content of the input field to the pty of the current session. */
    private void sendPrompt() {
        if (mInputView == null) return;

        String text = mInputView.getText().toString();
        if (text.isEmpty()) return;

        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) {
            Logger.logWarn(LOG_TAG, "There is no session to write the prompt to.");
            return;
        }

        byte[] bytes = (BRACKETED_PASTE_START + text + BRACKETED_PASTE_END + "\r").getBytes(StandardCharsets.UTF_8);
        session.write(bytes, 0, bytes.length);

        mInputView.setText("");
    }
}
