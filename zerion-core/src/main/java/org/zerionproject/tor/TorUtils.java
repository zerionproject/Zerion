package org.zerionproject.tor;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

import javax.annotation.Nullable;

@NotNullByDefault
final class TorUtils {

	static final Charset UTF_8 = Charset.forName("UTF-8");

	private TorUtils() {
	}

	/**
	 * Copies the input to the output and closes both. A failure while
	 * copying is reported to the caller after both streams are closed, so
	 * that a partly written file is never mistaken for a complete one.
	 */
	static void copyAndClose(InputStream in, OutputStream out)
			throws IOException {
		byte[] buf = new byte[4096];
		try {
			while (true) {
				int read = in.read(buf);
				if (read == -1) break;
				out.write(buf, 0, read);
			}
			out.flush();
		} finally {
			tryToClose(in);
			tryToClose(out);
		}
	}

	static void tryToClose(@Nullable Closeable c) {
		try {
			if (c != null) c.close();
		} catch (IOException ignored) {
		}
	}
}
