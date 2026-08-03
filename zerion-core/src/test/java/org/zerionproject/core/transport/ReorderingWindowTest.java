package org.zerionproject.core.transport;

import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestUtils;
import org.zerionproject.core.transport.ReorderingWindow.Change;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.zerionproject.core.api.transport.TransportConstants.REORDERING_WINDOW_SIZE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ReorderingWindowTest extends BrambleTestCase {

	private static final int BITMAP_BYTES = REORDERING_WINDOW_SIZE / 8;

	@Test
	public void testBitmapConversion() {
		for (int i = 0; i < 1000; i++) {
			byte[] bitmap = TestUtils.getRandomBytes(BITMAP_BYTES);
			ReorderingWindow window = new ReorderingWindow(0L, bitmap);
			assertArrayEquals(bitmap, window.getBitmap());
		}
	}

	@Test
	public void testWindowSlidesWhenFirstElementIsSeen() {
		byte[] bitmap = new byte[BITMAP_BYTES];
		ReorderingWindow window = new ReorderingWindow(0L, bitmap);

		Change change = window.setSeen(0L);

		assertEquals(1L, window.getBase());
		assertEquals(Collections.singletonList((long) REORDERING_WINDOW_SIZE),
				change.getAdded());
		assertEquals(Collections.singletonList(0L), change.getRemoved());

		assertArrayEquals(bitmap, window.getBitmap());
	}

	@Test
	public void testWindowDoesNotSlideWhenElementBelowMidpointIsSeen() {
		byte[] bitmap = new byte[BITMAP_BYTES];
		ReorderingWindow window = new ReorderingWindow(0L, bitmap);

		Change change = window.setSeen(1L);

		assertEquals(0L, window.getBase());
		assertEquals(Collections.emptyList(), change.getAdded());
		assertEquals(Collections.singletonList(1L), change.getRemoved());

		bitmap[0] = 0x40;
		assertArrayEquals(bitmap, window.getBitmap());
	}

	@Test
	public void testWindowSlidesWhenElementAboveMidpointIsSeen() {
		byte[] bitmap = new byte[BITMAP_BYTES];
		ReorderingWindow window = new ReorderingWindow(0, bitmap);
		long aboveMidpoint = REORDERING_WINDOW_SIZE / 2;

		Change change = window.setSeen(aboveMidpoint);

		assertEquals(1L, window.getBase());
		assertEquals(Collections.singletonList((long) REORDERING_WINDOW_SIZE),
				change.getAdded());
		assertEquals(Arrays.asList(0L, aboveMidpoint), change.getRemoved());

		bitmap[bitmap.length / 2 - 1] = (byte) 0x01;
		assertArrayEquals(bitmap, window.getBitmap());
	}

	@Test
	public void testWindowSlidesUntilLowestElementIsUnseenWhenFirstElementIsSeen() {
		byte[] bitmap = new byte[BITMAP_BYTES];
		ReorderingWindow window = new ReorderingWindow(0L, bitmap);
		window.setSeen(1L);

		Change change = window.setSeen(0L);

		assertEquals(2L, window.getBase());
		assertEquals(Arrays.asList((long) REORDERING_WINDOW_SIZE,
				(long) (REORDERING_WINDOW_SIZE + 1)), change.getAdded());
		assertEquals(Collections.singletonList(0L), change.getRemoved());

		assertArrayEquals(bitmap, window.getBitmap());
	}

	@Test
	public void testWindowSlidesUntilLowestElementIsUnseenWhenElementAboveMidpointIsSeen() {
		byte[] bitmap = new byte[BITMAP_BYTES];
		ReorderingWindow window = new ReorderingWindow(0L, bitmap);
		window.setSeen(1L);
		long aboveMidpoint = REORDERING_WINDOW_SIZE / 2;

		Change change = window.setSeen(aboveMidpoint);

		assertEquals(2L, window.getBase());
		assertEquals(Arrays.asList((long) REORDERING_WINDOW_SIZE,
				(long) (REORDERING_WINDOW_SIZE + 1)), change.getAdded());
		assertEquals(Arrays.asList(0L, aboveMidpoint), change.getRemoved());

		bitmap[bitmap.length / 2 - 1] = (byte) 0x02;
		assertArrayEquals(bitmap, window.getBitmap());
	}
}
