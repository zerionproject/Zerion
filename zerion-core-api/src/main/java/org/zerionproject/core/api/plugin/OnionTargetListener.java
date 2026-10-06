package org.zerionproject.core.api.plugin;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;

@NotNullByDefault
public interface OnionTargetListener extends Closeable {

	String getTorTarget();

	Socket accept() throws IOException;

	boolean isClosed();
}
