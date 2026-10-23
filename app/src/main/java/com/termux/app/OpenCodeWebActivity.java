package com.termux.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.logger.Logger;

import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shows the OpenCode web UI inside the app.
 *
 * <p>The UI itself is the web app served by the OpenCode background service. This activity only
 * makes sure that the service is running, obtains a one time pair link from the OpenCode CLI,
 * and lets the WebView establish its own session cookie. The terminal/TUI keeps running in its
 * own Termux session while this activity is open.</p>
 */
public final class OpenCodeWebActivity extends AppCompatActivity {

    private static final String LOG_TAG = "OpenCodeWebActivity";

    private static final int REQUEST_FILE_CHOOSER = 1001;
    private static final long SERVICE_START_TIMEOUT_MS = 60_000L;
    private static final long CLI_TIMEOUT_MS = 30_000L;

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static final Pattern PAIR_CODE_PATTERN = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PAIR_LINK_PATTERN = Pattern.compile("https?://\\S+/auth/connect/[^\\s]+");

    private WebView mWebView;
    private View mStatusView;
    private ProgressBar mProgressBar;
    private TextView mStatusText;
    private Button mRetryButton;

    @Nullable
    private ValueCallback<Uri[]> mFilePathCallback;

    private boolean mAuthCompleted = false;
    private boolean mPageError = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_opencode_web);

        mWebView = findViewById(R.id.opencode_web_view);
        mStatusView = findViewById(R.id.opencode_web_status);
        mProgressBar = findViewById(R.id.opencode_web_progress);
        mStatusText = findViewById(R.id.opencode_web_status_text);
        mRetryButton = findViewById(R.id.opencode_web_retry);

        findViewById(R.id.opencode_web_close_button).setOnClickListener(v -> finish());
        findViewById(R.id.opencode_web_reload_button).setOnClickListener(v -> mWebView.reload());
        mRetryButton.setOnClickListener(v -> loadWebUi());

        configureWebView();

        if (!OpenCodeBootstrap.isRuntimeComplete()) {
            Toast.makeText(this, R.string.opencode_not_installed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        loadWebUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWebView != null) mWebView.onResume();
    }

    @Override
    protected void onPause() {
        if (mWebView != null) mWebView.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mFilePathCallback != null) {
            mFilePathCallback.onReceiveValue(null);
            mFilePathCallback = null;
        }
        if (mWebView != null) {
            mWebView.stopLoading();
            mWebView.setWebChromeClient(null);
            mWebView.setWebViewClient(null);
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @SuppressWarnings("deprecation")
    private void configureWebView() {
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(mWebView, true);

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openExternalUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openExternalUrl(request.getUrl().toString());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (isFinishing() || isDestroyed()) return;

                if (!mAuthCompleted && url != null && !url.contains("/auth/connect/")) {
                    mAuthCompleted = true;
                    // Drop the one time pair redirect from the history, so the system back
                    // button leaves the web UI instead of showing the expired pair page.
                    view.clearHistory();
                }

                if (!mPageError) showContent();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                showError(description);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    CharSequence description = error == null ? null : error.getDescription();
                    showError(description == null ? null : description.toString());
                }
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                    mFilePathCallback = null;
                }
                mFilePathCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, REQUEST_FILE_CHOOSER);
                    return true;
                } catch (ActivityNotFoundException e) {
                    mFilePathCallback = null;
                    Logger.logStackTraceWithMessage(LOG_TAG, "No activity for the file chooser", e);
                    return false;
                }
            }
        });
    }

    /** Open links that do not belong to the local OpenCode server in the normal browser. */
    private boolean openExternalUrl(String url) {
        if (url == null) return false;

        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return false;
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null) return false;
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
            return false;

        if (host != null && ("127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host)))
            return false;

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "No activity to open " + url, e);
        }
        return true;
    }

    /** Start the service, pair this WebView and load the web UI. */
    private void loadWebUi() {
        mPageError = false;
        showStatus(getString(R.string.opencode_web_starting), false);

        new Thread(() -> {
            String error = null;
            String pairUrl = null;
            try {
                pairUrl = preparePairUrl();
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start the OpenCode web UI", e);
                error = e.getMessage();
            }

            final String url = pairUrl;
            final String message = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (url != null) {
                    // The server only answers the one time pair link with the session cookie
                    // redirect when the request looks like a browser navigation. WebView sends
                    // this by default, but be explicit so the pair link can never return JSON.
                    mWebView.loadUrl(url, Collections.singletonMap(
                        "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"));
                } else {
                    showError(message);
                }
            });
        }, "opencode-web-start").start();
    }

    /** @return the one time URL that gives the WebView its authentication cookie. */
    private String preparePairUrl() throws Exception {
        String startOutput = OpenCodeCli.run(this, SERVICE_START_TIMEOUT_MS, "service", "start");
        String baseUrl = firstMatch(URL_PATTERN, startOutput, 0);
        if (baseUrl == null) {
            throw new Exception("The OpenCode service did not report its URL: " + startOutput.trim());
        }
        baseUrl = trimTrailingSlash(baseUrl);

        try {
            String pairOutput = OpenCodeCli.run(this, CLI_TIMEOUT_MS, "api", "POST", "/api/pair");
            String code = firstMatch(PAIR_CODE_PATTERN, pairOutput, 1);
            if (code != null) {
                return baseUrl + "/auth/connect/" + code;
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not create a pair code through the API", e);
        }

        // Older versions do not expose /api/pair, but the pair command prints the complete link.
        String pairOutput = OpenCodeCli.run(this, CLI_TIMEOUT_MS, "pair");
        String link = firstMatch(PAIR_LINK_PATTERN, pairOutput, 0);
        if (link == null) {
            throw new Exception("OpenCode did not return a pair link: " + pairOutput.trim());
        }
        return link;
    }

    @Nullable
    private static String firstMatch(Pattern pattern, @Nullable String text, int group) {
        if (text == null) return null;
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(group) : null;
    }

    private static String trimTrailingSlash(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') end--;
        return url.substring(0, end);
    }

    private void showStatus(String text, boolean showRetry) {
        if (mStatusView == null) return;
        mStatusView.setVisibility(View.VISIBLE);
        mProgressBar.setVisibility(showRetry ? View.GONE : View.VISIBLE);
        mStatusText.setText(text);
        mRetryButton.setVisibility(showRetry ? View.VISIBLE : View.GONE);
    }

    private void showContent() {
        if (mStatusView != null) mStatusView.setVisibility(View.GONE);
    }

    private void showError(@Nullable String details) {
        if (isFinishing() || isDestroyed()) return;
        mPageError = true;

        String message = getString(R.string.opencode_web_failed);
        if (details != null && !details.trim().isEmpty()) {
            message += "\n\n" + details.trim();
        }
        showStatus(message, true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_FILE_CHOOSER) {
            if (mFilePathCallback == null) return;

            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK) {
                if (data != null && data.getClipData() != null) {
                    ClipData clipData = data.getClipData();
                    results = new Uri[clipData.getItemCount()];
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        results[i] = clipData.getItemAt(i).getUri();
                    }
                } else if (data != null && data.getData() != null) {
                    results = new Uri[] { data.getData() };
                }
            }

            mFilePathCallback.onReceiveValue(results);
            mFilePathCallback = null;
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }
}
