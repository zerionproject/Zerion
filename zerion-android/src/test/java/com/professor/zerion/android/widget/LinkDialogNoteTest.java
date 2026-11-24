package com.professor.zerion.android.widget;

import android.content.Context;
import android.os.Looper;
import android.widget.TextView;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import androidx.fragment.app.FragmentActivity;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
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
}
