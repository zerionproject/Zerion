package com.professor.zerion.android.attachment;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.view.MenuItem;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.TextView;
import android.widget.Toast;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.util.ActivityLaunchers.CreateDocumentAdvanced;
import com.professor.zerion.android.util.ActivityLaunchers.OpenDocumentAdvanced;
import com.professor.zerion.android.util.CacheSweeper;
import com.professor.zerion.android.util.PickedUris;
import com.professor.zerion.android.util.ReceivedImageGate;
import com.professor.zerion.android.util.SafeFileNames;
import com.professor.zerion.android.util.SavedFileNames;
import com.professor.zerion.android.vault.utils.MetadataStripper;
import com.professor.zerion.android.vault.utils.SecureMemory;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import androidx.activity.result.ActivityResultLauncher;
import androidx.core.content.FileProvider;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class CleanFileActivity extends ZerionActivity {

	static final long MAX_BYTES = SaveCleanCopy.MAX_BYTES;
	static final long SHRED_SHARED_AFTER_MS = 10 * 60 * 1000L;
	private static final String UNKNOWN = "application/octet-stream";
	private static final Executor WORKER =
			Executors.newSingleThreadExecutor();
	private static final SecureRandom RANDOM = new SecureRandom();

	private static final class Picked {

		final String name;
		final String mimeType;
		final byte[] data;

		Picked(String name, String mimeType, byte[] data) {
			this.name = name;
			this.mimeType = mimeType;
			this.data = data;
		}
	}

	private static final class Result {

		final Picked picked;
		@Nullable
		final SharedMediaSanitizer.Cleaned clean;
		@Nullable
		final CleanFilePolicy.Outcome outcome;
		@Nullable
		final MediaRefusedException refused;
		final boolean unreadable;

		Result(Picked picked, SharedMediaSanitizer.Cleaned clean,
				CleanFilePolicy.Outcome outcome) {
			this.picked = picked;
			this.clean = clean;
			this.outcome = outcome;
			this.refused = null;
			this.unreadable = false;
		}

		Result(Picked picked, @Nullable MediaRefusedException refused) {
			this.picked = picked;
			this.clean = null;
			this.outcome = null;
			this.refused = refused;
			this.unreadable = refused == null;
		}
	}

	private ActivityResultLauncher<String[]> picker;
	private ActivityResultLauncher<String> saver;
	private TextView nameView;
	private TextView originalView;
	private TextView resultView;
	private TextView explanationView;
	private View progress;
	private View actions;
	private View choose;

	private int job = 0;
	@Nullable
	private byte[] clean;
	@Nullable
	private String cleanMime;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_clean_file);
		setUpCustomToolbar(false);
		setTitle(R.string.clean_file_title);
		picker = registerForActivityResult(new OpenDocumentAdvanced(),
				this::onPicked);
		saver = registerForActivityResult(new CreateDocumentAdvanced(),
				this::onSaveTarget);
		nameView = findViewById(R.id.cleanFileName);
		originalView = findViewById(R.id.cleanFileOriginal);
		resultView = findViewById(R.id.cleanFileResult);
		explanationView = findViewById(R.id.cleanFileExplanation);
		progress = findViewById(R.id.cleanFileProgress);
		actions = findViewById(R.id.cleanFileActions);
		choose = findViewById(R.id.cleanFileChoose);
		choose.setOnClickListener(v -> picker.launch(new String[] {"*/*"}));
		findViewById(R.id.cleanFileShare).setOnClickListener(v -> share());
		findViewById(R.id.cleanFileSave).setOnClickListener(v -> save());
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		job++;
		wipeClean();
		Context app = getApplicationContext();
		long before = System.currentTimeMillis() - SHRED_SHARED_AFTER_MS;
		WORKER.execute(() -> CacheSweeper.sweepDirOlderThan(app,
				CacheSweeper.CLEAN_SHARE_DIR, before));
	}

	private void wipeClean() {
		byte[] data = clean;
		clean = null;
		cleanMime = null;
		if (data != null) Arrays.fill(data, (byte) 0);
	}

	private void onPicked(@Nullable Uri picked) {
		Uri uri = PickedUris.accept(this, picked);
		if (uri == null) return;
		wipeClean();
		int key = ++job;
		nameView.setVisibility(View.GONE);
		originalView.setVisibility(View.GONE);
		resultView.setVisibility(View.GONE);
		explanationView.setVisibility(View.GONE);
		actions.setVisibility(View.GONE);
		progress.setVisibility(View.VISIBLE);
		choose.setEnabled(false);
		Context app = getApplicationContext();
		Toast.makeText(app, R.string.save_file_cleaning, Toast.LENGTH_SHORT)
				.show();
		WeakReference<CleanFileActivity> screen = new WeakReference<>(this);
		WORKER.execute(() -> {
			Result result = readAndClean(app, uri);
			new Handler(Looper.getMainLooper()).post(() -> {
				CleanFileActivity a = screen.get();
				if (a != null) a.cleaned(key, result);
			});
		});
	}

	private static Result readAndClean(Context app, Uri uri) {
		Picked picked;
		try {
			picked = read(app, uri);
		} catch (MediaRefusedException e) {
			return new Result(new Picked(describe(app, uri), UNKNOWN,
					new byte[0]), e);
		} catch (IOException | RuntimeException e) {
			return new Result(new Picked(describe(app, uri), UNKNOWN,
					new byte[0]), null);
		}
		try {
			CleanFilePolicy.Cleaner cleaner =
					CleanFilePolicy.cleanerFor(picked.data);
			SharedMediaSanitizer.Cleaned clean = clean(app, cleaner, picked);
			CleanFilePolicy.Outcome outcome =
					CleanFilePolicy.outcomeFor(cleaner, clean.getMimeType());
			if (clean.getData() != picked.data) {
				Arrays.fill(picked.data, (byte) 0);
			}
			return new Result(picked, clean, outcome);
		} catch (MediaRefusedException e) {
			Arrays.fill(picked.data, (byte) 0);
			return new Result(picked, e);
		} catch (IOException | RuntimeException e) {
			Arrays.fill(picked.data, (byte) 0);
			return new Result(picked, MediaRefusedException.cannotClean(
					new IOException(e)));
		} catch (OutOfMemoryError e) {
			Arrays.fill(picked.data, (byte) 0);
			return new Result(picked, MediaRefusedException.tooLarge(
					"file too large to clean"));
		}
	}

	private static SharedMediaSanitizer.Cleaned clean(Context app,
			CleanFilePolicy.Cleaner cleaner, Picked picked)
			throws IOException {
		if (cleaner == CleanFilePolicy.Cleaner.OFFICE) {
			byte[] out = new MetadataStripper(app)
					.stripOfficeMetadataOrThrow(picked.data);
			if (out.length > MAX_BYTES) {
				throw MediaRefusedException.tooLarge("cleaned file too large");
			}
			return new SharedMediaSanitizer.Cleaned(
					CleanFilePolicy.officeMimeType(picked.data), out);
		}
		byte[] checked;
		try {
			checked = ReceivedImageGate.checkedIfImage(picked.data);
		} catch (IOException e) {
			if (!CleanFilePolicy.mayDecodeInAppProcess(picked.data)) {
				throw MediaRefusedException.cannotClean(e);
			}
			return new SharedMediaSanitizer(app, false).sanitize(
					picked.mimeType, picked.data, MAX_BYTES);
		}
		byte[] result = null;
		try {
			SharedMediaSanitizer.Cleaned cleaned =
					new SharedMediaSanitizer(app, true).sanitize(
							picked.mimeType, checked, MAX_BYTES);
			result = cleaned.getData();
			return cleaned;
		} finally {
			if (checked != picked.data && checked != result) {
				Arrays.fill(checked, (byte) 0);
			}
		}
	}

	private static Picked read(Context app, Uri uri) throws IOException {
		ContentResolver resolver = app.getContentResolver();
		String displayName = null;
		long declaredSize = -1;
		try (Cursor c = resolver.query(uri, new String[] {
				OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
				null, null, null)) {
			if (c != null && c.moveToFirst()) {
				int name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
				int size = c.getColumnIndex(OpenableColumns.SIZE);
				if (name >= 0 && !c.isNull(name)) displayName = c.getString(name);
				if (size >= 0 && !c.isNull(size)) declaredSize = c.getLong(size);
			}
		} catch (RuntimeException ignored) {
		}
		if (declaredSize > MAX_BYTES) {
			throw MediaRefusedException.tooLarge("file too large to clean");
		}
		String mime = resolver.getType(uri);
		if (mime == null && displayName != null) {
			mime = fromExtension(displayName);
		}
		if (mime == null) mime = UNKNOWN;
		InputStream in = resolver.openInputStream(uri);
		if (in == null) throw new IOException("file not readable");
		byte[] data;
		try (InputStream source = in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[64 * 1024];
			int n;
			while ((n = source.read(buf)) != -1) {
				if (out.size() + n > MAX_BYTES) {
					throw MediaRefusedException.tooLarge(
							"file too large to clean");
				}
				out.write(buf, 0, n);
			}
			data = out.toByteArray();
		} catch (OutOfMemoryError e) {
			throw MediaRefusedException.tooLarge("file too large to clean");
		}
		String fallback = AttachmentKinds.describe(app, mime);
		return new Picked(SafeFileNames.sanitize(displayName, fallback),
				mime, data);
	}

	@Nullable
	private static String fromExtension(String displayName) {
		int dot = displayName.lastIndexOf('.');
		if (dot < 0 || dot == displayName.length() - 1) return null;
		String ext = displayName.substring(dot + 1).toLowerCase(Locale.ROOT);
		try {
			return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String describe(Context app, Uri uri) {
		return SafeFileNames.sanitize(uri.getLastPathSegment(),
				app.getString(R.string.attachment_kind_file));
	}

	private void cleaned(int key, Result result) {
		if (key != job || isFinishing() || isDestroyed()) {
			if (result.clean != null) {
				Arrays.fill(result.clean.getData(), (byte) 0);
			}
			return;
		}
		progress.setVisibility(View.GONE);
		choose.setEnabled(true);
		nameView.setText(result.picked.name);
		nameView.setVisibility(View.VISIBLE);
		if (result.picked.data.length > 0 || result.clean != null) {
			originalView.setText(getString(R.string.clean_file_original,
					AttachmentKinds.describe(this, result.picked.mimeType),
					Formatter.formatShortFileSize(this,
							result.picked.data.length)));
			originalView.setVisibility(View.VISIBLE);
		}
		if (result.clean == null || result.outcome == null) {
			int message;
			if (result.unreadable) {
				message = R.string.clean_file_read_failed;
			} else if (result.refused != null && result.refused.isTooLarge()) {
				message = R.string.clean_file_refused_too_large;
			} else {
				message = R.string.clean_file_refused_cannot_clean;
			}
			resultView.setText(message);
			resultView.setVisibility(View.VISIBLE);
			return;
		}
		clean = result.clean.getData();
		cleanMime = result.clean.getMimeType();
		resultView.setText(getString(R.string.clean_file_result,
				AttachmentKinds.describe(this, cleanMime),
				Formatter.formatShortFileSize(this, clean.length)));
		resultView.setVisibility(View.VISIBLE);
		explanationView.setText(CleanFilePolicy.explanation(result.outcome));
		explanationView.setVisibility(View.VISIBLE);
		actions.setVisibility(View.VISIBLE);
	}

	private String suggestedName(String mime) {
		return SavedFileNames.named(AttachmentKinds.describe(this, mime),
				mime, System.currentTimeMillis());
	}

	private void share() {
		byte[] data = clean;
		String mime = cleanMime;
		if (data == null || mime == null) return;
		String name = suggestedName(mime);
		Context app = getApplicationContext();
		choose.setEnabled(false);
		WeakReference<CleanFileActivity> screen = new WeakReference<>(this);
		WORKER.execute(() -> {
			File dir = new File(app.getCacheDir(), CacheSweeper.CLEAN_SHARE_DIR);
			boolean written = dir.mkdirs() || dir.isDirectory();
			File f = unusedFile(dir, name, RANDOM);
			if (written) {
				try (OutputStream out = new FileOutputStream(f)) {
					out.write(data);
				} catch (IOException | RuntimeException e) {
					written = false;
					SecureMemory.secureDeleteFile(f);
				}
			}
			boolean ok = written;
			new Handler(Looper.getMainLooper()).post(() -> {
				CleanFileActivity a = screen.get();
				if (a != null) a.send(ok ? f : null, mime);
			});
		});
	}

	static File unusedFile(File dir, String name, SecureRandom random) {
		File f = new File(dir, name);
		if (!f.exists()) return f;
		int dot = name.lastIndexOf('.');
		String stem = dot > 0 ? name.substring(0, dot) : name;
		String ext = dot > 0 ? name.substring(dot) : "";
		do {
			String suffix = String.format(Locale.ROOT, "%04x",
					random.nextInt(0x10000));
			f = new File(dir, stem + "-" + suffix + ext);
		} while (f.exists());
		return f;
	}

	private void send(@Nullable File f, String mime) {
		choose.setEnabled(true);
		if (isFinishing() || isDestroyed()) {
			if (f != null) WORKER.execute(() -> SecureMemory.secureDeleteFile(f));
			return;
		}
		if (f == null) {
			Toast.makeText(this, R.string.clean_file_share_failed,
					Toast.LENGTH_LONG).show();
			return;
		}
		try {
			Uri uri = FileProvider.getUriForFile(this,
					getPackageName() + ".fileprovider", f);
			Intent send = new Intent(Intent.ACTION_SEND);
			send.setType(mime);
			send.putExtra(Intent.EXTRA_STREAM, uri);
			send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			startActivity(Intent.createChooser(send,
					getString(R.string.clean_file_share_chooser)));
		} catch (RuntimeException e) {
			WORKER.execute(() -> SecureMemory.secureDeleteFile(f));
			Toast.makeText(this, R.string.clean_file_share_failed,
					Toast.LENGTH_LONG).show();
		}
	}

	private void save() {
		String mime = cleanMime;
		if (clean == null || mime == null) return;
		try {
			saver.launch(suggestedName(mime));
		} catch (RuntimeException e) {
			Toast.makeText(this, R.string.save_file_error, Toast.LENGTH_LONG)
					.show();
		}
	}

	private void onSaveTarget(@Nullable Uri uri) {
		if (uri == null) return;
		byte[] data = clean;
		if (data == null) {
			SavedFileNames.discard(getContentResolver(), uri);
			return;
		}
		byte[] copy = data.clone();
		Context app = getApplicationContext();
		choose.setEnabled(false);
		WeakReference<CleanFileActivity> screen = new WeakReference<>(this);
		WORKER.execute(() -> {
			boolean saved;
			try {
				saved = write(app, uri, copy);
			} finally {
				Arrays.fill(copy, (byte) 0);
			}
			if (!saved) SavedFileNames.discard(app.getContentResolver(), uri);
			new Handler(Looper.getMainLooper()).post(() -> {
				Toast.makeText(app, saved ? R.string.save_file_success
						: R.string.save_file_error, Toast.LENGTH_LONG).show();
				CleanFileActivity a = screen.get();
				if (a != null) a.choose.setEnabled(true);
			});
		});
	}

	private static boolean write(Context ctx, Uri uri, byte[] data) {
		OutputStream opened;
		try {
			opened = ctx.getContentResolver().openOutputStream(uri, "wt");
		} catch (IOException | RuntimeException e) {
			try {
				opened = ctx.getContentResolver().openOutputStream(uri, "w");
			} catch (IOException | RuntimeException again) {
				return false;
			}
		}
		if (opened == null) return false;
		try (OutputStream out = opened) {
			out.write(data);
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}
}
