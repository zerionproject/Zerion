package com.professor.zerion.android.settings;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import com.professor.zerion.R;
import com.professor.zerion.android.backup.AccountBackupManager;
import com.professor.zerion.android.backup.BackupException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.backup.BackupException.Reason.IO_ERROR;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class BackupExportDiscardTest {

	private static final String AUTHORITY =
			"com.professor.zerion.test.backupdocuments";
	private static final Uri DOCUMENT =
			Uri.parse("content://" + AUTHORITY + "/document/backup");
	private static final byte[] EARLIER =
			"an earlier backup".getBytes(StandardCharsets.US_ASCII);

	private static final List<String> deleted = new CopyOnWriteArrayList<>();
	@Nullable
	private static volatile File file;

	public static final class Documents extends ContentProvider {

		@Override
		public boolean onCreate() {
			return true;
		}

		@Override
		public Cursor query(Uri uri, @Nullable String[] projection,
				@Nullable String selection, @Nullable String[] selectionArgs,
				@Nullable String sortOrder) {
			MatrixCursor c = new MatrixCursor(
					new String[] {OpenableColumns.SIZE});
			c.addRow(new Object[] {file.length()});
			return c;
		}

		@Override
		public ParcelFileDescriptor openFile(Uri uri, String mode)
				throws FileNotFoundException {
			return ParcelFileDescriptor.open(file,
					ParcelFileDescriptor.parseMode(mode));
		}

		@Override
		public Bundle call(String method, @Nullable String arg,
				@Nullable Bundle extras) {
			if ("android:deleteDocument".equals(method)) {
				deleted.add(method);
				file.delete();
			}
			return null;
		}

		@Nullable
		@Override
		public String getType(Uri uri) {
			return "application/octet-stream";
		}

		@Nullable
		@Override
		public Uri insert(Uri uri, @Nullable ContentValues values) {
			return null;
		}

		@Override
		public int delete(Uri uri, @Nullable String selection,
				@Nullable String[] selectionArgs) {
			return 0;
		}

		@Override
		public int update(Uri uri, @Nullable ContentValues values,
				@Nullable String selection, @Nullable String[] selectionArgs) {
			return 0;
		}
	}

	@Before
	public void setUp() throws IOException {
		deleted.clear();
		File f = new File(RuntimeEnvironment.getApplication().getCacheDir(),
				"chosen-document.zbk");
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(EARLIER);
		}
		file = f;
		Robolectric.setupContentProvider(Documents.class, AUTHORITY);
	}

	@After
	public void tearDown() {
		File f = file;
		if (f != null) f.delete();
	}

	private static AccountBackupManager failing(boolean writeFirst)
			throws BackupException {
		AccountBackupManager manager = mock(AccountBackupManager.class);
		doAnswer(inv -> {
			if (writeFirst) {
				AccountBackupManager.Destination d = inv.getArgument(1);
				try (OutputStream out = d.open()) {
					out.write(new byte[] {'Z', 'B'});
				}
			}
			throw new BackupException(IO_ERROR);
		}).when(manager).exportAccount(any(char[].class),
				any(AccountBackupManager.Destination.class));
		return manager;
	}

	@Test
	public void aReplacedBackupSurvivesAnExportThatFailsBeforeWriting()
			throws Exception {
		int message = BackupFragment.export(failing(false),
				RuntimeEnvironment.getApplication(), DOCUMENT,
				"passphrase".toCharArray());
		assertEquals(R.string.backup_export_failed, message);
		assertTrue("the earlier backup was deleted", file.exists());
		assertTrue(deleted.isEmpty());
		assertArrayEquals(EARLIER, Files.readAllBytes(file.toPath()));
	}

	@Test
	public void aPartlyWrittenExportIsDeleted() throws Exception {
		int message = BackupFragment.export(failing(true),
				RuntimeEnvironment.getApplication(), DOCUMENT,
				"passphrase".toCharArray());
		assertEquals(R.string.backup_export_failed, message);
		assertEquals(1, deleted.size());
		assertFalse(file.exists());
	}

	@Test
	public void anEmptyNewDocumentIsDeletedWhenTheExportFailsBeforeWriting()
			throws Exception {
		try (FileOutputStream out = new FileOutputStream(file)) {
			out.flush();
		}
		BackupFragment.export(failing(false),
				RuntimeEnvironment.getApplication(), DOCUMENT,
				"passphrase".toCharArray());
		assertEquals(1, deleted.size());
		assertFalse(file.exists());
	}
}
