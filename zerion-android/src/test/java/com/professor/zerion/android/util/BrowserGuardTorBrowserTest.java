package com.professor.zerion.android.util;

import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Looper;
import android.widget.TextView;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowPackageManager;

import javax.annotation.Nullable;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class BrowserGuardTorBrowserTest {

	private static final String FDROID = "org.fdroid.fdroid";
	private static final String DROIDIFY = "com.looker.droidify";
	private static final String BROWSER = "com.example.browser";
	private static final String OTHER_BROWSER = "com.example.otherbrowser";
	private static final String BANK_APP = "com.example.bank";
	private static final String BANK_HOST = "bank.example";
	private static final String BANK_LINK = "https://bank.example/login";

	private static FragmentActivity host() {
		FragmentActivity a = Robolectric.buildActivity(FragmentActivity.class)
				.setup().get();
		a.setTheme(R.style.ZerionTheme_NoActionBar);
		shadowOf(a.getApplication()).checkActivities(true);
		return a;
	}

	private static void installViewer(FragmentActivity a, String pkg,
			String... schemes) {
		installViewerOfHost(a, pkg, null, schemes);
	}

	private static void installViewerOfHost(FragmentActivity a, String pkg,
			@Nullable String onlyHost, String... schemes) {
		ShadowPackageManager pm = shadowOf(a.getPackageManager());
		ComponentName c = new ComponentName(pkg, pkg + ".Main");
		pm.addActivityIfNotPresent(c);
		IntentFilter f = webFilter(schemes);
		if (onlyHost != null) f.addDataAuthority(onlyHost, null);
		try {
			pm.addIntentFilterForActivity(c, f);
		} catch (android.content.pm.PackageManager.NameNotFoundException e) {
			throw new AssertionError(e);
		}
	}

	private static IntentFilter webFilter(String... schemes) {
		IntentFilter f = new IntentFilter(Intent.ACTION_VIEW);
		f.addCategory(Intent.CATEGORY_DEFAULT);
		f.addCategory(Intent.CATEGORY_BROWSABLE);
		for (String s : schemes) f.addDataScheme(s);
		return f;
	}

	private static void installBankApp(FragmentActivity a) {
		installViewerOfHost(a, BANK_APP, BANK_HOST, "http", "https");
	}

	private static void noBrowserIsTheDefault(FragmentActivity a) {
		shadowOf(a.getPackageManager()).setShouldShowActivityChooser(true);
	}

	private static void makeDefaultBrowser(FragmentActivity a, String pkg,
			String... installed) {
		ComponentName[] all = new ComponentName[installed.length];
		for (int i = 0; i < installed.length; i++) {
			all[i] = new ComponentName(installed[i], installed[i] + ".Main");
		}
		a.getPackageManager().addPreferredActivity(webFilter("http", "https"),
				IntentFilter.MATCH_CATEGORY_SCHEME, all,
				new ComponentName(pkg, pkg + ".Main"));
	}

	private static String toast() {
		return org.robolectric.shadows.ShadowToast.getTextOfLatestToast();
	}

	private static void installTorBrowser(FragmentActivity a, String pkg) {
		installViewer(a, pkg, "http", "https");
	}

	private static void installedFrom(FragmentActivity a, String installer) {
		a.getPackageManager().setInstallerPackageName(a.getPackageName(),
				installer);
	}

	private static AlertDialog dialog(FragmentActivity a, String url) {
		BrowserGuard.openUrl(a, url);
		shadowOf(Looper.getMainLooper()).idle();
		AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(d);
		return d;
	}

	@Nullable
	private static Intent click(FragmentActivity a, AlertDialog d,
			int button) {
		d.getButton(button).performClick();
		shadowOf(Looper.getMainLooper()).idle();
		return shadowOf(a).getNextStartedActivity();
	}

	private static String message(AlertDialog d) {
		TextView t = d.findViewById(android.R.id.message);
		return t.getText().toString();
	}

	private static String button(AlertDialog d, int which) {
		return d.getButton(which).getText().toString();
	}

	@Test
	public void anAppThatClaimsOneDomainDoesNotReceiveTheLink() {
		FragmentActivity a = host();
		installBankApp(a);
		installViewer(a, BROWSER, "http", "https");
		assertEquals(BROWSER, BrowserGuard.defaultBrowserPackage(a));
		Intent i = click(a, dialog(a, BANK_LINK),
				DialogInterface.BUTTON_POSITIVE);
		assertNotNull(i);
		assertEquals(BROWSER, i.getPackage());
		assertNull(i.getComponent());
		assertEquals(BANK_LINK, i.getDataString());
	}

	@Test
	public void theDefaultBrowserReceivesTheLinkAmongSeveralBrowsers() {
		FragmentActivity a = host();
		installViewer(a, BROWSER, "http", "https");
		installBankApp(a);
		installViewer(a, OTHER_BROWSER, "http", "https");
		noBrowserIsTheDefault(a);
		makeDefaultBrowser(a, OTHER_BROWSER, BROWSER, OTHER_BROWSER);
		assertEquals(OTHER_BROWSER, BrowserGuard.defaultBrowserPackage(a));
		for (String url : new String[] {BANK_LINK,
				"http://example.org/page"}) {
			Intent i = click(a, dialog(a, url),
					DialogInterface.BUTTON_POSITIVE);
			assertNotNull(url, i);
			assertEquals(url, OTHER_BROWSER, i.getPackage());
			assertEquals(url, i.getDataString());
		}
	}

	@Test
	public void torBrowserReceivesTheLinkBeforeAnyOtherApp() {
		FragmentActivity a = host();
		installBankApp(a);
		installViewer(a, BROWSER, "http", "https");
		installTorBrowser(a, BrowserGuard.TOR_BROWSER);
		Intent i = click(a, dialog(a, BANK_LINK),
				DialogInterface.BUTTON_POSITIVE);
		assertNotNull(i);
		assertEquals(BrowserGuard.TOR_BROWSER, i.getPackage());
		assertEquals(BANK_LINK, i.getDataString());
	}

	@Test
	public void anAppThatClaimsOneDomainIsNeverTakenForABrowser() {
		FragmentActivity a = host();
		installBankApp(a);
		assertNull(BrowserGuard.defaultBrowserPackage(a));
		assertNull(click(a, dialog(a, BANK_LINK),
				DialogInterface.BUTTON_POSITIVE));
		assertEquals(a.getString(R.string.updates_no_browser), toast());
	}

	@Test
	public void anAppThatOpensOnlySecureLinksIsNotTakenForABrowser() {
		FragmentActivity a = host();
		installViewer(a, BANK_APP, "https");
		assertNull(BrowserGuard.defaultBrowserPackage(a));
		assertNull(click(a, dialog(a, BANK_LINK),
				DialogInterface.BUTTON_POSITIVE));
		assertEquals(a.getString(R.string.updates_no_browser), toast());
	}

	@Test
	public void withoutADefaultTheSystemAsksOnlyWhenEveryChoiceIsABrowser() {
		FragmentActivity a = host();
		installViewer(a, BROWSER, "http", "https");
		installViewer(a, OTHER_BROWSER, "http", "https");
		noBrowserIsTheDefault(a);
		assertNull(BrowserGuard.defaultBrowserPackage(a));
		Intent i = click(a, dialog(a, BANK_LINK),
				DialogInterface.BUTTON_POSITIVE);
		assertNotNull(i);
		assertNull(i.getPackage());
		assertNull(i.getComponent());
		assertEquals(BANK_LINK, i.getDataString());
		installBankApp(a);
		assertNull(click(a, dialog(a, BANK_LINK),
				DialogInterface.BUTTON_POSITIVE));
		assertEquals(a.getString(R.string.updates_no_browser), toast());
		Intent elsewhere = click(a, dialog(a, "https://example.org/page"),
				DialogInterface.BUTTON_POSITIVE);
		assertNotNull(elsewhere);
		assertNull(elsewhere.getPackage());
	}

	@Test
	public void withoutTorBrowserTheDefaultBrowserIsNamed() {
		FragmentActivity a = host();
		installViewer(a, "com.example.browser", "http", "https");
		assertNull(BrowserGuard.torBrowserPackage(a));
		assertFalse(BrowserGuard.hasTorBrowser(a));
		AlertDialog d = dialog(a, "https://example.org/page");
		assertEquals(a.getString(R.string.browser_warning_open),
				button(d, DialogInterface.BUTTON_POSITIVE));
		assertEquals(a.getString(R.string.browser_tor_get),
				button(d, DialogInterface.BUTTON_NEGATIVE));
		String text = message(d);
		assertTrue(text, text.contains(
				a.getString(R.string.browser_warning_message)));
		assertTrue(text, text.contains(
				a.getString(R.string.browser_tor_install_hint)));
		assertTrue(text, text.endsWith("https://example.org/page"));
		Intent i = click(a, d, DialogInterface.BUTTON_POSITIVE);
		assertNotNull(i);
		assertEquals(BROWSER, i.getPackage());
		assertNull(i.getSelector());
		assertNull(i.getComponent());
		assertEquals("https://example.org/page", i.getDataString());
	}

	@Test
	public void withTorBrowserTheLinkIsHandedToItAlone() {
		FragmentActivity a = host();
		installViewer(a, "com.example.browser", "http", "https");
		installTorBrowser(a, BrowserGuard.TOR_BROWSER);
		assertEquals(BrowserGuard.TOR_BROWSER,
				BrowserGuard.torBrowserPackage(a));
		assertTrue(BrowserGuard.hasTorBrowser(a));
		AlertDialog d = dialog(a, "https://example.org/page");
		assertEquals(a.getString(R.string.browser_tor_open),
				button(d, DialogInterface.BUTTON_POSITIVE));
		assertEquals(a.getString(android.R.string.cancel),
				button(d, DialogInterface.BUTTON_NEGATIVE));
		String text = message(d);
		assertTrue(text, text.startsWith(
				a.getString(R.string.browser_tor_message)));
		assertFalse(text, text.contains(
				a.getString(R.string.browser_warning_message)));
		assertTrue(text, text.endsWith("https://example.org/page"));
		Intent i = click(a, d, DialogInterface.BUTTON_POSITIVE);
		assertNotNull(i);
		assertEquals(Intent.ACTION_VIEW, i.getAction());
		assertEquals(BrowserGuard.TOR_BROWSER, i.getPackage());
		assertNull(i.getSelector());
		assertNull(i.getComponent());
		assertTrue(i.hasCategory(Intent.CATEGORY_BROWSABLE));
		assertEquals("https://example.org/page", i.getDataString());
	}

	@Test
	public void aTorBrowserRemovedAfterTheDialogIsNotReplacedByAnotherBrowser() {
		FragmentActivity a = host();
		installViewer(a, "com.example.browser", "http", "https");
		installTorBrowser(a, BrowserGuard.TOR_BROWSER);
		AlertDialog d = dialog(a, "https://example.org/page");
		assertEquals(a.getString(R.string.browser_tor_open),
				button(d, DialogInterface.BUTTON_POSITIVE));
		shadowOf(a.getPackageManager()).removeActivity(new ComponentName(
				BrowserGuard.TOR_BROWSER, BrowserGuard.TOR_BROWSER + ".Main"));
		assertNull(BrowserGuard.torBrowserPackage(a));
		assertNull("no implicit intent is started",
				click(a, d, DialogInterface.BUTTON_POSITIVE));
		AlertDialog warning = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(warning);
		assertNotSame(d, warning);
		assertTrue(warning.isShowing());
		String text = message(warning);
		assertTrue(text, text.contains(
				a.getString(R.string.browser_warning_message)));
		assertTrue(text, text.contains(
				a.getString(R.string.browser_tor_install_hint)));
		assertTrue(text, text.endsWith("https://example.org/page"));
		assertEquals(a.getString(R.string.browser_warning_open),
				button(warning, DialogInterface.BUTTON_POSITIVE));
		assertEquals(a.getString(R.string.browser_tor_get),
				button(warning, DialogInterface.BUTTON_NEGATIVE));
		Intent i = click(a, warning, DialogInterface.BUTTON_POSITIVE);
		assertNotNull(i);
		assertEquals(BROWSER, i.getPackage());
		assertEquals("https://example.org/page", i.getDataString());
	}

	@Test
	public void theStableTorBrowserIsPreferredOverTheAlpha() {
		FragmentActivity a = host();
		installTorBrowser(a, BrowserGuard.TOR_BROWSER_ALPHA);
		assertEquals(BrowserGuard.TOR_BROWSER_ALPHA,
				BrowserGuard.torBrowserPackage(a));
		Intent i = click(a, dialog(a, "http://example.onion/tx/abc"),
				DialogInterface.BUTTON_POSITIVE);
		assertNotNull(i);
		assertEquals(BrowserGuard.TOR_BROWSER_ALPHA, i.getPackage());
		installTorBrowser(a, BrowserGuard.TOR_BROWSER);
		assertEquals(BrowserGuard.TOR_BROWSER,
				BrowserGuard.torBrowserPackage(a));
	}

	@Test
	public void otherSchemesAreRefusedAlsoWithTorBrowser() {
		String[] bad = {"tel:+15551234", "market://details?id=x",
				"fdroidrepos://example.org/repo", "content://x/y",
				"intent://x#Intent;scheme=http;end", "javascript:alert(1)",
				"zerion://channel/abc", "https://", "mailto:a@example.org"};
		for (String url : bad) {
			FragmentActivity a = host();
			installTorBrowser(a, BrowserGuard.TOR_BROWSER);
			assertNull(url, click(a, dialog(a, url),
					DialogInterface.BUTTON_POSITIVE));
		}
	}

	@Test
	public void getTorBrowserFromPlayIsExplicitToThePlayStore() {
		FragmentActivity a = host();
		installedFrom(a, BrowserGuard.PLAY_STORE);
		installViewer(a, BrowserGuard.PLAY_STORE, "market");
		Intent i = click(a, dialog(a, "https://example.org/"),
				DialogInterface.BUTTON_NEGATIVE);
		assertNotNull(i);
		assertEquals(Intent.ACTION_VIEW, i.getAction());
		assertEquals(BrowserGuard.PLAY_STORE, i.getPackage());
		assertEquals(BrowserGuard.TOR_BROWSER_PLAY, i.getDataString());
		assertNull(i.getSelector());
		assertNull(i.getComponent());
	}

	@Test
	public void getTorBrowserWithoutThePlayStoreFallsBackToTheWebPage() {
		FragmentActivity a = host();
		installedFrom(a, BrowserGuard.PLAY_STORE);
		installViewer(a, "com.example.browser", "http", "https");
		assertNull(click(a, dialog(a, "https://example.org/"),
				DialogInterface.BUTTON_NEGATIVE));
		AlertDialog second = (AlertDialog) ShadowDialog.getLatestDialog();
		String text = message(second);
		assertTrue(text, text.endsWith(BrowserGuard.TOR_BROWSER_PLAY_WEB));
		assertFalse(text, text.contains(
				a.getString(R.string.browser_tor_install_hint)));
		assertEquals(a.getString(android.R.string.cancel),
				button(second, DialogInterface.BUTTON_NEGATIVE));
		Intent i = click(a, second, DialogInterface.BUTTON_POSITIVE);
		assertNotNull(i);
		assertEquals(BROWSER, i.getPackage());
		assertEquals(BrowserGuard.TOR_BROWSER_PLAY_WEB, i.getDataString());
	}

	@Test
	public void getTorBrowserFromFdroidHandsTheRepositoryToTheClient() {
		FragmentActivity a = host();
		installedFrom(a, FDROID);
		installViewer(a, FDROID, "fdroidrepos", "fdroidrepo");
		installViewer(a, DROIDIFY, "fdroidrepos");
		Intent i = click(a, dialog(a, "https://example.org/"),
				DialogInterface.BUTTON_NEGATIVE);
		assertNotNull(i);
		assertEquals(Intent.ACTION_VIEW, i.getAction());
		assertEquals(FDROID, i.getPackage());
		assertEquals(BrowserGuard.GUARDIAN_REPO, i.getDataString());
		assertEquals("fdroidrepos", i.getData().getScheme());
		assertEquals("guardianproject.info", i.getData().getHost());
		assertEquals(BrowserGuard.GUARDIAN_REPO_FINGERPRINT,
				i.getData().getQueryParameter("fingerprint"));
		assertNull(i.getSelector());
		assertNull(i.getComponent());
	}

	@Test
	public void anotherFdroidClientIsUsedWhenTheInstallerIsGone() {
		FragmentActivity a = host();
		installedFrom(a, "org.fdroid.basic");
		installViewer(a, DROIDIFY, "fdroidrepos");
		Intent i = click(a, dialog(a, "https://example.org/"),
				DialogInterface.BUTTON_NEGATIVE);
		assertNotNull(i);
		assertEquals(DROIDIFY, i.getPackage());
		assertEquals(BrowserGuard.GUARDIAN_REPO, i.getDataString());
	}

	@Test
	public void getTorBrowserWithoutAnFdroidClientOpensTheRepositoryPage() {
		FragmentActivity a = host();
		installedFrom(a, FDROID);
		installViewer(a, "com.example.browser", "http", "https");
		assertNull(click(a, dialog(a, "https://example.org/"),
				DialogInterface.BUTTON_NEGATIVE));
		AlertDialog second = (AlertDialog) ShadowDialog.getLatestDialog();
		assertTrue(message(second).endsWith(BrowserGuard.GUARDIAN_PAGE));
		Intent i = click(a, second, DialogInterface.BUTTON_POSITIVE);
		assertNotNull(i);
		assertEquals(BROWSER, i.getPackage());
		assertEquals(BrowserGuard.GUARDIAN_PAGE, i.getDataString());
	}

	@Test
	public void getTorBrowserFromAnotherSourceWarnsBeforeTheDownloadPage() {
		for (String installer : new String[] {null, "dev.imranr.obtainium",
				"com.aurora.store"}) {
			FragmentActivity a = host();
			if (installer != null) installedFrom(a, installer);
			installViewer(a, "com.example.browser", "http", "https");
			assertNull(click(a, dialog(a, "https://example.org/"),
					DialogInterface.BUTTON_NEGATIVE));
			AlertDialog second = (AlertDialog) ShadowDialog.getLatestDialog();
			String text = message(second);
			assertTrue(text, text.contains(
					a.getString(R.string.browser_warning_message)));
			assertTrue(text, text.endsWith(BrowserGuard.TOR_BROWSER_DOWNLOAD));
			assertEquals(a.getString(android.R.string.cancel),
					button(second, DialogInterface.BUTTON_NEGATIVE));
			Intent i = click(a, second, DialogInterface.BUTTON_POSITIVE);
			assertNotNull(i);
			assertEquals(BROWSER, i.getPackage());
			assertEquals(BrowserGuard.TOR_BROWSER_DOWNLOAD, i.getDataString());
		}
	}

	@Test
	public void theOfferDialogLeadsToGetTorBrowser() {
		FragmentActivity a = host();
		installedFrom(a, BrowserGuard.PLAY_STORE);
		installViewer(a, BrowserGuard.PLAY_STORE, "market");
		BrowserGuard.offerTorBrowser(a, "Copied");
		shadowOf(Looper.getMainLooper()).idle();
		AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(d);
		String text = message(d);
		assertTrue(text, text.startsWith("Copied"));
		assertTrue(text, text.endsWith(
				a.getString(R.string.browser_tor_install_hint)));
		Intent i = click(a, d, DialogInterface.BUTTON_POSITIVE);
		assertNotNull(i);
		assertEquals(BrowserGuard.PLAY_STORE, i.getPackage());
		assertEquals(BrowserGuard.TOR_BROWSER_PLAY, i.getDataString());
	}

	@Test
	public void noStoreIntentIsEverImplicit() {
		for (String installer : new String[] {BrowserGuard.PLAY_STORE, FDROID,
				DROIDIFY, "org.fdroid.basic", null}) {
			FragmentActivity a = host();
			if (installer != null) installedFrom(a, installer);
			assertNull(installer, click(a, dialog(a, "https://example.org/"),
					DialogInterface.BUTTON_NEGATIVE));
		}
	}
}
