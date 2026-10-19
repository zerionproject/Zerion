package com.professor.zerion.android.attachment;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import com.professor.zerion.R;
import com.professor.zerion.android.util.ActivityLaunchers.CreateDocumentAdvanced;
import com.professor.zerion.android.util.SavedFileNames;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.io.OutputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class SaveCleanCopyActivity extends AppCompatActivity {

	private static final String STATE_TOKEN = "token";
	private static final String STATE_LAUNCHED = "launched";
	private static final Executor WORKER =
			Executors.newSingleThreadExecutor();

	@Nullable
	private String token;
	private boolean orphan = false;
	private boolean launched = false;
	private ActivityResultLauncher<String> saver;

	@Override
	protected void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		saver = registerForActivityResult(new CreateDocumentAdvanced(),
				this::onTarget);
		token = state != null ? state.getString(STATE_TOKEN)
				: getIntent().getStringExtra(SaveCleanCopy.EXTRA_TOKEN);
		launched = state != null && state.getBoolean(STATE_LAUNCHED, false);
		SaveCleanCopy.Pending pending =
				token == null ? null : SaveCleanCopy.get(token);
		if (pending == null) {
			if (launched && token != null) {
				orphan = true;
				return;
			}
			failed();
			return;
		}
		if (!pending.cleaned) clean(token);
	}

	@Override
	protected void onSaveInstanceState(Bundle out) {
		super.onSaveInstanceState(out);
		if (token != null) out.putString(STATE_TOKEN, token);
		out.putBoolean(STATE_LAUNCHED, launched);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (isFinishing() && token != null) SaveCleanCopy.drop(token);
	}

	private void clean(String key) {
		Context app = getApplicationContext();
		Toast.makeText(app, R.string.save_file_cleaning, Toast.LENGTH_SHORT)
				.show();
		WeakReference<SaveCleanCopyActivity> screen = new WeakReference<>(this);
		WORKER.execute(() -> {
			SaveCleanCopy.Pending raw = SaveCleanCopy.get(key);
			SharedMediaSanitizer.Cleaned clean = null;
			if (raw != null) {
				try {
					clean = new SharedMediaSanitizer(app).sanitize(
							raw.mimeType, raw.data, SaveCleanCopy.MAX_BYTES);
				} catch (IOException | RuntimeException e) {
					clean = null;
				}
			}
			SharedMediaSanitizer.Cleaned result = clean;
			SaveCleanCopyActivity a = screen.get();
			if (a != null) a.runOnUiThread(() -> a.cleaned(key, result));
		});
	}

	private void cleaned(String key,
			@Nullable SharedMediaSanitizer.Cleaned result) {
		if (isFinishing() || isDestroyed()) return;
		if (result == null) {
			failed();
			return;
		}
		if (!SaveCleanCopy.cleaned(key, result)) {
			failed();
			return;
		}
		try {
			saver.launch(SavedFileNames.named(
					AttachmentKinds.describe(this, result.getMimeType()),
					result.getMimeType(), System.currentTimeMillis()));
			launched = true;
		} catch (ActivityNotFoundException e) {
			failed();
		}
	}

	private void onTarget(@Nullable Uri uri) {
		if (orphan) {
			if (uri != null) {
				SavedFileNames.discard(getContentResolver(), uri);
			}
			failed();
			return;
		}
		SaveCleanCopy.Pending clean =
				token == null ? null : SaveCleanCopy.take(token);
		if (uri == null) {
			SaveCleanCopy.done(clean);
			finish();
			return;
		}
		if (clean == null || !clean.cleaned) {
			SaveCleanCopy.done(clean);
			SavedFileNames.discard(getContentResolver(), uri);
			failed();
			return;
		}
		Context app = getApplicationContext();
		WeakReference<SaveCleanCopyActivity> screen = new WeakReference<>(this);
		WORKER.execute(() -> {
			boolean saved = write(app, uri, clean.data);
			SaveCleanCopy.done(clean);
			if (!saved) SavedFileNames.discard(app.getContentResolver(), uri);
			android.os.Handler main =
					new android.os.Handler(android.os.Looper.getMainLooper());
			main.post(() -> {
				Toast.makeText(app, saved ? R.string.save_file_success
						: R.string.save_file_error, Toast.LENGTH_LONG).show();
				SaveCleanCopyActivity a = screen.get();
				if (a != null) a.finish();
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

	private void failed() {
		Toast.makeText(getApplicationContext(), R.string.save_file_error,
				Toast.LENGTH_LONG).show();
		finish();
	}
}
