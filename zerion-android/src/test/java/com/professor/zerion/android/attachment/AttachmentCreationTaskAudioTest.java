package com.professor.zerion.android.attachment;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import com.professor.zerion.android.attachment.media.ImageCompressor;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.utils.MetadataStripper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessageFormat;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.id3v1;
import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.id3v23;
import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.mpegFrames;
import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.samples;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class AttachmentCreationTaskAudioTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String AUTHORITY = "com.professor.zerion.test.audio";
	private static final byte[] MARKER =
			"ZtPlantedArtistName".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] SECOND_MARKER =
			"ZtPlantedAlbum".getBytes(StandardCharsets.US_ASCII);

	public static class ServedFiles extends ContentProvider {

		static final Map<String, Object[]> SERVED = new HashMap<>();

		@Override
		public boolean onCreate() {
			return true;
		}

		@Nullable
		@Override
		public String getType(Uri uri) {
			Object[] f = SERVED.get(uri.getLastPathSegment());
			return f == null ? null : (String) f[0];
		}

		@Nullable
		@Override
		public Cursor query(Uri uri, @Nullable String[] projection,
				@Nullable String selection, @Nullable String[] args,
				@Nullable String order) {
			Object[] f = SERVED.get(uri.getLastPathSegment());
			MatrixCursor c = new MatrixCursor(new String[] {
					OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
			if (f != null) {
				c.addRow(new Object[] {uri.getLastPathSegment(),
						((byte[]) f[1]).length});
			}
			return c;
		}

		@Nullable
		@Override
		public ParcelFileDescriptor openFile(Uri uri, String mode)
				throws FileNotFoundException {
			Object[] f = SERVED.get(uri.getLastPathSegment());
			if (f == null) throw new FileNotFoundException();
			try {
				File tmp = File.createTempFile("zt_served_", ".bin",
						getContext().getCacheDir());
				tmp.deleteOnExit();
				java.nio.file.Files.write(tmp.toPath(), (byte[]) f[1]);
				return ParcelFileDescriptor.open(tmp,
						ParcelFileDescriptor.MODE_READ_ONLY);
			} catch (IOException e) {
				throw new FileNotFoundException(e.toString());
			}
		}

		@Nullable
		@Override
		public Uri insert(Uri uri, @Nullable ContentValues values) {
			return null;
		}

		@Override
		public int delete(Uri uri, @Nullable String selection,
				@Nullable String[] args) {
			return 0;
		}

		@Override
		public int update(Uri uri, @Nullable ContentValues values,
				@Nullable String selection, @Nullable String[] args) {
			return 0;
		}
	}

	private Context ctx;
	private MessagingManager messaging;
	private AttachmentCreator creator;
	private byte[] sent;
	private String sentType;
	private long sentSize;

	@Before
	public void setUp() throws Exception {
		ctx = ApplicationProvider.getApplicationContext();
		Robolectric.buildContentProvider(ServedFiles.class).create(AUTHORITY);
		ServedFiles.SERVED.clear();
		sent = null;
		sentType = null;
		sentSize = -1;
		messaging = mock(MessagingManager.class);
		creator = mock(AttachmentCreator.class);
		when(messaging.addLocalAttachmentStreaming(any(), anyLong(),
				anyString(), any(), anyLong(), any())).thenAnswer(inv -> {
					sentType = inv.getArgument(2);
					sent = readAll(inv.getArgument(3));
					sentSize = inv.getArgument(4);
					return new AttachmentHeader(inv.getArgument(0),
							new MessageId(new byte[32]), sentType);
				});
	}

	@After
	public void tearDown() {
		ServedFiles.SERVED.clear();
	}

	private static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
		return out.toByteArray();
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
	}

	private static boolean contains(byte[] hay, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= hay.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (hay[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	private Uri attach(String name, String type, byte[] content) {
		ServedFiles.SERVED.put(name, new Object[] {type, content});
		Uri uri = Uri.parse("content://" + AUTHORITY + "/" + name);
		new AttachmentCreationTask(messaging, ctx.getContentResolver(),
				creator, mock(ImageCompressor.class),
				new MetadataStripper(ctx), new GroupId(new byte[32]),
				Collections.singletonList(uri), false,
				PrivateMessageFormat.TEXT_IMAGES_CHUNKED).storeAttachments();
		return uri;
	}

	@Test
	public void anMp3IsSentWithoutItsId3Tags() throws Exception {
		byte[] frames = mpegFrames(4);
		byte[] mp3 = concat(id3v23(MARKER), frames, id3v1(SECOND_MARKER));
		attach("song.mp3", "audio/mpeg", mp3);
		assertFalse("the ID3v2 tag was sent", contains(sent, MARKER));
		assertFalse("the ID3v1 tag was sent", contains(sent, SECOND_MARKER));
		assertArrayEquals(frames, sent);
		assertEquals(frames.length, sentSize);
		assertEquals("audio/mpeg", sentType);
	}

	@Test
	public void anAacStreamIsSentWithoutItsId3Tag() throws Exception {
		byte[] adts = concat(new byte[] {(byte) 0xFF, (byte) 0xF1, 0x50,
				(byte) 0x80, 0x02, 0x1F, (byte) 0xFC}, samples(300));
		attach("voice.aac", "audio/aac", concat(id3v23(MARKER), adts));
		assertFalse("the ID3 tag was sent", contains(sent, MARKER));
		assertArrayEquals(adts, sent);
		assertEquals(adts.length, sentSize);
	}

	@Test
	public void isoMediaReportedAsAnotherAudioTypeIsRemuxed()
			throws Exception {
		byte[] m4a = new byte[4096];
		byte[] head = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '};
		System.arraycopy(head, 0, m4a, 0, head.length);
		System.arraycopy(MARKER, 0, m4a, 200, MARKER.length);
		Uri uri = attach("memo.mp3", "audio/mpeg", m4a);
		if (sent == null) {
			verify(creator).onAttachmentError(eq(uri), any());
			return;
		}
		assertFalse("ISO audio was sent with its metadata",
				contains(sent, MARKER));
		assertEquals("audio/mp4", sentType);
	}

	@Test
	public void contentThatIsNotAudioIsRefused() throws Exception {
		byte[] jpeg = new byte[2048];
		jpeg[0] = (byte) 0xFF;
		jpeg[1] = (byte) 0xD8;
		jpeg[2] = (byte) 0xFF;
		jpeg[3] = (byte) 0xE1;
		System.arraycopy(MARKER, 0, jpeg, 40, MARKER.length);
		byte[] tiff = concat(new byte[] {'I', 'I', 42, 0}, MARKER,
				samples(500));
		Object[][] cases = {{"photo.ogg", "audio/ogg", jpeg},
				{"scan.opus", "audio/opus", tiff},
				{"photo.mp3", "audio/mpeg", jpeg}};
		for (Object[] c : cases) {
			sent = null;
			Uri uri = attach((String) c[0], (String) c[1], (byte[]) c[2]);
			assertNull(c[1] + " carrying another kind of file was sent",
					sent);
			verify(creator).onAttachmentError(eq(uri), any());
		}
	}

	@Test
	public void oggAudioIsSentWithoutItsComments() throws Exception {
		java.util.List<byte[]> audio = OggTestFiles.audioPackets(12, 3);
		byte[] ogg = OggTestFiles.opus(0x5EED, OggTestFiles.opusTags(
				"ARTIST=" + new String(MARKER, StandardCharsets.US_ASCII),
				"LOCATION=52.3702,4.8952"), audio, 255, 40);
		byte[] expected = OggTestFiles.opus(0x5EED,
				OggTestFiles.EMPTY_OPUS_TAGS, audio, 255, 40);
		Uri uri = attach("note.ogg", "audio/ogg", ogg);
		verify(creator, never()).onAttachmentError(eq(uri), any());
		assertFalse("the Opus comments were sent", contains(sent, MARKER));
		assertArrayEquals(expected, sent);
		assertEquals(expected.length, sentSize);
		assertEquals("audio/ogg", sentType);
	}

	@Test
	public void oggThatCannotBeReadPageByPageIsRefused() throws Exception {
		byte[] ogg = concat(new byte[] {'O', 'g', 'g', 'S', 0, 2},
				samples(700));
		Uri uri = attach("note.ogg", "audio/ogg", ogg);
		assertNull("an Ogg file that is not made of pages was sent", sent);
		verify(creator).onAttachmentError(eq(uri), any());
	}
}
