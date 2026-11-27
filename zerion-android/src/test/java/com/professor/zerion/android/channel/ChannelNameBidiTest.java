package com.professor.zerion.android.channel;

import android.content.Context;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelNameBidiTest {

	private final Context ctx = ApplicationProvider.getApplicationContext();

	@Test
	public void aChannelNameLosesItsDirectionControls() {
		assertEquals("News", ChannelNames.shown(ctx, "\u202ENews\u2066"));
		assertEquals(ctx.getString(R.string.channels_button),
				ChannelNames.shown(ctx, "\u202E\u2069 "));
	}

	@Test
	public void aNameInASentenceIsCleanedAndIsolated() {
		String rtl = "\u05D3\u05E0\u05D4";
		String question = ChannelInviteHandlerActivity.joinQuestion(ctx,
				true, "\u202E" + rtl);
		assertFalse(question, question.contains("\u202E"));
		assertTrue(question, question.contains("\u202B" + rtl + "\u202C"));
		String gone = ChannelInviteHandlerActivity.deletedMessage(ctx,
				"\u202E" + rtl);
		assertFalse(gone, gone.contains("\u202E"));
		assertTrue(gone, gone.contains("\u202B" + rtl + "\u202C"));
	}
}
