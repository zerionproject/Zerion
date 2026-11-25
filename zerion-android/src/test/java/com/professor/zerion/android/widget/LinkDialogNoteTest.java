package com.professor.zerion.android.widget;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
import android.widget.TextView;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class LinkDialogNoteTest {

	private static String sheetText(String url) {
		FragmentActivity a = Robolectric.buildActivity(FragmentActivity.class)
				.setup().get();
		a.setTheme(R.style.ZerionTheme_NoActionBar);
		LinkDialogFragment f = LinkDialogFragment.newInstance(url);
		f.show(a.getSupportFragmentManager(), f.getUniqueTag());
		shadowOf(Looper.getMainLooper()).idle();
		TextView urlView = f.requireView().findViewById(R.id.urlView);
		return urlView.getText().toString();
	}

	private static String note() {
		Context ctx = ApplicationProvider.getApplicationContext();
		return ctx.getString(R.string.link_host_other_alphabet);
	}

	@Test
	public void aHostInAnotherAlphabetIsNamedAsSuch() {
		String text = sheetText("https://xn--80ak6aa92e.com/login");
		assertTrue(text, text.contains("xn--80ak6aa92e.com"));
		assertTrue(text, text.contains(note()));
	}

	@Test
	public void anAsciiHostCarriesNoNote() {
		String text = sheetText("https://example.org/login");
		assertTrue(text, text.contains("example.org"));
		assertFalse(text, text.contains(note()));
	}

	@Test
	public void aHostThatIsRespelledInPlainLettersCarriesTheNote() {
		for (String url : new String[] {"https://sparka\u00DFe.de/login",
				"https://sparka\u200Dsse.de/login",
				"https://\uFF53parkasse.de/login"}) {
			assertEquals(url, "https://sparkasse.de/login\n" + note(),
					sheetText(url));
		}
		String sigma = sheetText("https://\u03BF\u03B4\u03CC\u03C2.gr");
		assertTrue(sigma, sigma.startsWith("https://xn--"));
		assertTrue(sigma, sigma.endsWith(".gr\n" + note()));
	}

	@Test
	public void userInfoInFrontOfABracketedAddressIsNotOnTheSheet() {
		assertEquals("https://[2001:db8::1]/login", sheetText(
				"https://accounts.example.com@[2001:db8::1]/login"));
	}

	@Test
	public void theSheetOpensTheAddressItShows() throws Exception {
		String url = "https://bank.example@sparka\u00DFe.de/login";
		FragmentActivity a = Robolectric.buildActivity(FragmentActivity.class)
				.setup().get();
		a.setTheme(R.style.ZerionTheme_NoActionBar);
		android.content.ComponentName browser = new android.content
				.ComponentName("com.example.browser", "com.example.Main");
		android.content.IntentFilter web =
				new android.content.IntentFilter(Intent.ACTION_VIEW);
		web.addCategory(Intent.CATEGORY_DEFAULT);
		web.addCategory(Intent.CATEGORY_BROWSABLE);
		web.addDataScheme("http");
		web.addDataScheme("https");
		shadowOf(a.getPackageManager()).addActivityIfNotPresent(browser);
		shadowOf(a.getPackageManager()).addIntentFilterForActivity(browser,
				web);
		LinkDialogFragment f = LinkDialogFragment.newInstance(url);
		f.show(a.getSupportFragmentManager(), f.getUniqueTag());
		shadowOf(Looper.getMainLooper()).idle();
		TextView urlView = f.requireView().findViewById(R.id.urlView);
		String shown = urlView.getText().toString();
		f.requireView().findViewById(R.id.openButton).performClick();
		shadowOf(Looper.getMainLooper()).idle();
		AlertDialog warning = (AlertDialog) ShadowDialog.getLatestDialog();
		TextView message = warning.findViewById(android.R.id.message);
		assertTrue(message.getText().toString().endsWith(shown));
		warning.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		shadowOf(Looper.getMainLooper()).idle();
		Intent opened = shadowOf(a).getNextStartedActivity();
		assertNotNull(opened);
		assertEquals("https://sparkasse.de/login", opened.getDataString());
		assertEquals(shown, opened.getDataString() + "\n" + note());
	}
}
