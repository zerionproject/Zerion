package com.professor.zerion.android.channel;

import org.junit.Test;
import org.zerionproject.app.api.channel.ChannelConstants;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

public class ChannelCommentNameBoundTest {

	private static String repeat(char c, int n) {
		StringBuilder sb = new StringBuilder(n);
		for (int i = 0; i < n; i++) sb.append(c);
		return sb.toString();
	}

	private static String managerRule(String name) throws Exception {
		Method m = Class.forName(
				"org.zerionproject.app.channel.ChannelManagerImpl")
				.getDeclaredMethod("boundedName", String.class);
		m.setAccessible(true);
		return (String) m.invoke(null, name);
	}

	private static void sameAsTheManager(String name, String expected)
			throws Exception {
		String signed = managerRule(name);
		assertEquals("the heading uses the name the comment is signed with",
				signed.trim(), ChannelCommentsActivity.sentName(name));
		assertEquals(expected, ChannelCommentsActivity.sentName(name));
	}

	@Test
	public void theHeadingShortensANameExactlyAsTheManagerSignsIt()
			throws Exception {
		int max = ChannelConstants.MAX_COMMENT_AUTHOR_NAME_CHARS;
		assertEquals(64, max);
		String grin = new String(Character.toChars(0x1F600));
		sameAsTheManager("Sam", "Sam");
		sameAsTheManager(repeat('a', max), repeat('a', max));
		sameAsTheManager(repeat('a', max + 1), repeat('a', max));
		sameAsTheManager(repeat('a', max - 1) + grin + "b",
				repeat('a', max - 1));
		sameAsTheManager(repeat('a', max - 2) + grin,
				repeat('a', max - 2) + grin);
		sameAsTheManager("  Sam  ", "Sam");
		sameAsTheManager(repeat('a', max - 1) + "   ", repeat('a', max - 1));
		sameAsTheManager("   " + repeat('a', max), repeat('a', max - 3));
		sameAsTheManager("", "");
	}
}
