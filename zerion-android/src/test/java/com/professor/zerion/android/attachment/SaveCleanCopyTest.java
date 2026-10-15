package com.professor.zerion.android.attachment;

import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SaveCleanCopyTest {

	@After
	public void tearDown() {
		SaveCleanCopy.clearAll();
	}

	@Test
	public void aCleanCopyIsKeptWhileItsSaveIsPending() {
		String token = SaveCleanCopy.offer(new byte[] {1, 2}, "image/jpeg");
		assertTrue(SaveCleanCopy.cleaned(token, cleaned()));
		assertTrue(SaveCleanCopy.get(token).cleaned);
	}

	@Test
	public void aCleanCopyFinishedAfterALockIsNotKept() {
		String token = SaveCleanCopy.offer(new byte[] {1, 2}, "image/jpeg");
		SaveCleanCopy.clearAll();
		assertFalse(SaveCleanCopy.cleaned(token, cleaned()));
		assertNull(SaveCleanCopy.get(token));
	}

	private static SharedMediaSanitizer.Cleaned cleaned() {
		SharedMediaSanitizer.Cleaned c =
				Mockito.mock(SharedMediaSanitizer.Cleaned.class);
		Mockito.when(c.getMimeType()).thenReturn("image/jpeg");
		Mockito.when(c.getData()).thenReturn(new byte[] {3});
		return c;
	}
}
