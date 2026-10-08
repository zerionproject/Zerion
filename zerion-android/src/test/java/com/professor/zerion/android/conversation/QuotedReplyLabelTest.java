package com.professor.zerion.android.conversation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class QuotedReplyLabelTest {

	@Test
	public void theQuoteIsLabelledByItsAuthorWhicheverWayTheReplyWent() {
		assertEquals("Bob", ConversationItemViewHolder.quoteAuthor(false,
				"You", "Bob"));
		assertEquals("You", ConversationItemViewHolder.quoteAuthor(true,
				"You", "Bob"));
		assertNull(ConversationItemViewHolder.quoteAuthor(null, "You",
				"Bob"));
	}
}
