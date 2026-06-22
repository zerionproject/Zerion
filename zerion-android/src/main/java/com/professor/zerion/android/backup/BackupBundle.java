package com.professor.zerion.android.backup;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static com.professor.zerion.android.backup.BackupException.Reason.CORRUPT;
import static com.professor.zerion.android.backup.BackupException.Reason.UNSUPPORTED_VERSION;

@NotNullByDefault
public class BackupBundle {

	static final int BUNDLE_VERSION = 1;
	private static final int MAX_BLOCK = 512 * 1024 * 1024;

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
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(baos);
			out.writeInt(BUNDLE_VERSION);
			out.writeUTF(displayName);
			writeBlock(out, dbKey);
			writeBlock(out, dbFile);
			writeBlock(out, vault == null ? new byte[0] : vault);
			out.flush();
			return baos.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static BackupBundle fromBytes(byte[] bytes) throws BackupException {
		try {
			DataInputStream in =
					new DataInputStream(new ByteArrayInputStream(bytes));
			int version = in.readInt();
			if (version != BUNDLE_VERSION) {
				throw new BackupException(UNSUPPORTED_VERSION);
			}
			String displayName = in.readUTF();
			byte[] dbKey = readBlock(in);
			byte[] dbFile = readBlock(in);
			byte[] vault = readBlock(in);
			return new BackupBundle(displayName, dbKey, dbFile,
					vault.length == 0 ? null : vault);
		} catch (IOException e) {
			throw new BackupException(CORRUPT);
		}
	}

	private static void writeBlock(DataOutputStream out, byte[] b)
			throws IOException {
		out.writeInt(b.length);
		out.write(b);
	}

	private static byte[] readBlock(DataInputStream in) throws IOException {
		int len = in.readInt();
		if (len < 0 || len > MAX_BLOCK) throw new IOException("bad block length");
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
