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
import static org.junit.Assert.assertFalse;
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
		assertEquals("https://xn--ypal-43d9g.com:8443/a?b=\u00E9#c",
				BrowserGuard.shown(
						"https://user@\u0440\u0430ypal.com:8443/a?b=\u00E9#c"));
		assertEquals("https://example.org/caf\u00E9",
				BrowserGuard.shown("https://example.org/caf\u00E9"));
		assertEquals("https://example.org/Page",
				BrowserGuard.shown("HTTPS://Example.org/Page"));
		assertEquals("https://[2001:db8::1]:443/",
				BrowserGuard.shown("https://[2001:db8::1]:443/"));
		assertEquals("tel:+15551234", BrowserGuard.shown("tel:+15551234"));
		assertEquals("https://xn--mnchen-3ya.de",
				BrowserGuard.shown("https://m\u00FCnchen.de"));
		assertEquals("https://xn--ypal-43d9g.com/",
				BrowserGuard.shown(
						"https://bank.example@\u0440\u0430ypal.com/"));
		FragmentActivity a = host();
		BrowserGuard.openUrl(a, "https://\u0440\u0430ypal.com/login");
		shadowOf(Looper.getMainLooper()).idle();
		AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
		android.widget.TextView message =
				d.findViewById(android.R.id.message);
		String text = message.getText().toString();
		assertTrue(text, text.endsWith("https://xn--ypal-43d9g.com/login\n"
				+ a.getString(R.string.link_host_other_alphabet)));
		Intent i = openAndConfirm(a, "https://\u0440\u0430ypal.com/login");
		assertNotNull(i);
		assertEquals("https://xn--ypal-43d9g.com/login", i.getDataString());
	}

	private static String warningText(FragmentActivity a, String url) {
		BrowserGuard.openUrl(a, url);
		shadowOf(Looper.getMainLooper()).idle();
		AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
		android.widget.TextView message =
				d.findViewById(android.R.id.message);
		String text = message.getText().toString();
		d.dismiss();
		return text;
	}

	private static void assertShownThenOpened(String url, String address,
			boolean noted) {
		FragmentActivity a = host();
		String note = a.getString(R.string.link_host_other_alphabet);
		assertEquals(url, address, BrowserGuard.shown(url));
		assertEquals(url, address, BrowserGuard.opened(url));
		assertEquals(url, noted, BrowserGuard.hostNeedsAlphabetNote(url));
		String text = warningText(a, url);
		assertTrue(text, text.endsWith(noted ? address + "\n" + note
				: address));
		assertEquals(text, noted, text.contains(note));
		Intent i = openAndConfirm(a, url);
		assertNotNull(url, i);
		assertEquals(url, address, i.getDataString());
	}

	@Test
	public void aSharpSHostOpensAsTheDoubleSHostThatWasShown() {
		assertShownThenOpened("https://sparka\u00dfe.de/login",
				"https://sparkasse.de/login", true);
	}

	@Test
	public void aFinalSigmaHostOpensAsTheHostThatWasShown() {
		String mapped = java.net.IDN.toASCII("\u03bf\u03b4\u03cc\u03c3.gr");
		assertTrue(mapped, mapped.startsWith("xn--"));
		assertShownThenOpened("https://\u03bf\u03b4\u03cc\u03c2.gr",
				"https://" + mapped, true);
	}

	@Test
	public void aJoinerHiddenInAHostIsGoneFromWhatIsShownAndOpened() {
		assertShownThenOpened("https://exam\u200dple.org/a",
				"https://example.org/a", true);
		assertShownThenOpened("https://exam\u200cple.org/a",
				"https://example.org/a", true);
	}

	@Test
	public void aCyrillicHostOpensInTheSpellingThatWasShown() {
		assertShownThenOpened("https://\u0440\u0430ypal.com/login",
				"https://xn--ypal-43d9g.com/login", true);
		assertShownThenOpened("https://xn--ypal-43d9g.com/login",
				"https://xn--ypal-43d9g.com/login", true);
	}

	@Test
	public void aWideLetterHostIsNotedAndOpensInPlainLetters() {
		assertShownThenOpened("https://\uff45xample.org/",
				"https://example.org/", true);
	}

	@Test
	public void aPlainHostOpensUnchanged() {
		String plain = "https://example.org:8443/a/b.html?c=d&e=%20f#g-h";
		assertShownThenOpened(plain, plain, false);
		assertShownThenOpened("http://example.org", "http://example.org",
				false);
		assertShownThenOpened("https://example.org/caf\u00e9?q=\u00df",
				"https://example.org/caf\u00e9?q=\u00df", false);
	}

	@Test
	public void userInfoInFrontOfTheHostIsNeitherShownNorOpened() {
		assertShownThenOpened(
				"https://user:pw@example.org:8443/a/b.html?c=d#e",
				"https://example.org:8443/a/b.html?c=d#e", false);
		assertShownThenOpened("https://accounts.example.com@evil.example/x",
				"https://evil.example/x", false);
		assertShownThenOpened("https://a@b@evil.example/x@y",
				"https://evil.example/x@y", false);
	}

	@Test
	public void userInfoInFrontOfABracketedAddressIsNeitherShownNorOpened() {
		assertShownThenOpened(
				"https://accounts.example.com@[2001:db8::1]/login",
				"https://[2001:db8::1]/login", false);
		assertShownThenOpened(
				"https://accounts.example.com:pw@[2001:DB8::1]:8443/login",
				"https://[2001:db8::1]:8443/login", false);
		assertShownThenOpened("https://[2001:db8::1]:443/",
				"https://[2001:db8::1]:443/", false);
	}

	@Test
	public void aLinkWhoseHostEndsInSomethingElseThanAPortIsNotOpened() {
		for (String url : new String[] {"https://example.org:80x/",
				"https://example.org:123456/", "https://example.org:70000/",
				"https://[2001:db8::1]evil.example/",
				"https://[2001:db8::1]:x/"}) {
			assertNull(url, BrowserGuard.webHost(url));
			assertNull(url, BrowserGuard.opened(url));
			assertNull(url, openAndConfirm(host(), url));
		}
		assertEquals("https://example.org:/",
				BrowserGuard.opened("https://example.org:/"));
	}

	@Test
	public void theCopiedLinkIsTheLinkThatWasShown() {
		FragmentActivity a = host();
		BrowserGuard.openUrl(a, "https://bank.example@sparka\u00dfe.de/x");
		shadowOf(Looper.getMainLooper()).idle();
		AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
		d.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
		shadowOf(Looper.getMainLooper()).idle();
		android.content.ClipboardManager clipboard =
				(android.content.ClipboardManager) a.getSystemService(
						android.content.Context.CLIPBOARD_SERVICE);
		assertEquals("https://sparkasse.de/x", clipboard.getPrimaryClip()
				.getItemAt(0).getText().toString());
	}

	@Test
	public void userInfoInFrontOfTheHostIsNotShown() {
		String shown = BrowserGuard.shown("https://bank.example@evil.com/x");
		assertFalse(shown.contains("bank.example"));
		assertTrue(shown.contains("evil.com/x"));
		assertEquals("https://evil.com/x",
				BrowserGuard.shown("https://user:pw@evil.com/x"));
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
	public void aHostWithLettersFromAnotherAlphabetIsRecognised() {
		assertTrue(BrowserGuard.hasOtherAlphabet("xn--ypal-43d9g.com"));
		assertTrue(BrowserGuard.hasOtherAlphabet("www.xn--80ak6aa92e.com"));
		assertFalse(BrowserGuard.hasOtherAlphabet("example.org"));
		assertFalse(BrowserGuard.hasOtherAlphabet("xn.example.org"));
		assertFalse(BrowserGuard.hasOtherAlphabet(null));
	}

	@Test
	public void theWarningNamesAHostWrittenInAnotherAlphabet() {
		FragmentActivity a = host();
		BrowserGuard.openUrl(a, "https://\u0440\u0430ypal.com/login");
		shadowOf(Looper.getMainLooper()).idle();
		AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
		android.widget.TextView message =
				d.findViewById(android.R.id.message);
		String text = message.getText().toString();
		assertTrue(text, text.contains("https://xn--ypal-43d9g.com/login\n"
				+ a.getString(R.string.link_host_other_alphabet)));
		d.dismiss();
		BrowserGuard.openUrl(a, "https://example.org/login");
		shadowOf(Looper.getMainLooper()).idle();
		d = (AlertDialog) ShadowDialog.getLatestDialog();
		message = d.findViewById(android.R.id.message);
		text = message.getText().toString();
		assertFalse(text,
				text.contains(a.getString(R.string.link_host_other_alphabet)));
	}

	@Test
	public void theSchemeIsNormalisedBeforeItIsHandedOver() {
		FragmentActivity a = host();
		Intent i = openAndConfirm(a, "HTTPS://example.org/Page");
		assertNotNull(i);
		assertEquals("https", i.getData().getScheme());
	}
}
