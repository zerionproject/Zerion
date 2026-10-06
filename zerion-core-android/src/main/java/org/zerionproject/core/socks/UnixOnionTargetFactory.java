package org.zerionproject.core.socks;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.plugin.OnionTargetFactory;
import org.zerionproject.core.api.plugin.OnionTargetListener;
import org.zerionproject.core.api.plugin.OnionTargets;
import org.zerionproject.core.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Locale;

@NotNullByDefault
public final class UnixOnionTargetFactory implements OnionTargetFactory {

	private static final int NAME_BYTES = 8;

	private final File dir;
	private final SecureRandom random = new SecureRandom();
	private final Object dirLock = new Object();
	private boolean swept = false;

	public UnixOnionTargetFactory(File dir) {
		this.dir = dir;
	}

	@Override
	public OnionTargetListener open() throws IOException {
		File path = newSocketPath();
		String target;
		try {
			target = OnionTargets.unix(path);
		} catch (IllegalArgumentException e) {
			throw new IOException("onion target path", e);
		}
		LocalSocket bound = new LocalSocket(LocalSocket.SOCKET_STREAM);
		try {
			bound.bind(new LocalSocketAddress(path.getAbsolutePath(),
					LocalSocketAddress.Namespace.FILESYSTEM));
			LocalServerSocket server =
					new LocalServerSocket(bound.getFileDescriptor());
			return new Listener(path, target, bound, server);
		} catch (IOException | RuntimeException e) {
			try {
				bound.close();
			} catch (IOException ignored) {
			}
			path.delete();
			throw e;
		}
	}

	private File newSocketPath() throws IOException {
		synchronized (dirLock) {
			prepareDirectory();
			byte[] name = new byte[NAME_BYTES];
			random.nextBytes(name);
			return new File(dir,
					StringUtils.toHexString(name).toLowerCase(Locale.US));
		}
	}

	private void prepareDirectory() throws IOException {
		if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
			throw new IOException("onion target directory");
		}
		Path p = dir.toPath();
		if (p.getFileSystem().supportedFileAttributeViews()
				.contains("posix")) {
			Files.setPosixFilePermissions(p, EnumSet.of(
					PosixFilePermission.OWNER_READ,
					PosixFilePermission.OWNER_WRITE,
					PosixFilePermission.OWNER_EXECUTE));
		}
		if (swept) return;
		swept = true;
		File[] stale = dir.listFiles();
		if (stale == null) return;
		for (File f : stale) f.delete();
	}

	private static final class Listener implements OnionTargetListener {

		private final File path;
		private final String target;
		private final LocalSocket bound;
		private final LocalServerSocket server;
		private volatile boolean closed = false;

		private Listener(File path, String target, LocalSocket bound,
				LocalServerSocket server) {
			this.path = path;
			this.target = target;
			this.bound = bound;
			this.server = server;
		}

		@Override
		public String getTorTarget() {
			return target;
		}

		@Override
		public Socket accept() throws IOException {
			if (closed) throw new SocketException("closed");
			LocalSocket s = server.accept();
			if (closed) {
				try {
					s.close();
				} catch (IOException ignored) {
				}
				throw new SocketException("closed");
			}
			return LocalSockets.wrap(s);
		}

		@Override
		public boolean isClosed() {
			return closed;
		}

		@Override
		public void close() {
			if (closed) return;
			closed = true;
			try {
				Os.shutdown(bound.getFileDescriptor(), OsConstants.SHUT_RDWR);
			} catch (ErrnoException | RuntimeException ignored) {
			}
			wake();
			try {
				server.close();
			} catch (IOException ignored) {
			}
			try {
				bound.close();
			} catch (IOException ignored) {
			}
			path.delete();
		}

		private void wake() {
			LocalSocket s = new LocalSocket(LocalSocket.SOCKET_STREAM);
			try {
				s.connect(new LocalSocketAddress(path.getAbsolutePath(),
						LocalSocketAddress.Namespace.FILESYSTEM));
			} catch (IOException ignored) {
			} finally {
				try {
					s.close();
				} catch (IOException ignored) {
				}
			}
		}
	}
}
