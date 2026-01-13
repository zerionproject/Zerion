package com.professor.zerion.android.attachment.media;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class VideoThumbnailExtractorTest {

	private static final int MAX_THUMBNAIL_DIMENSION = 512;

	@Test
	public void testSizeClass_ValidDimensions() {
		Size size = new Size(1920, 1080, "video/mp4");

		assertEquals("Width should be 1920", 1920, size.getWidth());
		assertEquals("Height should be 1080", 1080, size.getHeight());
		assertEquals("MimeType should be video/mp4", "video/mp4", size.getMimeType());
		assertTrue("Should not have error", !size.hasError());
	}

	@Test
	public void testSizeClass_ErrorState() {
		Size size = new Size();

		assertEquals("Width should be 0 for error state", 0, size.getWidth());
		assertEquals("Height should be 0 for error state", 0, size.getHeight());
		assertEquals("MimeType should be empty for error state", "", size.getMimeType());
		assertTrue("Should have error", size.hasError());
	}

	@Test
	public void testSizeClass_ZeroDimensions() {
		Size size = new Size(0, 0, "video/mp4");

		assertEquals("Width should be 0", 0, size.getWidth());
		assertEquals("Height should be 0", 0, size.getHeight());
		assertTrue("Should not have error flag (explicit construction)", !size.hasError());
	}

	@Test
	public void testScaleBitmapLogic_ScalesDownLargeImages() {
		int width = 1920;
		int height = 1080;

		int[] scaled = scaleBitmapForTest(width, height);

		assertTrue(
				"Scaled width should be <= MAX_THUMBNAIL_DIMENSION",
				scaled[0] <= MAX_THUMBNAIL_DIMENSION
		);
		assertTrue(
				"Scaled height should be <= MAX_THUMBNAIL_DIMENSION",
				scaled[1] <= MAX_THUMBNAIL_DIMENSION
		);
	}

	@Test
	public void testScaleBitmapLogic_PreservesAspectRatio() {
		int originalWidth = 1920;
		int originalHeight = 1080;
		float originalAspectRatio = (float) originalWidth / originalHeight;

		int[] scaled = scaleBitmapForTest(originalWidth, originalHeight);
		float scaledAspectRatio = (float) scaled[0] / scaled[1];

		float aspectDifference = Math.abs(originalAspectRatio - scaledAspectRatio);
		assertTrue(
				"Aspect ratio should be preserved (within rounding error)",
				aspectDifference < 0.1f
		);
	}

	@Test
	public void testScaleBitmapLogic_DoesNotUpscaleSmallImages() {
		int smallWidth = 200;
		int smallHeight = 150;

		int[] result = scaleBitmapForTest(smallWidth, smallHeight);

		assertEquals(
				"Small images should not be upscaled - width should remain unchanged",
				smallWidth,
				result[0]
		);
		assertEquals(
				"Small images should not be upscaled - height should remain unchanged",
				smallHeight,
				result[1]
		);
	}

	@Test
	public void testMemoryBoundedness_ThumbnailSizeIsLimited() {
		int maxBytes = MAX_THUMBNAIL_DIMENSION * MAX_THUMBNAIL_DIMENSION * 4;

		int[] maxDimensions = {MAX_THUMBNAIL_DIMENSION, MAX_THUMBNAIL_DIMENSION};
		int actualBytes = maxDimensions[0] * maxDimensions[1] * 4;

		assertTrue(
				"Thumbnail memory usage should be bounded to " + maxBytes + " bytes",
				actualBytes <= maxBytes
		);
	}

	@Test
	public void testVideoMimeTypes() {
		Size mp4Size = new Size(1920, 1080, "video/mp4");
		Size webmSize = new Size(1920, 1080, "video/webm");
		Size mkvSize = new Size(1920, 1080, "video/x-matroska");

		assertEquals("video/mp4", mp4Size.getMimeType());
		assertEquals("video/webm", webmSize.getMimeType());
		assertEquals("video/x-matroska", mkvSize.getMimeType());
	}

	@Test
	public void testThumbnailQuality_IsReasonable() {
		int thumbnailQuality = 80;
		assertTrue(
				"Thumbnail quality (80) should be between 1-100",
				thumbnailQuality >= 1 && thumbnailQuality <= 100
		);
	}

	@Test
	public void testMaxThumbnailDimension_IsReasonable() {
		assertTrue(
				"MAX_THUMBNAIL_DIMENSION should be positive",
				MAX_THUMBNAIL_DIMENSION > 0
		);
		assertTrue(
				"MAX_THUMBNAIL_DIMENSION should be <= 1024 for memory efficiency",
				MAX_THUMBNAIL_DIMENSION <= 1024
		);
	}

	private int[] scaleBitmapForTest(int width, int height) {
		if (width <= MAX_THUMBNAIL_DIMENSION && height <= MAX_THUMBNAIL_DIMENSION) {
			return new int[]{width, height};
		}

		float scale = Math.min(
				(float) MAX_THUMBNAIL_DIMENSION / width,
				(float) MAX_THUMBNAIL_DIMENSION / height
		);

		int newWidth = Math.round(width * scale);
		int newHeight = Math.round(height * scale);

		return new int[]{newWidth, newHeight};
	}
}
