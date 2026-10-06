package org.zerionproject.core.data;

import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.BdfWriterFactory;

public final class BdfForTests {

	private BdfForTests() {
	}

	public static BdfReaderFactory readers() {
		return new BdfReaderFactoryImpl();
	}

	public static BdfWriterFactory writers() {
		return new BdfWriterFactoryImpl();
	}
}
