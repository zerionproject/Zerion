package com.professor.zerion.android.util;

import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.view.textclassifier.TextClassifier;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.widget.DonationDialogFragment;
import com.professor.zerion.android.widget.LinkDialogFragment;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class LinkActionsStayInAppTest {

	private static FragmentActivity host() {
		FragmentActivity a = Robolectric.buildActivity(FragmentActivity.class)
				.setup().get();
		a.setTheme(R.style.ZerionTheme_NoActionBar);
		return a;
	}

	@Test
	public void aTextWithLinksOffersNoSystemOpenAction() {
		TextView v = new TextView(host());
		v.setTextIsSelectable(true);
		assertNotSame(TextClassifier.NO_OP, v.getTextClassifier());
		SpannableString s = new SpannableString("see https://example.org/");
		s.setSpan(new URLSpan("https://example.org/"), 4, s.length(),
				Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		v.setText(s);
		UiUtils.makeLinksClickable(v, url -> {
		});
		assertSame(TextClassifier.NO_OP, v.getTextClassifier());
	}

	@Test
	public void theLinkSheetOffersNoSystemOpenAction() {
		FragmentActivity a = host();
		LinkDialogFragment f =
				LinkDialogFragment.newInstance("https://example.org/");
		f.show(a.getSupportFragmentManager(), f.getUniqueTag());
		shadowOf(Looper.getMainLooper()).idle();
		TextView urlView = f.requireView().findViewById(R.id.urlView);
		assertSame(TextClassifier.NO_OP, urlView.getTextClassifier());
	}

	@Test
	public void theDonationAddressOffersNoSystemOpenAction() {
		FragmentActivity a = host();
		DonationDialogFragment f = DonationDialogFragment.newInstance();
		f.show(a.getSupportFragmentManager(), DonationDialogFragment.TAG);
		shadowOf(Looper.getMainLooper()).idle();
		TextView urlView = f.requireView().findViewById(R.id.donationUrl);
		assertSame(TextClassifier.NO_OP, urlView.getTextClassifier());
	}
}
