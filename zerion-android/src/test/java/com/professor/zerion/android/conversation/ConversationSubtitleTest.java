package com.professor.zerion.android.conversation;

import com.professor.zerion.R;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ConversationSubtitleTest {

	@Test
	public void aContactReachedOverTheInternetIsJustOnline() {
		assertEquals(R.string.conversation_subtitle_online,
				ConversationActivity.onlineSubtitle(true, false));
		assertEquals(R.string.conversation_subtitle_online,
				ConversationActivity.onlineSubtitle(true, true));
		assertEquals(R.string.conversation_subtitle_online,
				ConversationActivity.onlineSubtitle(false, false));
	}

	@Test
	public void aContactReachedOnlyNearbyStillSaysMesh() {
		assertEquals(R.string.conversation_subtitle_online_mesh,
				ConversationActivity.onlineSubtitle(false, true));
	}
}
