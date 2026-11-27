package com.professor.zerion.android.sticker;

import android.os.Looper;

import com.professor.zerion.android.conversation.TestPictures;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static androidx.lifecycle.Transformations.map;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class StickerSendTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private final GroupId groupId = new GroupId(new byte[GroupId.LENGTH]);
	private final List<byte[]> stored = new ArrayList<>();
	private final List<AttachmentHeader> sent = new ArrayList<>();
	private final List<Exception> failed = new ArrayList<>();
	private final Owner owner = new Owner();
	private MessagingManager messaging;

	private static final class Owner implements LifecycleOwner {

		private final LifecycleRegistry registry = new LifecycleRegistry(this);

		Owner() {
			registry.setCurrentState(Lifecycle.State.RESUMED);
		}

		@NonNull
		@Override
		public Lifecycle getLifecycle() {
			return registry;
		}
	}

	@Before
	public void setUp() throws Exception {
		messaging = mock(MessagingManager.class);
		when(messaging.addLocalAttachment(any(GroupId.class), anyLong(),
				anyString(), any(InputStream.class))).thenAnswer(inv -> {
			stored.add(readAll(inv.getArgument(3)));
			return new AttachmentHeader(inv.getArgument(0),
					new MessageId(new byte[MessageId.LENGTH]),
					inv.getArgument(2));
		});
	}

	private void send(LiveData<GroupId> conversation, byte[] png) {
		new StickerSendTask(Runnable::run, messaging).send(owner,
				conversation, png, new StickerSendTask.Callback() {
					@Override
					public void onStickerHeaderReady(AttachmentHeader h) {
						sent.add(h);
					}

					@Override
					public void onStickerSendFailed(Exception e) {
						failed.add(e);
					}
				});
		idle();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static byte[] picture() {
		return TestPictures.png(
				"hello".getBytes(StandardCharsets.US_ASCII), 40, 30);
	}

	@Test
	public void importedStickerIsSentToTheConversation() throws Exception {
		StickerStorage storage = new StickerStorage(folder.newFolder(),
				new com.professor.zerion.android.security
						.MemorySharedPreferences());
		byte[] picture = picture();
		String id = storage.save(picture);
		byte[] picked = storage.load(id);

		MutableLiveData<String> contact = new MutableLiveData<>("contact");
		LiveData<GroupId> conversation = map(contact, c -> groupId);
		send(conversation, picked);

		assertTrue(failed.isEmpty());
		assertEquals(1, sent.size());
		assertEquals(groupId, sent.get(0).getGroupId());
		assertEquals(StickerUtils.STICKER_PNG_MIME,
				sent.get(0).getContentType());
		assertEquals(1, stored.size());
		assertArrayEquals(Arrays.copyOf(picture, 16),
				Arrays.copyOf(stored.get(0), 16));
		assertFalse(conversation.hasObservers());
	}

	@Test
	public void aStickerWaitsUntilTheConversationIsKnown() {
		MutableLiveData<GroupId> conversation = new MutableLiveData<>();
		send(conversation, picture());

		assertTrue(stored.isEmpty());
		assertTrue(sent.isEmpty());
		assertTrue(failed.isEmpty());
		assertTrue(conversation.hasObservers());

		conversation.setValue(groupId);
		idle();
		assertEquals(1, stored.size());
		assertEquals(1, sent.size());
		assertTrue(failed.isEmpty());
		assertFalse(conversation.hasObservers());

		conversation.setValue(groupId);
		idle();
		assertEquals("sent exactly once", 1, stored.size());
		assertEquals(1, sent.size());
	}

	@Test
	public void noConversationReportsAFailedSend() {
		MutableLiveData<GroupId> conversation = new MutableLiveData<>(null);
		send(conversation, picture());

		assertEquals(1, failed.size());
		assertTrue(sent.isEmpty());
		assertTrue(stored.isEmpty());
		assertFalse(conversation.hasObservers());
	}

	@Test
	public void aClosedChatDropsThePendingSticker() {
		MutableLiveData<GroupId> conversation = new MutableLiveData<>();
		send(conversation, picture());
		owner.registry.setCurrentState(Lifecycle.State.DESTROYED);

		assertFalse(conversation.hasObservers());
		conversation.setValue(groupId);
		idle();
		assertTrue(stored.isEmpty());
		assertTrue(sent.isEmpty());
		assertTrue(failed.isEmpty());
	}

	private static byte[] readAll(InputStream in) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[4096];
		int n;
		while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
		return out.toByteArray();
	}
}
