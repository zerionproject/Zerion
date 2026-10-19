package com.professor.zerion.android.update;

import android.content.Context;
import android.os.SystemClock;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.tor.BridgeList;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

import javax.annotation.Nullable;
import javax.net.SocketFactory;

import static com.professor.zerion.android.TestingConstants.IS_DEBUG_BUILD;

@NotNullByDefault
interface BridgeListSource {

	List<String> URLS = Collections.singletonList(
			"https://raw.githubusercontent.com/zerionproject/Zerion/master/docs/bridges/bridges.json");

	long FETCH_DEADLINE_MS = 120_000L;

	String LIST_FILE = "bridges.json";
	String SIGNATURE_SUFFIX = ".sig";

	final class Fetched {
		final byte[] list;
		final byte[] signature;

		Fetched(byte[] list, byte[] signature) {
			this.list = list;
			this.signature = signature;
		}
	}

	@Nullable
	Fetched local() throws IOException;

	@Nullable
	Fetched remote() throws IOException;

	static BridgeListSource overTor(Context ctx, SocketFactory torSockets,
			List<String> urls) {
		return new BridgeListSource() {

			@Nullable
			@Override
			public Fetched local() throws IOException {
				String testDir = BridgeListDebug.TEST_DIR;
				if (!IS_DEBUG_BUILD || testDir == null) return null;
				File dir = new File(ctx.getFilesDir(), testDir);
				File list = new File(dir, LIST_FILE);
				File sig = new File(dir, LIST_FILE + SIGNATURE_SUFFIX);
				if (!list.isFile() || !sig.isFile()) return null;
				return new Fetched(read(list, BridgeList.MAX_FILE_BYTES),
						read(sig, BridgeList.MAX_SIGNATURE_FILE_BYTES));
			}

			@Nullable
			@Override
			public Fetched remote() throws IOException {
				return fetch(torSockets, urls, SystemClock::elapsedRealtime);
			}
		};
	}

	@Nullable
	static Fetched fetch(SocketFactory torSockets, List<String> urls,
			LongSupplier clock) throws IOException {
		long deadline = clock.getAsLong() + FETCH_DEADLINE_MS;
		IOException failure = null;
		for (String url : urls) {
			try {
				TorHttpsGet.Response l = TorHttpsGet.get(torSockets, url,
						BridgeList.MAX_FILE_BYTES, deadline, clock);
				if (l.status != 200) continue;
				TorHttpsGet.Response s = TorHttpsGet.get(torSockets,
						url + SIGNATURE_SUFFIX,
						BridgeList.MAX_SIGNATURE_FILE_BYTES, deadline, clock);
				if (s.status != 200) continue;
				return new Fetched(l.body, s.body);
			} catch (IOException e) {
				failure = e;
			}
		}
		if (failure != null) throw failure;
		return null;
	}

	static byte[] read(File f, int limit) throws IOException {
		try (InputStream in = new FileInputStream(f)) {
			return TorHttpsGet.readAll(in, limit);
		}
	}
}
