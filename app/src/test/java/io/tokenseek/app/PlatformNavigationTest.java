package io.tokenseek.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlatformNavigationTest {
    private static final String ORIGIN = "https://platform.deepseek.com";
    private static final String HOME = ORIGIN + "/";
    private static final String LOGIN = ORIGIN + "/sign_in";
    private static final String TOP_UP = ORIGIN + "/top_up";
    private static final String KEYS = ORIGIN + "/api_keys";

    @Test public void protectedEntryResumesExactlyOnceFromVerifiedHomeGet() {
        for (String target : new String[] { TOP_UP, KEYS }) {
            PlatformNavigation route = new PlatformNavigation(target, false);
            assertNull(route.onVisibleRoot(target, true, 0));
            assertNull(route.onVisibleRoot(HOME, true, 1));
            assertNull(route.onVisibleRoot(LOGIN + "?redirect=official", true, 2));
            assertNull(route.onVisibleRoot(HOME, false, 3));
            assertFalse(route.continuationConsumed());
            assertEquals(target, route.onVisibleRoot(HOME, true, 4));
            assertTrue(route.continuationConsumed());
            assertNull(route.onVisibleRoot(LOGIN, true, 5));
            assertNull(route.onVisibleRoot(HOME, true, 6));
        }
    }

    @Test public void siteCompletingTheTaskItselfConsumesTheContinuation() {
        PlatformNavigation route = new PlatformNavigation(KEYS, false);
        assertNull(route.onVisibleRoot(LOGIN, true, 0));
        assertNull(route.onVisibleRoot(KEYS, true, 1));
        assertTrue(route.continuationConsumed());
        assertNull(route.onVisibleRoot(HOME, true, 2));
    }

    @Test public void explicitBackAndRestoredConsumedTaskDoNotRestartTheFlow() {
        PlatformNavigation original = new PlatformNavigation(TOP_UP, false);
        assertNull(original.onVisibleRoot(LOGIN, true, 0));
        original.cancelContinuation();
        assertTrue(original.continuationConsumed());
        assertNull(original.onVisibleRoot(HOME, true, 1));
        PlatformNavigation restored = new PlatformNavigation(TOP_UP, original.continuationConsumed());
        assertNull(restored.onVisibleRoot(LOGIN, true, 2));
        assertNull(restored.onVisibleRoot(HOME, true, 3));
    }

    @Test public void directLoginHomeOrSignupHasNoPendingChargeOrKeyTask() {
        for (String initial : new String[] { HOME, LOGIN, ORIGIN + "/sign_up" }) {
            PlatformNavigation route = new PlatformNavigation(initial, false);
            assertNull(route.onVisibleRoot(LOGIN, true, 0));
            assertNull(route.onVisibleRoot(HOME, true, 1));
        }
    }

    @Test public void callbacksRegistrationPaymentAndExternalPagesKeepTheirOwnNavigation() {
        for (String other : new String[] {
                ORIGIN + "/sign_up", ORIGIN + "/register", ORIGIN + "/verify", ORIGIN + "/otp",
                ORIGIN + "/oauth/callback", HOME + "?code=one-time", HOME + "#state=one-time",
                ORIGIN + "/checkout", ORIGIN + "/payment", ORIGIN + "/top_up",
                "https://accounts.google.com/", "https://pay.example/", "about:blank",
                "http://platform.deepseek.com/", "https://platform.deepseek.com.example/",
                "https://platform.deepseek.com:8443/", "https://name@platform.deepseek.com/" }) {
            PlatformNavigation route = new PlatformNavigation(KEYS, false);
            assertNull(route.onVisibleRoot(LOGIN, true, 0));
            assertNull("Unexpected continuation at " + other, route.onVisibleRoot(other, true, 1));
        }
    }

    @Test public void unrelatedLoginPagesDoNotArmAnOfficialTask() {
        for (String unrelated : new String[] { "https://id.example/sign_in", ORIGIN + "/sign_up", ORIGIN + "/otp" }) {
            PlatformNavigation route = new PlatformNavigation(TOP_UP, false);
            assertNull(route.onVisibleRoot(unrelated, true, 0));
            assertNull(route.onVisibleRoot(HOME, true, 1));
        }
    }

    @Test public void standaloneLoginDoesNotReopenAuthenticationWhenBackIsPressed() {
        PlatformNavigation route = new PlatformNavigation(LOGIN, false);
        assertNull(route.onVisibleRoot(LOGIN, true, 0));
        assertNull(route.onVisibleRoot(HOME, true, 1));
        assertFalse(route.canGoBackWithinTask(1));
        assertNull(route.onVisibleRoot(KEYS, true, 2));
        assertTrue(route.canGoBackWithinTask(2));
    }

    @Test public void continuedTaskLeavesAuthenticationBehindAndAllowsBackOnNewSubpages() {
        PlatformNavigation route = new PlatformNavigation(KEYS, false);
        assertNull(route.onVisibleRoot(KEYS, true, 0));
        assertNull(route.onVisibleRoot(LOGIN, true, 1));
        assertEquals(KEYS, route.onVisibleRoot(HOME, true, 2));
        assertNull(route.onVisibleRoot(KEYS, true, 3));
        assertFalse(route.canGoBackWithinTask(3));
        assertNull(route.onVisibleRoot(ORIGIN + "/key_details", true, 4));
        assertTrue(route.canGoBackWithinTask(4));
        assertFalse(route.canGoBackWithinTask(3));
    }

    @Test public void anotherLoginAfterContinuationNeitherLoopsNorExposesItsPreviousPage() {
        PlatformNavigation route = new PlatformNavigation(KEYS, false);
        assertNull(route.onVisibleRoot(LOGIN, true, 0));
        assertEquals(KEYS, route.onVisibleRoot(HOME, true, 1));
        assertNull(route.onVisibleRoot(LOGIN, true, 2));
        assertFalse(route.canGoBackWithinTask(2));
        assertNull(route.onVisibleRoot(HOME, true, 3));
    }

    @Test public void callbackOrPaymentEntryCannotBecomeAReplayableTask() {
        for (String initial : new String[] { KEYS + "?code=one-time", TOP_UP + "#checkout",
                ORIGIN + "/checkout", "https://pay.example/top_up" }) {
            PlatformNavigation route = new PlatformNavigation(initial, false);
            assertNull(route.onVisibleRoot(LOGIN, true, 0));
            assertNull(route.onVisibleRoot(HOME, true, 1));
        }
    }

    @Test public void methodEvidenceBelongsToOneDocumentIncludingReloadsAndPosts() {
        PlatformNavigation.Document page = new PlatformNavigation.Document();
        page.observeRequest(LOGIN, "GET");
        page.start(LOGIN);
        assertTrue(page.isOfficialGet(LOGIN));
        page.start(HOME);
        assertFalse(page.isOfficialGet(HOME));
        page.observeRequest(HOME, "GET"); // May arrive after onPageStarted.
        assertTrue(page.isOfficialGet(HOME));
        page.start(HOME);
        assertFalse(page.isOfficialGet(HOME));
        page.observeRequest(HOME, "POST");
        assertFalse(page.isOfficialGet(HOME));
    }

    @Test public void aFinishedSpaDocumentCanRenderItsNewRouteWithoutAnotherRequest() {
        PlatformNavigation.Document page = new PlatformNavigation.Document();
        page.start(KEYS);
        page.observeRequest(KEYS, "GET");
        assertTrue(page.finishedAt(KEYS, LOGIN));
        assertTrue(page.isOfficialGet(LOGIN));
        assertTrue(page.isOfficialGet(HOME));
        assertFalse(page.finishedAt(KEYS, "https://accounts.google.com/"));
        assertFalse(page.finishedAt(ORIGIN + "/stale", LOGIN));
    }

    @Test public void pendingCrossOriginNavigationCannotAuthorizeAnotherOfficialGet() {
        PlatformNavigation.Document document = new PlatformNavigation.Document();
        document.start(LOGIN);
        document.observeRequest(LOGIN, "GET");
        assertTrue(document.isOfficialGet(LOGIN));
        document.observeRequest("https://pay.example/return", "GET");
        assertFalse(document.isOfficialGet(HOME));
        document.start(HOME);
        assertFalse(document.isOfficialGet(HOME));
        assertFalse(document.finishedAt("https://pay.example/return", HOME));
    }

    @Test public void aTransientResumedKeyShellCannotTrapBackAtSignIn() {
        PlatformNavigation route = new PlatformNavigation(KEYS, false);
        route.onVisibleRoot(LOGIN, true, 1);
        assertEquals(KEYS, route.onVisibleRoot(HOME, true, 2));
        route.onVisibleRoot(KEYS, true, 3);
        route.noteLoginHistory(LOGIN, 4);
        assertFalse(route.canGoBackWithinTask(4)); // Safe even before the login page finishes.
        route.onVisibleRoot(LOGIN, true, 4);
        assertFalse(route.canGoBackWithinTask(4));
        assertTrue(route.canGoBackWithinTask(5));
    }

    @Test public void bindRequiresTheSuccessfulVisibleRootKeyPage() {
        assertTrue(PlatformNavigation.isKeyPage(KEYS));
        assertTrue(PlatformNavigation.canBind(KEYS, KEYS, true, false));
        assertFalse(PlatformNavigation.canBind(KEYS, KEYS, false, false));
        assertFalse(PlatformNavigation.canBind(KEYS, KEYS, true, true));
        assertFalse(PlatformNavigation.canBind(KEYS, LOGIN, true, false));
        assertFalse(PlatformNavigation.canBind(LOGIN, KEYS, true, false));
        for (String other : new String[] { null, "", HOME, LOGIN, TOP_UP, ORIGIN + "/api_keys_extra",
                "https://platform.deepseek.com.example/api_keys", "https://user@platform.deepseek.com/api_keys",
                "http://platform.deepseek.com/api_keys", "https://platform.deepseek.com:8443/api_keys" }) {
            assertFalse(PlatformNavigation.isKeyPage(other));
            assertFalse(PlatformNavigation.canBind(other, other, true, false));
        }
    }
}
