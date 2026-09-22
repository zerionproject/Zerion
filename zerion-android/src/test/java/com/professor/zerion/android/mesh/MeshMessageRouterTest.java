package com.professor.zerion.android.mesh;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.crypto.async.MeshSeenStore;
import org.zerionproject.core.test.TestUtils;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Random;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Dispatch of opened offline mesh messages: only a sender whose hybrid
 * identity matches a contact is routed, a text is delivered once and acked on
 * every repeat, a delivery that fails leaves the message unmarked so a retry
 * can land, malformed bodies and unknown types are dropped without touching
 * the store, and group records are deduplicated by content.
 */
public class MeshMessageRouterTest {

	private final Random random = new Random(89);
	private final ContactManager contactManager = mock(ContactManager.class);
	private final MessagingManager messagingManager =
			mock(MessagingManager.class);
	private final MeshSeenStore seenStore = mock(MeshSeenStore.class);
	private final MeshTextSender textSender = mock(MeshTextSender.class);
	private final MeshPresenceTracker presenceTracker =
			mock(MeshPresenceTracker.class);
	private final MeshAttachmentSender attachmentSender =
			mock(MeshAttachmentSender.class);
	private final ContactId contactId = new ContactId(7);

	private byte[] identity;
	private MeshMessageRouter router;

	@Before
	public void setUp() throws Exception {
		Author author = TestUtils.getAuthor();
		byte[] mlDsa = new byte[PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES];
		random.nextBytes(mlDsa);
		identity = new HybridSignaturePublicKey(
				author.getPublicKey().getEncoded(), mlDsa).getEncoded();
		Contact contact = new Contact(contactId, author,
				new AuthorId(TestUtils.getRandomId()), null, null, true, true,
				false, mlDsa);
		when(contactManager.getContacts())
				.thenReturn(Collections.singletonList(contact));
		router = new MeshMessageRouter(contactManager, messagingManager,
				seenStore, () -> textSender, presenceTracker,
				() -> attachmentSender, Runnable::run);
	}

	@Test
	public void aTextFromAContactIsDeliveredOnceAndAckedOnEveryRepeat()
			throws Exception {
		byte[] messageId = TestUtils.getRandomId();
		when(seenStore.checkAndMark(messageId)).thenReturn(false, true, true);
		byte[] payload = MeshPadding.pad(text(messageId, 1_000L, null,
				"hello"));

		assertTrue(router.onOfflineMessage(identity, MeshMessageRouter.MESH_TEXT,
				payload, 0L));
		assertTrue(router.onOfflineMessage(identity, MeshMessageRouter.MESH_TEXT,
				payload, 0L));
		assertTrue(router.onOfflineMessage(identity, MeshMessageRouter.MESH_TEXT,
				payload, 0L));

		verify(messagingManager, times(1)).receiveMeshMessage(eq(contactId),
				eq("hello"), eq(1_000L), eq(messageId), eq(null));
		verify(textSender, times(3)).sendAck(contactId, messageId);
		verify(presenceTracker, times(3)).markPresent(contactId);
	}

	@Test
	public void aReplyCarriesItsParentAndAFutureComposeTimeIsClamped()
			throws Exception {
		byte[] messageId = TestUtils.getRandomId();
		byte[] parent = TestUtils.getRandomId();
		long farFuture = System.currentTimeMillis() + 3_600_000L;
		when(seenStore.checkAndMark(messageId)).thenReturn(false);
		assertTrue(router.onOfflineMessage(identity, MeshMessageRouter.MESH_TEXT,
				MeshPadding.pad(text(messageId, farFuture, parent, "re")),
				0L));
		ArgumentCaptor<Long> ts = ArgumentCaptor.forClass(Long.class);
		verify(messagingManager).receiveMeshMessage(eq(contactId), eq("re"),
				ts.capture(), eq(messageId), eq(parent));
		assertTrue(ts.getValue() < farFuture);
		assertTrue(ts.getValue() <= System.currentTimeMillis());
	}

	@Test
	public void aFailedDeliveryUnmarksTheMessageAndSendsNoAck()
			throws Exception {
		byte[] messageId = TestUtils.getRandomId();
		when(seenStore.checkAndMark(messageId)).thenReturn(false);
		doThrow(new DbException()).when(messagingManager).receiveMeshMessage(
				any(), any(), anyLong(), any(), any());
		assertTrue(router.onOfflineMessage(identity, MeshMessageRouter.MESH_TEXT,
				MeshPadding.pad(text(messageId, 5L, null, "x")), 0L));
		verify(seenStore).unmark(messageId);
		verify(textSender, never()).sendAck(any(), any());
	}

