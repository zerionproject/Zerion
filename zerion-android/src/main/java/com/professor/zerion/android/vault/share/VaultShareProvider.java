package com.professor.zerion.android.vault.share;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;

import javax.annotation.Nullable;

@NotNullByDefault
public class VaultShareProvider extends ContentProvider {

	private static final int CHUNK = 64 * 1024;

	@Override
	public boolean onCreate() {
		return true;
	}

	@Nullable
	@Override
	public Cursor query(Uri uri, @Nullable String[] projection,
			@Nullable String selection, @Nullable String[] selectionArgs,
			@Nullable String sortOrder) {
		VaultShareRegistry.Entry e = VaultShareRegistry.get(uri);
		if (e == null) return null;
		String[] columns = projection != null ? projection
				: new String[] {OpenableColumns.DISPLAY_NAME,
				OpenableColumns.SIZE};
		Object[] row = new Object[columns.length];
		for (int i = 0; i < columns.length; i++) {
			if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
				row[i] = e.name;
			} else if (OpenableColumns.SIZE.equals(columns[i])) {
				row[i] = (long) e.data.length;
			}
		}
		MatrixCursor c = new MatrixCursor(columns, 1);
		c.addRow(row);
		return c;
	}

	@Nullable
	@Override
	public String getType(Uri uri) {
		VaultShareRegistry.Entry e = VaultShareRegistry.get(uri);
		return e == null ? null : e.mimeType;
	}

	@Override
	public ParcelFileDescriptor openFile(Uri uri, String mode)
			throws FileNotFoundException {
		if (!"r".equals(mode)) throw new FileNotFoundException(mode);
		VaultShareRegistry.Entry e = VaultShareRegistry.get(uri);
		if (e == null) throw new FileNotFoundException();
		ParcelFileDescriptor[] pipe;
		try {
			pipe = ParcelFileDescriptor.createReliablePipe();
		} catch (IOException ex) {
			throw new FileNotFoundException();
		}
		ParcelFileDescriptor writeEnd = pipe[1];
		new Thread(() -> stream(e, writeEnd), "VaultShareStream").start();
		return pipe[0];
	}

	private static void stream(VaultShareRegistry.Entry e,
			ParcelFileDescriptor writeEnd) {
		byte[] buf = new byte[CHUNK];
		OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(
				writeEnd);
		try {
			int offset = 0;
			while (true) {
				int n = VaultShareRegistry.read(e, offset, buf);
				if (n < 0) {
					writeEnd.closeWithError("released");
					return;
				}
				if (n == 0) break;
				out.write(buf, 0, n);
				offset += n;
			}
			out.close();
		} catch (IOException ignored) {
		} finally {
			java.util.Arrays.fill(buf, (byte) 0);
			try {
				out.close();
			} catch (IOException ignored) {
			}
		}
	}

	@Nullable
	@Override
	public Uri insert(Uri uri, @Nullable ContentValues values) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int delete(Uri uri, @Nullable String selection,
			@Nullable String[] selectionArgs) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int update(Uri uri, @Nullable ContentValues values,
			@Nullable String selection, @Nullable String[] selectionArgs) {
		throw new UnsupportedOperationException();
	}
}
