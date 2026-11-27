package com.professor.zerion.android.conversation;

import android.content.Context;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class LinkPreviewDomainLabelTest {

	private final Context ctx = ApplicationProvider.getApplicationContext();

	@Test
	public void aPlainHostIsShownAsItIs() {
		assertEquals("example.org", ConversationItemViewHolder
				.previewDomainLabel(ctx, "https://example.org/page"));
	}

	@Test
	public void aHostWrittenInAnotherAlphabetIsSpelledOutAndMarked() {
		assertEquals("xn--ypal-43d9g.com "
						+ ctx.getString(R.string.link_host_other_alphabet_mark),
				ConversationItemViewHolder.previewDomainLabel(ctx,
						"https://раypal.com/login"));
	}

	@Test
	public void aHostThatIsRespelledInPlainLettersIsMarked() {
		String marked = "sparkasse.de "
				+ ctx.getString(R.string.link_host_other_alphabet_mark);
		assertEquals(marked, ConversationItemViewHolder.previewDomainLabel(
				ctx, "https://sparka\u00DFe.de/login"));
		assertEquals(marked, ConversationItemViewHolder.previewDomainLabel(
				ctx, "https://spark\u200Dasse.de/login"));
	}

	@Test
	public void aLinkThatIsNotAWebLinkIsShownAsUnsupported() {
		assertEquals(ctx.getString(R.string.link_unsupported),
				ConversationItemViewHolder.previewDomainLabel(ctx,
						"mailto:someone@example.org"));
	}
}
