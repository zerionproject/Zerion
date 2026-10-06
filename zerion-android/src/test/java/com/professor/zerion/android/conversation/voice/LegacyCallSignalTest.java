package com.professor.zerion.android.conversation.voice;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LegacyCallSignalTest {

	@Test
	public void recognisesTheStoredPrefixesOnly() {
		assertTrue(LegacyCallSignal.isSignal("\0ZSIG\001\0{\"t\":\"offer\"}:abcd"));
		assertFalse(LegacyCallSignal.isSignal("ZSIG{\"t\":\"offer\"}"));
		assertFalse(LegacyCallSignal.isSignal(null));
		assertTrue(LegacyCallSignal.isCallEventText("VOICE_CALL:CALL_END:x"));
		assertFalse(LegacyCallSignal.isCallEventText("hello"));
	}

	@Test
	public void helperExposesNoParser() {
		for (Method m : LegacyCallSignal.class.getDeclaredMethods()) {
			if (!Modifier.isPublic(m.getModifiers())) continue;
			assertEquals("only boolean predicates: " + m.getName(),
					boolean.class, m.getReturnType());
		}
	}

	@Test
	public void wireParserClassIsGone() {
		try {
			Class.forName("com.professor.zerion.android.conversation.voice"
					+ ".VoiceCallSignal");
			throw new AssertionError("legacy signal parser still present");
		} catch (ClassNotFoundException expected) {
		}
	}
}
