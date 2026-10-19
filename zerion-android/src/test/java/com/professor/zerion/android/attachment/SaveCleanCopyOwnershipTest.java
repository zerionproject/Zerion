package com.professor.zerion.android.attachment;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SaveCleanCopyOwnershipTest {

	@After
	public void tearDown() {
		SaveCleanCopy.clearAll();
	}

	private static byte[] bytes(int seed) {
		byte[] d = new byte[32];
		for (int i = 0; i < d.length; i++) d[i] = (byte) (i + seed);
		return d;
	}

	@Test
	public void aCopyHandedOverIsWipedOnceCleanAndTheCleanOneOnceSaved() {
		byte[] copy = bytes(1);
		String token = SaveCleanCopy.offer(copy, "image/png", true);
		byte[] clean = bytes(2);
		assertTrue(SaveCleanCopy.cleaned(token,
				new SharedMediaSanitizer.Cleaned("image/png", clean)));
		assertArrayEquals("the raw copy outlived its cleaning",
				new byte[copy.length], copy);
		SaveCleanCopy.Pending pending = SaveCleanCopy.take(token);
		assertNotNull(pending);
		assertArrayEquals(bytes(2), pending.data);
		SaveCleanCopy.done(pending);
		assertArrayEquals("the clean copy outlived the save",
				new byte[clean.length], clean);
	}

	@Test
	public void aCopyHandedOverIsWipedWhenTheSaveIsDroppedOrTheAppLocks() {
		byte[] dropped = bytes(3);
		SaveCleanCopy.drop(SaveCleanCopy.offer(dropped, "image/png", true));
		assertArrayEquals(new byte[dropped.length], dropped);
		byte[] locked = bytes(4);
		SaveCleanCopy.offer(locked, "image/png", true);
		SaveCleanCopy.clearAll();
		assertArrayEquals(new byte[locked.length], locked);
	}

	@Test
	public void bytesThatStayTheCallersAreLeftAloneButTheCleanCopyGoes() {
		byte[] own = bytes(5);
		String token = SaveCleanCopy.offer(own, "image/png");
		byte[] clean = bytes(6);
		assertTrue(SaveCleanCopy.cleaned(token,
				new SharedMediaSanitizer.Cleaned("image/png", clean)));
		SaveCleanCopy.done(SaveCleanCopy.take(token));
		assertArrayEquals(bytes(5), own);
		assertArrayEquals(new byte[clean.length], clean);
	}

	@Test
	public void aCleanCopyForASaveThatIsGoneIsWiped() {
		String token = SaveCleanCopy.offer(bytes(7), "image/png", true);
		SaveCleanCopy.drop(token);
		byte[] late = bytes(8);
		assertFalse(SaveCleanCopy.cleaned(token,
				new SharedMediaSanitizer.Cleaned("image/png", late)));
		assertArrayEquals(new byte[late.length], late);
	}
}
