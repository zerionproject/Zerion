package org.zerionproject.core.util;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;

import javax.annotation.Nullable;

@NotNullByDefault
public class IoUtils {

	public static void deleteFileOrDir(File f) {
		if (f.isFile()) {
			delete(f);
		} else if (f.isDirectory()) {
			File[] children = f.listFiles();
			if (children != null) {
				for (File child : children) deleteFileOrDir(child);
			}
			delete(f);
		}
	}

	public static void delete(File f) {
		if (f.isFile()) {
			try {
				long len = f.length();
				if (len > 0) {
					byte[] buf = new byte[4096];
					SecureRandom random = new SecureRandom();
					try (RandomAccessFile raf =
							new RandomAccessFile(f, "rw")) {
						long written = 0;
						while (written < len) {
							random.nextBytes(buf);
							int n = (int) Math.min(buf.length, len - written);
							raf.write(buf, 0, n);
							written += n;
						}
						raf.getFD().sync();
					}
				}
			} catch (IOException e) {
			} catch (SecurityException e) {
			}
		}
		f.delete();
	}

	public static void copyAndClose(InputStream in, OutputStream out) {
		byte[] buf = new byte[4096];
		try {
			while (true) {
				int read = in.read(buf);
				if (read == -1) break;
				out.write(buf, 0, read);
			}
			in.close();
			out.flush();
			out.close();
		} catch (IOException e) {
			tryToClose(in);
			tryToClose(out);
		}
	}

	public static void tryToClose(@Nullable Closeable c) {
		try {
			if (c != null) c.close();
		} catch (IOException e) {
		}
	}

	public static void tryToClose(@Nullable Socket s) {
		try {
			if (s != null) s.close();
		} catch (IOException e) {
		}
	}

	public static void tryToClose(@Nullable ServerSocket ss) {
		try {
			if (ss != null) ss.close();
		} catch (IOException e) {
		}
	}

	public static void read(InputStream in, byte[] b) throws IOException {
		int offset = 0;
		while (offset < b.length) {
			int read = in.read(b, offset, b.length - offset);
			if (read == -1) throw new EOFException();
			offset += read;
		}
	}

	public static InputStream getInputStream(Socket s) throws IOException {
		try {
			return s.getInputStream();
		} catch (NullPointerException e) {
			throw new IOException(e);
		}
	}

	public static OutputStream getOutputStream(Socket s) throws IOException {
		try {
			return s.getOutputStream();
		} catch (NullPointerException e) {
			throw new IOException(e);
		}
	}

	public static boolean isNonEmptyDirectory(File f) {
		if (!f.isDirectory()) return false;
		File[] children = f.listFiles();
		return children != null && children.length > 0;
	}
}
