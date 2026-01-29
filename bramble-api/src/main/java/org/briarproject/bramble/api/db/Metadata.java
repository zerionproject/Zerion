package org.briarproject.bramble.api.db;

import java.util.TreeMap;

import javax.annotation.concurrent.NotThreadSafe;

@NotThreadSafe
public class Metadata extends TreeMap<String, byte[]> {

	
	public static final byte[] REMOVE = new byte[0];
}
