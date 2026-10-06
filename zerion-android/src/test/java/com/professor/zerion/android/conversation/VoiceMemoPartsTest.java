package com.professor.zerion.android.conversation;

import org.zerionproject.core.api.sync.MessageId;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class VoiceMemoPartsTest {

	private static String part(String memoId, int seq) {
		return "[VMP:1:" + memoId + ":" + seq + ":2:1000:c" + seq + "]";
	}

	@Test
	public void deletingTheContactsMemoKeepsAMemoThisDeviceSentUnderTheSameId() {
		String memo = "00000000000000aa";
		MessageId mine0 = new MessageId(getRandomId());
		MessageId mine1 = new MessageId(getRandomId());
		MessageId theirs0 = new MessageId(getRandomId());
		MessageId theirs1 = new MessageId(getRandomId());
		Map<MessageId, String> chat = new HashMap<>();
		chat.put(mine0, part(memo, 0));
		chat.put(mine1, part(memo, 1));
		chat.put(theirs0, part(memo, 0));
		chat.put(theirs1, part(memo, 1));
		Set<MessageId> sent = new HashSet<>(asList(mine0, mine1));

		VoiceMemoParts.Expansion x = VoiceMemoParts.expand(
				Collections.singletonList(theirs0),
				Collections.singletonMap(theirs0, part(memo, 0)), chat,
				sent::contains);

		assertEquals(new HashSet<>(asList(theirs0, theirs1)), x.messages);
		assertEquals(Collections.emptySet(), x.sentMemoIds);

		VoiceMemoParts.Expansion own = VoiceMemoParts.expand(
				Collections.singletonList(mine1),
				Collections.singletonMap(mine1, part(memo, 1)), chat,
				sent::contains);

		assertEquals(new HashSet<>(asList(mine0, mine1)), own.messages);
		assertEquals(Collections.singleton(memo), own.sentMemoIds);
	}

	@Test
	public void keysKeepTheDirection() {
		String key = VoiceMemoParts.key(true, "00000000000000bb");
		assertEquals(true, VoiceMemoParts.isLocalKey(key));
		assertEquals("00000000000000bb", VoiceMemoParts.memoIdOf(key));
		String other = VoiceMemoParts.key(false, "00000000000000bb");
		assertEquals(false, VoiceMemoParts.isLocalKey(other));
		assertEquals("00000000000000bb", VoiceMemoParts.memoIdOf(other));
	}
}
