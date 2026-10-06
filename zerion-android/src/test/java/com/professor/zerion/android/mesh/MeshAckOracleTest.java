package com.professor.zerion.android.mesh;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.crypto.async.MeshSeenStore;
import org.zerionproject.core.test.TestUtils;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Random;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MeshAckOracleTest {

	private final Random random = new Random(41);
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
	public void aMessageCarriedByAnOldEnvelopeIsDeliveredButNotAcknowledged()
			throws Exception {
		byte[] messageId = TestUtils.getRandomId();
		when(seenStore.checkAndMark(messageId)).thenReturn(false, true);
		long hourAgo = System.currentTimeMillis() - 3_600_000L;
		router.onOfflineMessage(identity, MeshMessageRouter.MESH_TEXT,
				MeshPadding.pad(text(messageId, "late")), hourAgo);
		router.onOfflineMessage(identity, MeshMessageRouter.MESH_TEXT,
				MeshPadding.pad(text(messageId, "late")), hourAgo);
		verify(messagingManager).receiveMeshMessage(eq(contactId),
				eq("late"), anyLong(), eq(messageId), eq(null));
		verify(textSender, never()).sendAck(any(), any());
	}

	@Test
	public void copiesOfOneMessageAreAcknowledgedOnceInAWhile()
			throws Exception {
		byte[] messageId = TestUtils.getRandomId();
		when(seenStore.checkAndMark(messageId)).thenReturn(false, true);
		for (int i = 0; i < 6; i++) {
			router.onOfflineMessage(identity, MeshMessageRouter.MESH_TEXT,
					MeshPadding.pad(text(messageId, "hi")),
					System.currentTimeMillis());
		}
		verify(textSender, times(1)).sendAck(contactId, messageId);
	}

	@Test
	public void anAttachmentCarriedByAnOldEnvelopeIsNotAcknowledged()
			throws Exception {
		byte[] attachId = new byte[MeshAttachmentSender.ATTACH_ID_BYTES];
		random.nextBytes(attachId);
		when(seenStore.checkAndMark(attachId)).thenReturn(false, true);
		long hourAgo = System.currentTimeMillis() - 3_600_000L;
		byte[] data = new byte[100];
		random.nextBytes(data);
		router.onOfflineMessage(identity,
				MeshMessageRouter.MESH_ATTACH_MANIFEST,
				MeshPadding.pad(manifest(attachId, data.length)), hourAgo);
		router.onOfflineMessage(identity, MeshMessageRouter.MESH_ATTACH_CHUNK,
				MeshPadding.pad(chunk(attachId, data)), hourAgo);
		verify(messagingManager).receiveMeshAttachment(eq(contactId),
				eq("image/jpeg"), any(), anyLong());
		verify(attachmentSender, never()).sendAck(any(), any());
	}

	@Test
	public void aFreshAttachmentIsAcknowledgedOnce() throws Exception {
		byte[] attachId = new byte[MeshAttachmentSender.ATTACH_ID_BYTES];
		random.nextBytes(attachId);
		when(seenStore.checkAndMark(attachId)).thenReturn(false, true);
		byte[] data = new byte[100];
		random.nextBytes(data);
		for (int i = 0; i < 3; i++) {
			long now = System.currentTimeMillis();
			router.onOfflineMessage(identity,
					MeshMessageRouter.MESH_ATTACH_MANIFEST,
					MeshPadding.pad(manifest(attachId, data.length)), now);
			router.onOfflineMessage(identity,
					MeshMessageRouter.MESH_ATTACH_CHUNK,
					MeshPadding.pad(chunk(attachId, data)), now);
		}
		verify(attachmentSender, times(1)).sendAck(contactId, attachId);
	}

	private static byte[] text(byte[] messageId, String text) {
		byte[] utf8 = text.getBytes(UTF_8);
		return ByteBuffer.allocate(MeshTextSender.HEADER_BYTES + 1
				+ utf8.length).put(messageId).putLong(1_000L).put((byte) 0)
				.put(utf8).array();
	}

	private static byte[] manifest(byte[] attachId, int size) {
		byte[] ct = "image/jpeg".getBytes(UTF_8);
		return ByteBuffer.allocate(MeshAttachmentSender.MANIFEST_HEADER_BYTES
				+ ct.length).put(attachId).putLong(1_000L).putInt(size)
				.putShort((short) 1).put((byte) ct.length).put(ct).array();
	}

	private static byte[] chunk(byte[] attachId, byte[] data) {
		return ByteBuffer.allocate(MeshAttachmentSender.CHUNK_HEADER_BYTES
				+ data.length).put(attachId).putShort((short) 0).put(data)
				.array();
	}
}
