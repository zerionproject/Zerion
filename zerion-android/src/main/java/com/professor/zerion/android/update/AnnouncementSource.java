package com.professor.zerion.android.update;

import android.content.Context;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.annotation.Nullable;
import javax.net.SocketFactory;

import static com.professor.zerion.android.TestingConstants.IS_DEBUG_BUILD;

@NotNullByDefault
interface AnnouncementSource {

	String TEST_DIR = "update-test";
	String MANIFEST_FILE = "release-manifest.json";
	String SIGNATURE_FILE = "release-manifest.json.sig";

	final class Fetched {
		@Nullable
		final byte[] manifest;
		@Nullable
		final byte[] signature;

		Fetched(@Nullable byte[] manifest, @Nullable byte[] signature) {
			this.manifest = manifest;
			this.signature = signature;
		}
	}

	Fetched fetch() throws IOException;

	static AnnouncementSource overTor(Context ctx, SocketFactory torSockets,
			String manifestUrl) {
		return () -> {
			if (IS_DEBUG_BUILD) {
				File dir = new File(ctx.getFilesDir(), TEST_DIR);
				File m = new File(dir, MANIFEST_FILE);
				if (m.isFile()) {
					File s = new File(dir, SIGNATURE_FILE);
					return new Fetched(
							read(m, ReleaseAnnouncements.MAX_MANIFEST_BYTES),
							s.isFile() ? read(s,
									ReleaseAnnouncements.MAX_SIGNATURE_BYTES)
									: null);
				}
			}
			TorHttpsGet.Response m = TorHttpsGet.get(torSockets, manifestUrl,
					ReleaseAnnouncements.MAX_MANIFEST_BYTES);
			if (m.status != 200) return new Fetched(null, null);
			TorHttpsGet.Response s = TorHttpsGet.get(torSockets,
					manifestUrl + ".sig",
					ReleaseAnnouncements.MAX_SIGNATURE_BYTES);
			return new Fetched(m.body, s.status == 200 ? s.body : null);
		};
	}

	static byte[] read(File f, int limit) throws IOException {
		try (InputStream in = new FileInputStream(f)) {
			return TorHttpsGet.readAll(in, limit);
		}
	}
}
