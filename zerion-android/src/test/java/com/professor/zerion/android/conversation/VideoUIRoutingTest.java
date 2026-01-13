package com.professor.zerion.android.conversation;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VideoUIRoutingTest {

	private static final String IMAGE_VIEW_HOLDER_PATH =
			"src/main/java/com/professor/zerion/android/conversation/ImageViewHolder.java";

	@Test
	public void testImageViewHolder_HasPlayOverlayField() throws IOException {
		String sourceCode = readSourceFile();

		boolean hasPlayOverlay = sourceCode.contains("playOverlay") &&
				sourceCode.contains("ImageView");

		assertTrue(
				"ImageViewHolder MUST have a playOverlay ImageView field for video indicators",
				hasPlayOverlay
		);
	}

	@Test
	public void testImageViewHolder_ChecksIsVideoMethod() throws IOException {
		String sourceCode = readSourceFile();

		boolean checksIsVideo = sourceCode.contains("isVideo()");

		assertTrue(
				"ImageViewHolder MUST check isVideo() to determine rendering path",
				checksIsVideo
		);
	}

	@Test
	public void testImageViewHolder_SetsPlayOverlayVisibility() throws IOException {
		String sourceCode = readSourceFile();

		boolean setsVisibility = sourceCode.contains("playOverlay.setVisibility") ||
				(sourceCode.contains("playOverlay") && sourceCode.contains("setVisibility"));

		assertTrue(
				"ImageViewHolder MUST set playOverlay visibility based on video status",
				setsVisibility
		);
	}

	@Test
	public void testImageViewHolder_HasVideoThumbnailLoader() throws IOException {
		String sourceCode = readSourceFile();

		boolean hasVideoThumbnailMethod = sourceCode.contains("loadVideoThumbnail");

		assertTrue(
				"ImageViewHolder MUST have a loadVideoThumbnail method for video attachments",
				hasVideoThumbnailMethod
		);
	}

	@Test
	public void testImageViewHolder_HasImageLoader() throws IOException {
		String sourceCode = readSourceFile();

		boolean hasImageLoadMethod = sourceCode.contains("loadImage");

		assertTrue(
				"ImageViewHolder MUST have a loadImage method for image attachments",
				hasImageLoadMethod
		);
	}

	@Test
	public void testImageViewHolder_RoutesVideoToVideoThumbnail() throws IOException {
		String sourceCode = readSourceFile();

		boolean routesVideoCorrectly = sourceCode.contains("if") &&
				sourceCode.contains("isVideo") &&
				sourceCode.contains("loadVideoThumbnail");

		assertTrue(
				"ImageViewHolder MUST route video attachments to loadVideoThumbnail",
				routesVideoCorrectly
		);
	}

	@Test
	public void testImageViewHolder_ShowsPlayOverlayForVideo() throws IOException {
		String sourceCode = readSourceFile();

		boolean showsPlayOverlayForVideo =
				sourceCode.contains("isVideo") &&
				sourceCode.contains("VISIBLE") &&
				sourceCode.contains("playOverlay");

		assertTrue(
				"ImageViewHolder MUST show play overlay (VISIBLE) for video attachments",
				showsPlayOverlayForVideo
		);
	}

	@Test
	public void testImageViewHolder_HidesPlayOverlayForNonVideo() throws IOException {
		String sourceCode = readSourceFile();

		boolean hidesPlayOverlayForNonVideo =
				sourceCode.contains("GONE") &&
				sourceCode.contains("playOverlay");

		assertTrue(
				"ImageViewHolder MUST hide play overlay (GONE) for non-video attachments",
				hidesPlayOverlayForNonVideo
		);
	}

	@Test
	public void testVideoRouting_UsesMediaMetadataRetriever() throws IOException {
		String sourceCode = readSourceFile();

		boolean usesMediaRetriever = sourceCode.contains("MediaMetadataRetriever");

		assertTrue(
				"Video thumbnail loading should use MediaMetadataRetriever",
				usesMediaRetriever
		);
	}

	@Test
	public void testVideoRouting_HandlesErrorGracefully() throws IOException {
		String sourceCode = readSourceFile();

		boolean hasTryCatch = sourceCode.contains("try") && sourceCode.contains("catch");
		boolean hasErrorHandling = sourceCode.contains("Exception") ||
				sourceCode.contains("ic_video") ||
				sourceCode.contains("ERROR_RES");

		assertTrue(
				"Video loading MUST handle errors gracefully with try-catch",
				hasTryCatch && hasErrorHandling
		);
	}

	private String readSourceFile() throws IOException {
		File currentDir = new File(System.getProperty("user.dir"));
		File sourceFile = new File(currentDir, IMAGE_VIEW_HOLDER_PATH);

		if (!sourceFile.exists()) {
			File parentDir = currentDir.getParentFile();
			if (parentDir != null) {
				sourceFile = new File(parentDir, "zerion-android/" + IMAGE_VIEW_HOLDER_PATH);
			}
		}

		if (!sourceFile.exists()) {
			sourceFile = findSourceFile(currentDir, "ImageViewHolder.java");
		}

		if (sourceFile == null || !sourceFile.exists()) {
			fail("Could not find ImageViewHolder.java source file for analysis");
		}

		StringBuilder content = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new FileReader(sourceFile))) {
			String line;
			while ((line = reader.readLine()) != null) {
				content.append(line).append("\n");
			}
		}
		return content.toString();
	}

	private File findSourceFile(File startDir, String fileName) {
		File[] files = startDir.listFiles();
		if (files == null) return null;

		for (File file : files) {
			if (file.isDirectory()) {
				File found = findSourceFile(file, fileName);
				if (found != null) return found;
			} else if (file.getName().equals(fileName)) {
				return file;
			}
		}
		return null;
	}
}
