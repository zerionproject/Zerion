package com.professor.zerion.android.grouptr;

import android.graphics.Bitmap;

import com.professor.zerion.android.conversation.DeletedContentEvictor;
import com.professor.zerion.android.conversation.voice.VoiceChunkAssembler;

import org.zerionproject.app.api.autodelete.event.ConversationMessagesDeletedEvent;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.messaging.event.GroupTrLocalStateChangedEvent;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.zerionproject.app.api.messaging.event.GroupTrLocalStateChangedEvent.Kind.REMOVED;
import static org.zerionproject.app.api.messaging.event.GroupTrLocalStateChangedEvent.Kind.UPDATED;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class DeletedMediaEvictionTest {

	private final byte[] removedGroup = getRandomId();
	private final byte[] keptGroup = getRandomId();
	private final byte[] sender = getRandomId();
	private final VoiceChunkAssembler voiceAssembler =
			new VoiceChunkAssembler();
	private final AtomicInteger imageMemoryCleared = new AtomicInteger();
	private final DeletedContentEvictor evictor =
			new DeletedContentEvictor(voiceAssembler,
					imageMemoryCleared::incrementAndGet);

	@After
	public void tearDown() {
		GroupTrMediaCache.clear();
	}

	@Test
	public void removingAGroupEvictsOnlyThatGroupsMedia() {
		GroupTrPost gone = post(removedGroup, 1L);
		GroupTrPost kept = post(keptGroup, 1L);
		cache(gone);
		cache(kept);
		assertEquals(4, GroupTrMediaCache.size());

		evictor.eventOccurred(
				new GroupTrLocalStateChangedEvent(removedGroup, UPDATED));
		assertEquals(4, GroupTrMediaCache.size());

		evictor.eventOccurred(
				new GroupTrLocalStateChangedEvent(removedGroup, REMOVED));

		assertNull(image(gone));
		assertNull(video(gone));
		assertNotNull(image(kept));
		assertNotNull(video(kept));
	}

	@Test
	public void anExpiredPostLosesItsMediaAndNoOtherPostDoes() {
		GroupTrPost expired = post(removedGroup, 1L);
		GroupTrPost later = post(removedGroup, 2L);
		cache(expired);
		cache(later);

		GroupTrMediaCache.evictPost(expired);

		assertNull(image(expired));
		assertNull(video(expired));
		assertNotNull(image(later));
		assertNotNull(video(later));
	}

	@Test
	public void removingAContactForgetsItsVoiceMemos() {
		ContactId removed = new ContactId(1);
		ContactId kept = new ContactId(2);
		String memo = "00000000000000aa";
		voiceAssembler.putComplete(removed, memo, "[VOICE:1000:abc]");
		voiceAssembler.putComplete(kept, memo, "[VOICE:1000:abc]");

		evictor.eventOccurred(new ContactRemovedEvent(removed));

		assertNull(voiceAssembler.getReassembled(removed, true, memo));
		assertNotNull(voiceAssembler.getReassembled(kept, true, memo));
		assertEquals(1, imageMemoryCleared.get());
	}

	@Test
	public void deletingMessagesDropsTheChatsVoiceMemosAndDecodedImages() {
		ContactId deletedFrom = new ContactId(1);
		ContactId kept = new ContactId(2);
		String memo = "00000000000000bb";
		voiceAssembler.putComplete(deletedFrom, memo, "[VOICE:1000:abc]");
		voiceAssembler.putComplete(kept, memo, "[VOICE:1000:abc]");

		evictor.eventOccurred(new ConversationMessagesDeletedEvent(
				deletedFrom, singletonList(new MessageId(getRandomId()))));

		assertNull(voiceAssembler.getReassembled(deletedFrom, true, memo));
		assertNotNull(voiceAssembler.getReassembled(kept, true, memo));
		assertEquals(1, imageMemoryCleared.get());
	}

	private GroupTrPost post(byte[] groupId, long timestamp) {
		return new GroupTrPost(groupId, sender, "Sender", new byte[] {1},
				timestamp, 3L, false);
	}

	private static void cache(GroupTrPost p) {
		GroupTrMediaCache.put(GroupTrMediaCache.key(p,
				GroupTrMediaCache.IMAGE), bitmap());
		GroupTrMediaCache.put(GroupTrMediaCache.key(p,
				GroupTrMediaCache.VIDEO), bitmap());
	}

	private static Bitmap image(GroupTrPost p) {
		return GroupTrMediaCache.get(GroupTrMediaCache.key(p,
				GroupTrMediaCache.IMAGE));
	}

	private static Bitmap video(GroupTrPost p) {
		return GroupTrMediaCache.get(GroupTrMediaCache.key(p,
				GroupTrMediaCache.VIDEO));
	}

	private static Bitmap bitmap() {
		return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
	}
}
