package com.professor.zerion.android.conversation;

import org.junit.Test;
import org.zerionproject.app.api.autodelete.AutoDeleteManager;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessage;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
import org.zerionproject.app.api.messaging.PrivateMessageFormat;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.GroupId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OutgoingTextMessagesTest {

	private static final long HOUR = 60L * 60L * 1000L;

	private final Transaction txn = mock(Transaction.class);
	private final MessagingManager messaging = mock(MessagingManager.class);
	private final AutoDeleteManager autoDelete = mock(AutoDeleteManager.class);
	private final PrivateMessageFactory factory =
			mock(PrivateMessageFactory.class);
	private final ContactId contact = new ContactId(3);
	private final GroupId group = new GroupId(new byte[32]);
	private final PrivateMessage built = mock(PrivateMessage.class);

	private PrivateMessage send(PrivateMessageFormat format) throws Exception {
		when(messaging.getContactMessageFormat(txn, contact)).thenReturn(format);
		when(autoDelete.getAutoDeleteTimer(eq(txn), eq(contact), anyLong()))
				.thenReturn(HOUR);
		when(factory.createPrivateMessage(any(), anyLong(), anyString(),
				any(), anyLong(), any())).thenReturn(built);
		when(factory.createPrivateMessage(any(), anyLong(), anyString(),
				any())).thenReturn(built);
		when(factory.createLegacyPrivateMessage(any(), anyLong(),
				anyString())).thenReturn(built);
		return OutgoingTextMessages.create(txn, messaging, autoDelete, factory,
				contact, group, 1000L, "hi");
	}

	@Test
	public void timerFormatsCarryTheConversationTimer() throws Exception {
		for (PrivateMessageFormat f : new PrivateMessageFormat[] {
				PrivateMessageFormat.TEXT_IMAGES_AUTO_DELETE,
				PrivateMessageFormat.TEXT_IMAGES_CHUNKED}) {
			assertSame(built, send(f));
		}
		verify(factory, org.mockito.Mockito.times(2)).createPrivateMessage(
				eq(group), eq(1000L), eq("hi"),
				eq(Collections.emptyList()), eq(HOUR), eq(null));
		verify(factory, never()).createLegacyPrivateMessage(any(), anyLong(),
				anyString());
	}

	@Test
	public void olderFormatsKeepTheirShape() throws Exception {
		send(PrivateMessageFormat.TEXT_ONLY);
		verify(factory).createLegacyPrivateMessage(group, 1000L, "hi");
		send(PrivateMessageFormat.TEXT_IMAGES);
		verify(factory).createPrivateMessage(group, 1000L, "hi",
				Collections.emptyList());
		verify(autoDelete, never()).getAutoDeleteTimer(any(), any(),
				anyLong());
	}

	@Test
	public void repliesForwardsAndSecretNotesUseTheTimer() throws Exception {
		String reply = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/"
						+ "NotificationQuickReplyReceiver.java")),
				StandardCharsets.UTF_8);
		String vm = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/conversation/"
						+ "ConversationViewModel.java")),
				StandardCharsets.UTF_8);
		assertTrue(reply.contains("OutgoingTextMessages.create("));
		assertFalse(reply.contains("createLegacyPrivateMessage"));
		int fwd = vm.indexOf("void forwardMessage(");
		String forward = vm.substring(fwd, vm.indexOf("void loadLinkPreviews(",
				fwd));
		assertTrue(forward.contains("OutgoingTextMessages.create("));
		assertFalse(forward.contains("createLegacyPrivateMessage"));
		int note = vm.indexOf("void sendSecretNote(");
		String secret = vm.substring(note, vm.indexOf(
				"private void storeReplyContext(", note));
		assertTrue(secret.contains("noteTimer = autoDeleteManager"
				+ ".getAutoDeleteTimer("));
		assertFalse(secret.contains("NO_AUTO_DELETE_TIMER, null);"));
	}
}