	@Test
	public void aStoreFailureDeliversNothing() throws Exception {
		byte[] messageId = TestUtils.getRandomId();
		when(seenStore.checkAndMark(messageId)).thenThrow(new DbException());
		assertTrue(router.onOfflineMessage(identity, MeshMessageRouter.MESH_TEXT,
				MeshPadding.pad(text(messageId, 5L, null, "x")), 0L));
		verifyNoInteractions(messagingManager);
		verify(textSender, never()).sendAck(any(), any());
	}

	@Test
	public void anUnknownSenderIsDroppedBeforeAnythingIsTouched()
			throws Exception {
		byte[] stranger = identity.clone();
		stranger[40] ^= 1;
		byte[] payload = MeshPadding.pad(text(TestUtils.getRandomId(), 5L,
				null, "x"));
		assertFalse(router.onOfflineMessage(stranger,
				MeshMessageRouter.MESH_TEXT, payload, 0L));
		assertFalse(router.onOfflineMessage(new byte[0],
				MeshMessageRouter.MESH_TEXT, payload, 0L));
		verifyNoInteractions(messagingManager, seenStore, textSender,
				presenceTracker);
	}

	@Test
	public void anUnknownTypeIsRefusedAndAContactLookupFailureIsRefused()
			throws Exception {
		byte[] payload = MeshPadding.pad(text(TestUtils.getRandomId(), 5L,
				null, "x"));
		assertFalse(router.onOfflineMessage(identity, 3, payload, 0L));
		assertFalse(router.onOfflineMessage(identity, 99, payload, 0L));
		assertFalse(router.onOfflineMessage(identity, -1, payload, 0L));
		verifyNoInteractions(messagingManager, seenStore, textSender);
		when(contactManager.getContacts()).thenThrow(new DbException());
		MeshMessageRouter fresh = new MeshMessageRouter(contactManager,
				messagingManager, seenStore, () -> textSender,
				presenceTracker, () -> attachmentSender, Runnable::run);
		assertFalse(fresh.onOfflineMessage(identity,
				MeshMessageRouter.MESH_TEXT, payload, 0L));
	}

	@Test
	public void malformedTextBodiesAreDroppedWithoutTouchingTheStore()
			throws Exception {
		byte[] messageId = TestUtils.getRandomId();
		byte[][] bodies = {
				new byte[0],
				new byte[MeshTextSender.HEADER_BYTES],
				withParentLength(text(messageId, 5L, null, "x"), 5),
				withParentLength(text(messageId, 5L, null, "x"), 32),
				withParentLength(text(messageId, 5L, null, ""), 32),
				new byte[3],
		};
		for (byte[] body : bodies) {
			assertTrue(router.onOfflineMessage(identity,
					MeshMessageRouter.MESH_TEXT, MeshPadding.pad(body), 0L));
		}
		verifyNoInteractions(messagingManager, seenStore, textSender);
	}

	@Test
	public void anEmptyTextWithAHeaderIsStillAMessage() throws Exception {
		byte[] messageId = TestUtils.getRandomId();
		when(seenStore.checkAndMark(messageId)).thenReturn(false);
		byte[] body = text(messageId, 5L, null, "");
		assertEquals(MeshTextSender.HEADER_BYTES + 1, body.length);
		assertTrue(router.onOfflineMessage(identity,
				MeshMessageRouter.MESH_TEXT, MeshPadding.pad(body), 0L));
		verify(messagingManager).receiveMeshMessage(eq(contactId), eq(""),
				anyLong(), eq(messageId), eq(null));
	}

