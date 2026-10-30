package com.professor.zerion.android.util;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLSocket;

@NotNullByDefault
public final class TlsProtocols {

	private TlsProtocols() {
	}

	public static void restrict(SSLSocket ssl) {
		List<String> keep = new ArrayList<>();
		for (String p : ssl.getSupportedProtocols()) {
			if ("TLSv1.3".equals(p) || "TLSv1.2".equals(p)) keep.add(p);
		}
		if (!keep.isEmpty()) {
			ssl.setEnabledProtocols(keep.toArray(new String[0]));
		}
	}
}
