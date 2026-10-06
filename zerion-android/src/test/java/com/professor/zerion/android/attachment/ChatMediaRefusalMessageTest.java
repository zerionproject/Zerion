package com.professor.zerion.android.attachment;

import android.app.Application;
import android.net.Uri;
import android.os.Looper;

import com.professor.zerion.android.attachment.media.ImageCompressor;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.utils.MetadataStripper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessageFormat;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.attachment.OggTestFiles.FIRST;
import static com.professor.zerion.android.attachment.OggTestFiles.ascii;
import static com.professor.zerion.android.attachment.OggTestFiles.audioPackets;
import static com.professor.zerion.android.attachment.OggTestFiles.concat;
import static com.professor.zerion.android.attachment.OggTestFiles.granules;
import static com.professor.zerion.android.attachment.OggTestFiles.list;
import static com.professor.zerion.android.attachment.OggTestFiles.pages;
import static com.professor.zerion.android.attachment.OggTestFiles.zeros;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChatMediaRefusalMessageTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String AUTHORITY = "com.professor.zerion.test.refusal";

	private Application app;
	private MessagingManager messaging;
	private AttachmentRetriever retriever;

	@Before
	public void setUp() throws Exception {
		app = ApplicationProvider.getApplicationContext();
		Robolectric.buildContentProvider(
				AttachmentCreationTaskAudioTest.ServedFiles.class)
				.create(AUTHORITY);
		AttachmentCreationTaskAudioTest.ServedFiles.SERVED.clear();
		messaging = mock(MessagingManager.class);
		when(messaging.addLocalAttachmentStreaming(any(), anyLong(),
				anyString(), any(), anyLong(), any())).thenAnswer(inv ->
				new AttachmentHeader(inv.getArgument(0),
						new MessageId(new byte[32]), inv.getArgument(2)));
		retriever = mock(AttachmentRetriever.class);
		AttachmentItem item = mock(AttachmentItem.class);
		when(item.getState()).thenReturn(AttachmentItem.State.AVAILABLE);
		when(retriever.createAttachmentItem(any(), anyBoolean()))
				.thenReturn(item);
	}

	@After
	public void tearDown() {
		AttachmentCreationTaskAudioTest.ServedFiles.SERVED.clear();
	}

	private String errorShownFor(String name, String type, byte[] content) {
		AttachmentCreationTaskAudioTest.ServedFiles.SERVED.put(name,
				new Object[] {type, content});
		Uri uri = Uri.parse("content://" + AUTHORITY + "/" + name);
		try (MockedConstruction<MetadataStripper> ignored =
				mockConstruction(MetadataStripper.class, withSettings()
						.defaultAnswer(inv -> {
							throw new IOException(
									"Failed to strip video metadata");
						}))) {
			AttachmentCreatorImpl creator = new AttachmentCreatorImpl(app,
					Runnable::run, messaging, retriever,
					mock(ImageCompressor.class));
			MutableLiveData<GroupId> group =
					new MutableLiveData<>(new GroupId(new byte[32]));
			LiveData<AttachmentResult> result = creator.storeAttachments(
					group, Collections.singletonList(uri),
					PrivateMessageFormat.TEXT_IMAGES_CHUNKED);
			shadowOf(Looper.getMainLooper()).idle();
			AttachmentResult r = result.getValue();
			assertNotNull("no result for " + name, r);
			for (AttachmentItemResult each : r.getItemResults()) {
				if (each.hasError()) return each.getErrorMsg();
			}
			return null;
		}
	}

	private static void assertTellsWhy(String what, String shown) {
		assertNotNull(what + " was attached", shown);
		assertTrue(what + " showed: " + shown,
				shown.contains("hidden information"));
	}

	@Test
	public void aVideoThatCannotBeCleanedSaysWhyItWasNotSent() {
		byte[] avi = concat(ascii("RIFF"), new byte[] {(byte) 0xF8, 0x0F, 0,
				0}, ascii("AVI LIST"), new byte[4000]);
		assertTellsWhy("an AVI video",
				errorShownFor("clip.avi", "video/avi", avi));
	}

	@Test
	public void oggSoundThatCannotBeCleanedSaysWhyItWasNotSent() {
		List<byte[]> data = audioPackets(8, 2);
		java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
		long seq = pages(b, list(concat(ascii("CMML\0\0\0\0"), new byte[20])),
				4, 0, FIRST, zeros(1), false, 255);
		pages(b, data, 4, seq, 0, granules(data.size(), 1), true, 10);
		assertTellsWhy("Ogg in a codec whose metadata cannot be removed",
				errorShownFor("note.ogg", "audio/ogg", b.toByteArray()));
	}
}
