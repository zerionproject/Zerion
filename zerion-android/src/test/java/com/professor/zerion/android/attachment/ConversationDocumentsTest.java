package com.professor.zerion.android.attachment;

import android.content.Context;
import android.net.Uri;

import com.professor.zerion.android.attachment.media.ImageCompressor;
import com.professor.zerion.android.attachment.media.ImageHelper;
import com.professor.zerion.android.attachment.media.ImageSizeCalculator;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.utils.MetadataStripper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.Attachment;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.attachment.AttachmentReader;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessageFormat;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.Executor;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ConversationDocumentsTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String AUTHORITY =
			"com.professor.zerion.test.audio";
	private static final byte[] PDF = ("%PDF-1.4\n1 0 obj\n<<>>\nendobj\n"
			+ "trailer\n<<>>\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);

	private Context ctx;
	private MessagingManager messaging;
	private AttachmentCreator creator;
	private byte[] sent;
	private String sentType;

	@Before
	public void setUp() throws Exception {
		ctx = ApplicationProvider.getApplicationContext();
		Robolectric.buildContentProvider(
				AttachmentCreationTaskAudioTest.ServedFiles.class)
				.create(AUTHORITY);
		AttachmentCreationTaskAudioTest.ServedFiles.SERVED.clear();
		sent = null;
		sentType = null;
		messaging = mock(MessagingManager.class);
		creator = mock(AttachmentCreator.class);
		when(messaging.addLocalAttachmentStreaming(any(), anyLong(),
				anyString(), any(), anyLong(), any())).thenAnswer(inv -> {
					sentType = inv.getArgument(2);
					sent = readAll(inv.getArgument(3));
					return new AttachmentHeader(inv.getArgument(0),
							new MessageId(new byte[32]), sentType);
				});
	}

	@After
	public void tearDown() {
		AttachmentCreationTaskAudioTest.ServedFiles.SERVED.clear();
	}

	private static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
		return out.toByteArray();
	}

	private Uri attach(String name, String type, byte[] content,
			PrivateMessageFormat format) {
		AttachmentCreationTaskAudioTest.ServedFiles.SERVED.put(name,
				new Object[] {type, content});
		Uri uri = Uri.parse("content://" + AUTHORITY + "/" + name);
		new AttachmentCreationTask(messaging, ctx.getContentResolver(),
				creator, mock(ImageCompressor.class),
				new MetadataStripper(ctx), new GroupId(new byte[32]),
				Collections.singletonList(uri), false, format)
				.storeAttachments();
		return uri;
	}

	@Test
	public void aPdfIsSentAsADocument() throws Exception {
		attach("report.pdf", "application/pdf", PDF,
				PrivateMessageFormat.TEXT_IMAGES_CHUNKED);
		assertEquals("application/pdf", sentType);
		assertArrayEquals(PDF, sent);
	}

	@Test
	public void aMarkdownNoteFromTheVaultIsSentAsText() throws Exception {
		byte[] md = "# Notes\nsome *text*\n".getBytes(StandardCharsets.UTF_8);
		attach("notes.md", "text/markdown", md,
				PrivateMessageFormat.TEXT_IMAGES_CHUNKED);
		assertEquals("text/plain", sentType);
		assertArrayEquals(md, sent);
	}

	@Test
	public void aFileThatOnlyClaimsToBeAPdfIsRefused() throws Exception {
		Uri uri = attach("page.pdf", "application/pdf",
				"<html><img src=http://x/t></html>"
						.getBytes(StandardCharsets.US_ASCII),
				PrivateMessageFormat.TEXT_IMAGES_CHUNKED);
		assertNull(sent);
		verify(creator).onAttachmentError(eq(uri), any());
	}

	@Test
	public void aContactThatCannotReceiveDocumentsGetsAnExplicitRefusal()
			throws Exception {
		Uri uri = attach("report.pdf", "application/pdf", PDF,
				PrivateMessageFormat.TEXT_IMAGES);
		assertNull(sent);
		verify(messaging, never()).addLocalAttachment(any(), anyLong(),
				anyString(), any());
		verify(creator).onAttachmentError(eq(uri),
				isA(ChunkedAttachmentsNotSupportedException.class));
	}

	@Test
	public void aReceivedDocumentIsShownAsADocument() throws Exception {
		AttachmentReader reader = mock(AttachmentReader.class);
		ImageSizeCalculator sizes = mock(ImageSizeCalculator.class);
		Executor now = Runnable::run;
		AttachmentRetrieverImpl retriever = new AttachmentRetrieverImpl(now,
				reader, new AttachmentDimensions(100, 50, 200, 75, 300),
				mock(ImageHelper.class), sizes);
		for (String type : new String[] {"application/pdf", "text/plain"}) {
			AttachmentHeader h = new AttachmentHeader(
					new GroupId(new byte[32]), new MessageId(new byte[32]),
					type);
			Attachment a = new Attachment(h, new ByteArrayInputStream(PDF));
			AttachmentItem item = retriever.createAttachmentItem(a, true);
			assertEquals(type, AttachmentItem.State.AVAILABLE,
					item.getState());
			assertTrue(item.getThumbnailWidth() > 0);
			verify(sizes, never()).getSize(any(), anyString());
		}
	}
}
