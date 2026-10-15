package com.professor.zerion.android.backup;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import javax.annotation.Nullable;

import static com.professor.zerion.android.backup.BackupException.Reason.CORRUPT;
import static com.professor.zerion.android.backup.BackupException.Reason.UNSUPPORTED_VERSION;

@NotNullByDefault
final class BundleStreamParser extends OutputStream {

	static final class Malformed extends IOException {

		final BackupException.Reason reason;

		Malformed(BackupException.Reason reason) {
			this.reason = reason;
		}
	}

	private enum Field {
		VERSION, NAME_LENGTH, NAME, KEY_LENGTH, KEY, DB_LENGTH, DB,
		VAULT_LENGTH, DONE
	}

	private final OutputStream db;
	private final byte[] number = new byte[4];
	private final byte[] key = new byte[BackupBundle.KEY_LENGTH];
	private Field field = Field.VERSION;
	private int numberCount = 0;
	private byte[] name = new byte[0];
	private int nameCount = 0;
	private int keyCount = 0;
	private long dbRemaining = 0;
	@Nullable
	private String displayName;

	BundleStreamParser(OutputStream db) {
		this.db = db;
	}

	@Override
	public void write(int b) throws IOException {
		write(new byte[] {(byte) b}, 0, 1);
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		while (len > 0) {
			int used;
			switch (field) {
				case VERSION:
					used = number(b, off, len, 4);
					if (numberCount == 4) {
						if (int32() != BackupBundle.BUNDLE_VERSION) {
							throw new Malformed(UNSUPPORTED_VERSION);
						}
						next(Field.NAME_LENGTH);
					}
					break;
				case NAME_LENGTH:
					used = number(b, off, len, 2);
					if (numberCount == 2) {
						name = new byte[((number[0] & 0xFF) << 8)
								| (number[1] & 0xFF)];
						nameCount = 0;
						next(Field.NAME);
						if (name.length == 0) nameDone();
					}
					break;
				case NAME:
					used = Math.min(len, name.length - nameCount);
					System.arraycopy(b, off, name, nameCount, used);
					nameCount += used;
					if (nameCount == name.length) nameDone();
					break;
				case KEY_LENGTH:
					used = number(b, off, len, 4);
					if (numberCount == 4) {
						if (int32() != key.length) throw new Malformed(CORRUPT);
						next(Field.KEY);
					}
					break;
				case KEY:
					used = Math.min(len, key.length - keyCount);
					System.arraycopy(b, off, key, keyCount, used);
					keyCount += used;
					if (keyCount == key.length) next(Field.DB_LENGTH);
					break;
				case DB_LENGTH:
					used = number(b, off, len, 4);
					if (numberCount == 4) {
						int n = int32();
						if (n < 0) throw new Malformed(CORRUPT);
						dbRemaining = n;
						next(dbRemaining == 0 ? Field.VAULT_LENGTH : Field.DB);
					}
					break;
				case DB:
					used = (int) Math.min(len, dbRemaining);
					db.write(b, off, used);
					dbRemaining -= used;
					if (dbRemaining == 0) next(Field.VAULT_LENGTH);
					break;
				case VAULT_LENGTH:
					used = number(b, off, len, 4);
					if (numberCount == 4) {
						if (int32() != 0) throw new Malformed(CORRUPT);
						next(Field.DONE);
					}
					break;
				default:
					throw new Malformed(CORRUPT);
			}
			off += used;
			len -= used;
		}
	}

	private int number(byte[] b, int off, int len, int size) {
		int used = Math.min(len, size - numberCount);
		System.arraycopy(b, off, number, numberCount, used);
		numberCount += used;
		return used;
	}

	private int int32() {
		return ((number[0] & 0xFF) << 24) | ((number[1] & 0xFF) << 16)
				| ((number[2] & 0xFF) << 8) | (number[3] & 0xFF);
	}

	private void next(Field f) {
		field = f;
		numberCount = 0;
	}

	private void nameDone() throws Malformed {
		byte[] withLength = new byte[name.length + 2];
		withLength[0] = (byte) (name.length >>> 8);
		withLength[1] = (byte) name.length;
		System.arraycopy(name, 0, withLength, 2, name.length);
		try {
			displayName = new DataInputStream(
					new ByteArrayInputStream(withLength)).readUTF();
		} catch (IOException e) {
			throw new Malformed(CORRUPT);
		}
		next(Field.KEY_LENGTH);
	}

	void finish() throws Malformed {
		if (field != Field.DONE) throw new Malformed(CORRUPT);
	}

	String displayName() throws Malformed {
		String n = displayName;
		if (field != Field.DONE || n == null) throw new Malformed(CORRUPT);
		return n;
	}

	byte[] dbKey() throws Malformed {
		if (field != Field.DONE) throw new Malformed(CORRUPT);
		return key.clone();
	}

	void wipe() {
		Arrays.fill(key, (byte) 0);
		Arrays.fill(number, (byte) 0);
	}
}
