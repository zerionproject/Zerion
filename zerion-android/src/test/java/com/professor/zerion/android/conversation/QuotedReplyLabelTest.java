package com.professor.zerion.android.conversation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class QuotedReplyLabelTest {

	@Test
	public void theQuoteIsLabelledByItsAuthorWhicheverWayTheReplyWent() {
		assertEquals("Bob: hi", ConversationItemViewHolder.quoteLine(false,
				"You", "Bob", "hi"));
		assertEquals("You: hi", ConversationItemViewHolder.quoteLine(true,
				"You", "Bob", "hi"));
		assertEquals("hi", ConversationItemViewHolder.quoteLine(null, "You",
				"Bob", "hi"));
	}
}
