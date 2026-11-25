package io.tokenseek.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.content.IntentFilter;
import android.os.PatternMatcher;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PlatformWebLoginTest {
    @Test
    public void acceptsGeneralHttpsBrowsersIncludingWildcardHosts() {
        IntentFilter browser = new IntentFilter(Intent.ACTION_VIEW);
        browser.addCategory(Intent.CATEGORY_BROWSABLE);
        browser.addDataScheme("https");
        assertTrue(PlatformWebLogin.acceptsWebLinks(browser));
        browser.addDataAuthority("*", null);
        assertTrue(PlatformWebLogin.acceptsWebLinks(browser));
    }

    @Test
    public void excludesDomainAndPathHandlersFromPasswordSignIn() {
        IntentFilter links = new IntentFilter(Intent.ACTION_VIEW);
        links.addCategory(Intent.CATEGORY_BROWSABLE);
        links.addDataScheme("https");
        links.addDataAuthority("platform.deepseek.com", null);
        assertFalse(PlatformWebLogin.acceptsWebLinks(links));
        links.addDataAuthority("*", null);
        links.addDataPath("/sign_in", PatternMatcher.PATTERN_LITERAL);
        assertFalse(PlatformWebLogin.acceptsWebLinks(links));
        assertFalse(PlatformWebLogin.acceptsWebLinks(null));
    }
}
