package com.professor.zerion.android.backup;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static com.professor.zerion.android.backup.BackupException.Reason.CORRUPT;
import static com.professor.zerion.android.backup.BackupException.Reason.UNSUPPORTED_VERSION;

@NotNullByDefault
public class BackupBundle {

	static final int BUNDLE_VERSION = 1;
	static final int KEY_LENGTH = 32;
	static final int MAX_BUNDLE_BYTES = 512 * 1024 * 1024;
	private static final int MAX_NAME_BYTES = 0xFFFF;
	private static final int FIXED_BYTES = 4 + 2 + 4 + 4 + 4;

	interface BlockSource {
		void readInto(byte[] buf, int off, int len) throws IOException;
	}

	public final String displayName;
	public final byte[] dbKey;
	public final byte[] dbFile;
	@Nullable
	public final byte[] vault;

	public BackupBundle(String displayName, byte[] dbKey, byte[] dbFile,
			@Nullable byte[] vault) {
		this.displayName = displayName;
		this.dbKey = dbKey;
		this.dbFile = dbFile;
		this.vault = vault;
	}

	public byte[] toBytes() {
		try {
			return encode(displayName, dbKey, dbFile.length,
					(buf, off, len) -> System.arraycopy(dbFile, 0, buf, off,
							len), vault);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	static byte[] encode(String displayName, byte[] dbKey, long dbLength,
			BlockSource db, @Nullable byte[] vault) throws IOException {
		if (dbKey.length != KEY_LENGTH) throw new IOException("bad key length");
		byte[] name = modifiedUtf8(displayName);
		int vaultLength = vault == null ? 0 : vault.length;
		long total = FIXED_BYTES + (long) name.length + dbKey.length
				+ dbLength + vaultLength;
		if (dbLength < 0 || total > MAX_BUNDLE_BYTES) {
			throw new IOException("bundle too large");
		}
		byte[] out = new byte[(int) total];
		boolean done = false;
		try {
			ByteBuffer buf = ByteBuffer.wrap(out);
			buf.putInt(BUNDLE_VERSION);
			buf.putShort((short) name.length);
			buf.put(name);
			buf.putInt(dbKey.length);
			buf.put(dbKey);
			buf.putInt((int) dbLength);
			db.readInto(out, buf.position(), (int) dbLength);
			buf.position(buf.position() + (int) dbLength);
			buf.putInt(vaultLength);
			if (vault != null) buf.put(vault);
			done = true;
			return out;
		} finally {
			if (!done) Arrays.fill(out, (byte) 0);
		}
	}

	static byte[] prefix(String displayName, byte[] dbKey, long dbLength)
			throws IOException {
		if (dbKey.length != KEY_LENGTH) throw new IOException("bad key length");
		if (dbLength < 0 || dbLength > Integer.MAX_VALUE) {
			throw new IOException("database too large");
		}
		byte[] name = modifiedUtf8(displayName);
		ByteBuffer buf = ByteBuffer.allocate(4 + 2 + name.length + 4
				+ KEY_LENGTH + 4);
		buf.putInt(BUNDLE_VERSION);
		buf.putShort((short) name.length);
		buf.put(name);
		buf.putInt(KEY_LENGTH);
		buf.put(dbKey);
		buf.putInt((int) dbLength);
		return buf.array();
	}

	static byte[] suffix() {
		return new byte[4];
	}

	private static byte[] modifiedUtf8(String s) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		new DataOutputStream(bytes).writeUTF(s);
		byte[] withLength = bytes.toByteArray();
		return Arrays.copyOfRange(withLength, 2, withLength.length);
	}

	public static BackupBundle fromBytes(byte[] bytes) throws BackupException {
		if (bytes.length > MAX_BUNDLE_BYTES) {
			throw new BackupException(CORRUPT);
		}
		ByteArrayInputStream source = new ByteArrayInputStream(bytes);
		DataInputStream in = new DataInputStream(source);
		byte[] dbKey = null;
		byte[] dbFile = null;
		byte[] vault = null;
		boolean done = false;
		try {
			int version = in.readInt();
			if (version != BUNDLE_VERSION) {
				throw new BackupException(UNSUPPORTED_VERSION);
			}
			String displayName = readName(in, source);
			dbKey = readBlock(in, source, KEY_LENGTH, KEY_LENGTH);
			dbFile = readBlock(in, source, 0, MAX_BUNDLE_BYTES);
			vault = readBlock(in, source, 0, MAX_BUNDLE_BYTES);
			if (source.available() != 0) throw new IOException("trailing data");
			BackupBundle bundle = new BackupBundle(displayName, dbKey, dbFile,
					vault.length == 0 ? null : vault);
			done = true;
			return bundle;
		} catch (IOException e) {
			throw new BackupException(CORRUPT);
		} finally {
			if (!done) {
				if (dbKey != null) Arrays.fill(dbKey, (byte) 0);
				if (dbFile != null) Arrays.fill(dbFile, (byte) 0);
				if (vault != null) Arrays.fill(vault, (byte) 0);
			}
		}
	}

	private static String readName(DataInputStream in,
			ByteArrayInputStream source) throws IOException {
		in.mark(2);
		int len = in.readUnsignedShort();
		if (len > MAX_NAME_BYTES || len > source.available()) {
			throw new IOException("bad name length");
		}
		in.reset();
		return in.readUTF();
	}

	private static byte[] readBlock(DataInputStream in,
			ByteArrayInputStream source, int min, int max) throws IOException {
		int len = in.readInt();
		if (len < min || len > max || len > source.available()) {
			throw new IOException("bad block length");
		}
		byte[] b = new byte[len];
		in.readFully(b);
		return b;
	}

	public void clear() {
		Arrays.fill(dbKey, (byte) 0);
		Arrays.fill(dbFile, (byte) 0);
		if (vault != null) Arrays.fill(vault, (byte) 0);
	}
}
