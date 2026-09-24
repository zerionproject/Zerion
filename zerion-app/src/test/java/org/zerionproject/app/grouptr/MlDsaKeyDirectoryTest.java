package org.zerionproject.app.grouptr;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * PROTO-16: a miss is looked up again next time, a hit is cached, and
 * invalidation forgets the hits.
 */
public class MlDsaKeyDirectoryTest {

	private final byte[] ed = new byte[] {1, 2, 3};
	private final byte[] mlDsa = new byte[] {9, 9, 9};

	@Test
	public void aMissIsNeverRemembered() throws Exception {
		AtomicReference<byte[]> answer = new AtomicReference<>(null);
		AtomicInteger calls = new AtomicInteger();
		MlDsaKeyDirectory d = new MlDsaKeyDirectory(pk -> {
			calls.incrementAndGet();
			return answer.get();
		});
		assertNull(d.lookup(ed));
		assertNull(d.lookup(ed));
		assertEquals("every miss asks the source again", 2, calls.get());
		answer.set(mlDsa);
		assertArrayEquals("the key is found as soon as it exists", mlDsa,
				d.lookup(ed));
		assertArrayEquals(mlDsa, d.lookup(ed));
		assertEquals("a hit is cached", 3, calls.get());
	}

	@Test
	public void sourcesAreConsultedInOrderAndInvalidationForgetsHits()
			throws Exception {
		AtomicInteger first = new AtomicInteger();
		AtomicInteger second = new AtomicInteger();
		MlDsaKeyDirectory d = new MlDsaKeyDirectory(pk -> {
			first.incrementAndGet();
			return null;
		}, pk -> {
			second.incrementAndGet();
			return mlDsa;
		});
		assertArrayEquals(mlDsa, d.lookup(ed));
		assertArrayEquals(mlDsa, d.lookup(ed));
		assertEquals(1, first.get());
		assertEquals(1, second.get());
		d.invalidate();
		assertArrayEquals(mlDsa, d.lookup(ed));
		assertEquals(2, second.get());
	}

}
