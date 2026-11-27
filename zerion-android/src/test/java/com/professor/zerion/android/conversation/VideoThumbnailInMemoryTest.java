package com.professor.zerion.android.conversation;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VideoThumbnailInMemoryTest {

	@Test
	public void aReceivedVideoNeverLandsInTheCacheForItsThumbnail()
			throws Exception {
		String src = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/conversation/"
						+ "ImageViewHolder.java")), StandardCharsets.UTF_8);
		assertFalse("the thumbnail copies the video to a file",
				src.contains("createTempFile"));
		assertFalse(src.contains("FileOutputStream"));
		assertTrue("the thumbnail is not read from memory",
				src.contains("ByteArrayMediaDataSource"));
	}

	@Test
	public void theThumbnailReadStopsAtTheAttachmentLimit() throws Exception {
		byte[] small = {1, 2, 3};
		assertArrayEquals(small, ImageViewHolder.readBounded(
				new ByteArrayInputStream(small), 3));
		assertNull(ImageViewHolder.readBounded(
				new ByteArrayInputStream(new byte[4]), 3));
	}

	@Test
	public void aGrowingReadLeavesNoCopyOfTheVideoBehind() {
		ImageViewHolder.WipingBuffer buffer = new ImageViewHolder.WipingBuffer(4);
		buffer.write(new byte[] {1, 2, 3, 4}, 0, 4);
		byte[] first = buffer.current();
		buffer.write(new byte[] {5, 6}, 0, 2);
		assertArrayEquals(new byte[4], first);
		byte[] grown = buffer.current();
		assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6},
				buffer.toExactArray());
		for (byte b : grown) assertTrue(b == 0);
	}

	@Test
	public void theThumbnailIsReadAndDecodedInOneBoundedTask()
			throws Exception {
		String src = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/conversation/"
						+ "ImageViewHolder.java")), StandardCharsets.UTF_8);
		int load = src.indexOf("private void loadVideoThumbnailWithRetry(");
		String loader = src.substring(load, src.indexOf("static byte[] readBounded(", load));
		assertFalse("the video is read on another executor",
				loader.contains("dbExecutor.execute("));
		assertTrue(loader.contains("previewExecutor.execute("));
		assertTrue("the preview does not default to the single preview thread",
				src.replaceAll("\\s+", "")
						.contains("dbExecutor,THUMBNAIL_DECODE_EXECUTOR);"));
		int decode = src.indexOf("private void decodeThumbnail(");
		String decoder = src.substring(decode, src.indexOf("setDataSource(", decode));
		assertTrue("the frame is taken for a row that moved on",
				decoder.contains("token != bound"));
		assertTrue(decoder.contains("VIDEO_THUMB_CACHE.get(thumbKey)"));
	}
}
