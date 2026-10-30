package com.professor.zerion.android.panic;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertNotEquals;

public class WipePasswordStringFreeTest {

	@Test
	public void noMethodTakesThePasswordAsAString() {
		for (Method m : WipePasswordManager.class.getDeclaredMethods()) {
			for (Class<?> p : m.getParameterTypes()) {
				assertNotEquals(m.getName() + " takes a String password",
						String.class, p);
			}
		}
	}
}