	@Test
	public void anAckMustBeExactlyOneMessageId() throws Exception {
		byte[] messageId = TestUtils.getRandomId();
		assertTrue(router.onOfflineMessage(identity, MeshMessageRouter.MESH_ACK,
				MeshPadding.pad(messageId), 0L));
		verify(textSender).onDelivered(contactId, new MessageId(messageId));
		assertTrue(router.onOfflineMessage(identity, MeshMessageRouter.MESH_ACK,
				MeshPadding.pad(new byte[31]), 0L));
		assertTrue(router.onOfflineMessage(identity, MeshMessageRouter.MESH_ACK,
				MeshPadding.pad(new byte[33]), 0L));
		assertTrue(router.onOfflineMessage(identity, MeshMessageRouter.MESH_ACK,
				MeshPadding.pad(new byte[0]), 0L));
		verify(textSender, times(1)).onDelivered(any(), any());
	}

	@Test
	public void groupRecordsAreDeduplicatedByContentAndNeedAComposeTime()
			throws Exception {
		when(seenStore.checkAndMark(any())).thenReturn(false, true);
		byte[] record = new byte[48];
		random.nextBytes(record);
		byte[] body = ByteBuffer.allocate(8 + record.length).putLong(77L)
				.put(record).array();
		assertTrue(router.onOfflineMessage(identity,
				MeshMessageRouter.MESH_GROUP_RECORD, MeshPadding.pad(body),
				0L));
		assertTrue(router.onOfflineMessage(identity,
				MeshMessageRouter.MESH_GROUP_RECORD, MeshPadding.pad(body),
				0L));
		verify(messagingManager, times(1)).receiveMeshGroupRecord(contactId,
				record, 77L);
		ArgumentCaptor<byte[]> dedup = ArgumentCaptor.forClass(byte[].class);
		verify(seenStore, times(2)).checkAndMark(dedup.capture());
		assertArrayEquals(dedup.getAllValues().get(0),
				dedup.getAllValues().get(1));
		assertEquals(32, dedup.getValue().length);

		byte[] zeroTime = ByteBuffer.allocate(8 + record.length).putLong(0L)
				.put(record).array();
		byte[] negativeTime = ByteBuffer.allocate(8 + record.length)
				.putLong(-5L).put(record).array();
		assertTrue(router.onOfflineMessage(identity,
				MeshMessageRouter.MESH_GROUP_RECORD, MeshPadding.pad(zeroTime),
				0L));
		assertTrue(router.onOfflineMessage(identity,
				MeshMessageRouter.MESH_GROUP_RECORD,
				MeshPadding.pad(negativeTime), 0L));
		assertTrue(router.onOfflineMessage(identity,
				MeshMessageRouter.MESH_GROUP_RECORD, MeshPadding.pad(new byte[8]),
				0L));
		verify(messagingManager, times(1)).receiveMeshGroupRecord(any(),
				any(), anyLong());
	}

	@Test
	public void aFailedGroupRecordDeliveryIsUnmarked() throws Exception {
		when(seenStore.checkAndMark(any())).thenReturn(false);
		doThrow(new DbException()).when(messagingManager)
				.receiveMeshGroupRecord(any(), any(), anyLong());
		byte[] body = ByteBuffer.allocate(12).putLong(9L).putInt(1).array();
		assertTrue(router.onOfflineMessage(identity,
				MeshMessageRouter.MESH_GROUP_RECORD, MeshPadding.pad(body),
				0L));
		verify(seenStore).unmark(any());
	}

	@Test
	public void unpaddedAndTruncatedPayloadsAreTreatedAsEmpty()
			throws Exception {
		assertTrue(router.onOfflineMessage(identity,
				MeshMessageRouter.MESH_TEXT, new byte[0], 0L));
		assertTrue(router.onOfflineMessage(identity,
				MeshMessageRouter.MESH_TEXT, new byte[] {0, 0, 1, 0, 1}, 0L));
		assertNull(null);
		verifyNoInteractions(messagingManager, seenStore, textSender);
	}

	private static byte[] text(byte[] messageId, long composeMs,
			byte[] parent, String text) {
		byte[] utf8 = text.getBytes(UTF_8);
		int parentLen = parent == null ? 0 : parent.length;
		ByteBuffer b = ByteBuffer.allocate(MeshTextSender.HEADER_BYTES + 1
				+ parentLen + utf8.length);
		b.put(messageId).putLong(composeMs).put((byte) parentLen);
		if (parent != null) b.put(parent);
		b.put(utf8);
		return b.array();
	}

	private static byte[] withParentLength(byte[] body, int parentLen) {
		byte[] out = body.clone();
		out[MeshTextSender.HEADER_BYTES] = (byte) parentLen;
		return out;
	}
}
