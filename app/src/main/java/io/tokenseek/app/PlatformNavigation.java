package io.tokenseek.app;

import java.net.URI;
import java.net.URISyntaxException;

/** Tracks a user-requested platform task without reading or interpreting page contents. */
final class PlatformNavigation {
    static final String BASE = "https://platform.deepseek.com";
    static final String TOP_UP = "/top_up";
    static final String KEYS = "/api_keys";
    static final String LOGIN = "/sign_in";

    private enum Step { BEFORE_LOGIN, AT_LOGIN, RETURNING_TO_TASK, AT_RESUMED_TASK, FINISHED }

    private final String entry;
    private final boolean loginTask;
    private Step step = Step.BEFORE_LOGIN;
    private boolean consumed;
    private int historyFloor;

    PlatformNavigation(String initialUrl, boolean resumeConsumed) {
        URI initial = https(initialUrl);
        String path = platformPath(initialUrl);
        // Only known GET entry points can be replayed. OAuth queries, fragments and checkout URLs
        // belong to the website and must not be persisted or automatically repeated.
        entry = initial != null && initial.getRawQuery() == null && initial.getRawFragment() == null
                && (KEYS.equals(path) || TOP_UP.equals(path)) ? BASE + path : null;
        loginTask = LOGIN.equals(path);
        consumed = resumeConsumed;
    }

    static URI https(String address) {
        if (address == null) return null;
        try {
            URI uri = new URI(address);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getPort() <= 65535) return uri;
        } catch (URISyntaxException malformed) {
            // WebView can report non-network locations while opening a window.
        }
        return null;
    }

    static String platformPath(String url) {
        URI parsed = https(url);
        if (parsed == null || !"platform.deepseek.com".equalsIgnoreCase(parsed.getHost())
                || (parsed.getPort() != -1 && parsed.getPort() != 443)) return null;
        return parsed.getRawPath().isEmpty() ? "/" : parsed.getRawPath();
    }

    static boolean isKeyPage(String url) {
        return KEYS.equals(platformPath(url));
    }

    static boolean canBind(String visibleUrl, String renderedUrl, boolean isRoot, boolean failed) {
        return isRoot && !failed && isKeyPage(visibleUrl) && visibleUrl.equals(renderedUrl);
    }

    /** WebView reports a request off the UI thread, either side of the corresponding page start.
     * A history change inside an SPA keeps the same document. A new document must earn its own
     * method evidence, even on a reload of exactly the same URL. */
    static final class Document {
        private static final class Request {
            final String address;
            final boolean get;
            Request(String url, String method) {
                address = platformPath(url) == null ? null : withoutFragment(url);
                get = "GET".equals(method);
            }
        }

        private volatile Request latest;
        private Request accepted;
        private Request used;
        private String started;

        void observeRequest(String url, String method) {
            latest = new Request(url, method);
        }

        void start(String url) {
            claimRequest();
            started = withoutFragment(url);
            accepted = null;
            claimRequest();
        }

        private void claimRequest() {
            Request candidate = latest;
            if (accepted == null && candidate != null && candidate != used
                    && started != null && started.equals(candidate.address)) {
                accepted = candidate;
                used = candidate;
            }
        }

        boolean isOfficialGet(String current) {
            claimRequest();
            return accepted != null && accepted == latest && accepted.get && platformPath(current) != null;
        }

        boolean finishedAt(String url, String current) {
            if (https(current) == null || https(url) == null) return false;
            // Android may finish the original URL after its SPA has already replaced the path.
            return url.equals(current) || (withoutFragment(url).equals(started)
                    && platformPath(url) != null && platformPath(current) != null);
        }

        private static String withoutFragment(String url) {
            if (url == null) return null;
            int hash = url.indexOf('#');
            return hash < 0 ? url : url.substring(0, hash);
        }
    }

    void noteLoginHistory(String url, int index) {
        if ((entry != null || loginTask) && LOGIN.equals(platformPath(url))
                && (step == Step.BEFORE_LOGIN || step == Step.RETURNING_TO_TASK || step == Step.AT_RESUMED_TASK)) {
            historyFloor = Math.max(historyFloor, index);
        }
    }

    String onVisibleRoot(String url, boolean fromGetDocument, int historyIndex) {
        if ((!loginTask && entry == null) || !fromGetDocument || step == Step.FINISHED) return null;
        String path = platformPath(url);
        boolean businessPage = TOP_UP.equals(path) || KEYS.equals(path);
        URI current = https(url);
        boolean home = "/".equals(path) && current.getRawQuery() == null && current.getRawFragment() == null;

        if (step == Step.BEFORE_LOGIN) {
            if (LOGIN.equals(path)) {
                historyFloor = historyIndex;
                step = Step.AT_LOGIN;
            } else if (loginTask && (home || businessPage)) {
                // An explicit sign-in entry can go straight to the site if a session already exists.
                historyFloor = historyIndex;
                step = Step.FINISHED;
            }
        } else if (step == Step.AT_LOGIN && (home || businessPage)) {
            // Returning here through WebView history would revisit sign-in and redirect again.
            historyFloor = historyIndex;
            if (entry != null && !consumed && home) {
                consumed = true;
                step = Step.RETURNING_TO_TASK;
                return entry;
            }
            consumed = true;
            step = Step.FINISHED;
        } else if (step == Step.RETURNING_TO_TASK && (businessPage || LOGIN.equals(path))) {
            historyFloor = historyIndex;
            step = businessPage ? Step.AT_RESUMED_TASK : Step.FINISHED;
        } else if (step == Step.AT_RESUMED_TASK && !businessPage) {
            // The website may render a protected shell and then ask for sign-in again.
            if (LOGIN.equals(path)) historyFloor = historyIndex;
            step = Step.FINISHED;
        }
        return null;
    }

    boolean canGoBackWithinTask(int historyIndex) {
        return historyIndex > historyFloor;
    }

    void cancelContinuation() {
        consumed = true;
    }

    boolean continuationConsumed() {
        return consumed;
    }
}
