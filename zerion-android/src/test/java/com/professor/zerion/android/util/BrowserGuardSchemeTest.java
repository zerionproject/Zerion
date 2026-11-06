package com.professor.zerion.android.util;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class BrowserGuardSchemeTest {

	private static FragmentActivity host() {
		FragmentActivity a = Robolectric.buildActivity(FragmentActivity.class)
				.setup().get();
		a.setTheme(R.style.ZerionTheme_NoActionBar);
		return a;
	}

	private static Intent openAndConfirm(FragmentActivity a, String url) {
		BrowserGuard.openUrl(a, url);
		shadowOf(Looper.getMainLooper()).idle();
		AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(d);
		d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		shadowOf(Looper.getMainLooper()).idle();
		return shadowOf(a).getNextStartedActivity();
	}

	@Test
	public void aWebLinkOpensOnlyInABrowser() {
		FragmentActivity a = host();
		Intent i = openAndConfirm(a, "https://example.org/page");
		assertNotNull(i);
		assertEquals(Intent.ACTION_VIEW, i.getAction());
		assertEquals("https://example.org/page", i.getDataString());
		assertTrue(i.hasCategory(Intent.CATEGORY_BROWSABLE));
		assertNull(i.getSelector());
		assertNull(i.getComponent());
	}

	@Test
	public void otherSchemesAreNeverHandedToAnotherApp() {
		String[] bad = {"tel:+15551234", "sms:+15551234",
				"market://details?id=x", "content://x/y", "file:///sdcard/x",
				"intent://x#Intent;scheme=http;end", "javascript:alert(1)",
				"zerion://channel/abc", "https://", "HTTP:example.org",
				"mailto:a@example.org"};
		for (String url : bad) {
			FragmentActivity a = host();
			assertNull(url, openAndConfirm(a, url));
		}
	}

	@Test
	public void theWebHostIsOnlyGivenForWebLinks() {
		assertEquals("example.org",
				BrowserGuard.webHost("https://example.org/a?b=c"));
		assertEquals("example.org", BrowserGuard.webHost("HTTP://example.org"));
		assertNull(BrowserGuard.webHost("tel:+15551234"));
		assertNull(BrowserGuard.webHost("zerion://channel/abc"));
		assertNull(BrowserGuard.webHost("https://"));
		assertNull(BrowserGuard.webHost("not a link"));
	}

	@Test
	public void aHostIsShownOnlyInPlainAsciiLetters() {
		assertNull(BrowserGuard.webHost("https://exa%E2%80%AEmple.org/x"));
		assertNull(BrowserGuard.webHost("https://exa\u202Emple.org/x"));
		assertNull(BrowserGuard.webHost("https://exa%2Fmple.org/"));
		assertEquals("xn--ypal-43d9g.com",
				BrowserGuard.webHost("https://\u0440\u0430ypal.com/login"));
		assertEquals("evil.example",
				BrowserGuard.webHost("https://bank.example@evil.example/"));
		assertEquals("example.org",
				BrowserGuard.webHost("https://example.org:8443/a"));
		assertEquals("[2001:db8::1]",
				BrowserGuard.webHost("https://[2001:db8::1]:443/"));
		assertNull(BrowserGuard.webHost("https://[2001:db8::zz]/"));
	}

	@Test
	public void aDirectionControlInALinkIsNotShownInTheWarning() {
		FragmentActivity a = host();
		BrowserGuard.openUrl(a, "https://example.org/\u202Etxt.exe");
		shadowOf(Looper.getMainLooper()).idle();
		AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
		android.widget.TextView message =
				d.findViewById(android.R.id.message);
		String text = message.getText().toString();
		assertTrue(text, text.endsWith("https://example.org/txt.exe"));
	}

	@Test
	public void aLookalikeHostIsSpelledInPlainLettersBeforeTheLinkOpens() {
		assertEquals("https://xn--ypal-43d9g.com/login",
				BrowserGuard.shown("https://\u0440\u0430ypal.com/login"));
		assertEquals("https://user@xn--ypal-43d9g.com:8443/a?b=\u00E9#c",
				BrowserGuard.shown(
						"https://user@\u0440\u0430ypal.com:8443/a?b=\u00E9#c"));
		assertEquals("https://example.org/caf\u00E9",
				BrowserGuard.shown("https://example.org/caf\u00E9"));
		assertEquals("HTTPS://Example.org/Page",
				BrowserGuard.shown("HTTPS://Example.org/Page"));
		assertEquals("https://[2001:db8::1]:443/",
				BrowserGuard.shown("https://[2001:db8::1]:443/"));
		assertEquals("tel:+15551234", BrowserGuard.shown("tel:+15551234"));
		assertEquals("https://xn--mnchen-3ya.de",
				BrowserGuard.shown("https://m\u00FCnchen.de"));
		assertEquals("https://bank.example@xn--ypal-43d9g.com/",
				BrowserGuard.shown(
						"https://bank.example@\u0440\u0430ypal.com/"));
		FragmentActivity a = host();
		BrowserGuard.openUrl(a, "https://\u0440\u0430ypal.com/login");
		shadowOf(Looper.getMainLooper()).idle();
		AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
		android.widget.TextView message =
				d.findViewById(android.R.id.message);
		String text = message.getText().toString();
		assertTrue(text, text.endsWith("https://xn--ypal-43d9g.com/login"));
		Intent i = openAndConfirm(a, "https://\u0440\u0430ypal.com/login");
		assertNotNull(i);
		assertEquals("https://\u0440\u0430ypal.com/login", i.getDataString());
	}

	@Test
	public void aLinkNothingCanOpenSaysSo() {
		FragmentActivity a = host();
		shadowOf(a.getApplication()).checkActivities(true);
		openAndConfirm(a, "https://example.org/");
		assertEquals(a.getString(R.string.updates_no_browser),
				org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
	}

	@Test
	public void theSchemeIsNormalisedBeforeItIsHandedOver() {
		FragmentActivity a = host();
		Intent i = openAndConfirm(a, "HTTPS://example.org/Page");
		assertNotNull(i);
		assertEquals("https", i.getData().getScheme());
	}
}
