package com.professor.zerion.android.channel;

import android.app.Application;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.widget.TextView;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Comparator;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelInviteSpanSchemeTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String REST = "://channel/abc2/def7?o=xyz";

	private static final String[] SCHEMES = {
			"zerion", "Zerion", "ZERION", "zErIoN"
	};

	private static ClickableSpan[] spansInOrder(Spanned text) {
		ClickableSpan[] spans =
				text.getSpans(0, text.length(), ClickableSpan.class);
		Arrays.sort(spans, Comparator.comparingInt(text::getSpanStart));
		return spans;
	}

	@Test
	public void aChatLinkWithAnyCaseOfTheSchemeOpensTheSameInvite() {
		Application app = ApplicationProvider.getApplicationContext();
		for (String scheme : SCHEMES) {
			TextView view = new TextView(app);
			assertTrue(scheme, ChannelInviteSpanUtil.apply(view,
					"Join my channel:\n" + scheme + REST));
			ClickableSpan[] spans = spansInOrder((Spanned) view.getText());
			assertEquals(scheme, 3, spans.length);
			for (int k = 0; k < 2; k++) {
				spans[k].onClick(view);
				Intent next = shadowOf(app).getNextStartedActivity();
				assertNotNull(scheme, next);
				assertEquals(ChannelInviteHandlerActivity.class.getName(),
						next.getComponent().getClassName());
				assertEquals(scheme, "zerion" + REST, next.getDataString());
			}
			spans[2].onClick(view);
			ClipboardManager cm = (ClipboardManager)
					app.getSystemService(Context.CLIPBOARD_SERVICE);
			assertEquals(scheme, "zerion" + REST, String.valueOf(
					cm.getPrimaryClip().getItemAt(0).getText()));
		}
	}

	@Test
	public void aLookalikeSchemeIsNotALink() {
		Application app = ApplicationProvider.getApplicationContext();
		assertFalse(ChannelInviteSpanUtil.apply(new TextView(app),
				"zer\u0131on" + REST));
		assertFalse(ChannelInviteSpanUtil.apply(new TextView(app),
				"ZER\u0130ON" + REST));
	}
}
