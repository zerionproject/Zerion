package com.professor.zerion.android.vault.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EventTest {

	@Test
	public void contentDeliveredExactlyOnce() {
		Event<String> e = new Event<>("send-review");
		assertFalse(e.isHandled());
		assertEquals("send-review", e.getIfNotHandled());
		assertTrue(e.isHandled());
	}

	@Test
	public void redeliveryReturnsNothing() {
		Event<String> e = new Event<>("send-review");
		e.getIfNotHandled();
		assertNull(e.getIfNotHandled());
		assertNull(e.getIfNotHandled());
	}

	@Test
	public void independentEventsEachDeliverOnce() {
		Event<String> first = new Event<>("a");
		Event<String> second = new Event<>("b");
		assertEquals("a", first.getIfNotHandled());
		assertEquals("b", second.getIfNotHandled());
		assertNull(first.getIfNotHandled());
	}
}
