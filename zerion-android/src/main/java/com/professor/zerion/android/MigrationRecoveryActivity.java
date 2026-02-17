package com.professor.zerion.android;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.briarproject.bramble.api.account.AccountManager;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import com.professor.zerion.BuildConfig;
import com.professor.zerion.R;
import com.professor.zerion.android.account.SetupActivity;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.BaseActivity;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.core.content.FileProvider;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class MigrationRecoveryActivity extends BaseActivity {

	@Inject
	AccountManager accountManager;
	@Inject
	@IoExecutor
	Executor ioExecutor;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);

		getWindow().setFlags(
				WindowManager.LayoutParams.FLAG_SECURE,
				WindowManager.LayoutParams.FLAG_SECURE
		);

		setContentView(R.layout.activity_migration_recovery);

		findViewById(R.id.retryButton).setOnClickListener(v -> retryMigration());
		findViewById(R.id.exportButton).setOnClickListener(v -> exportDiagnostics());
		findViewById(R.id.startFreshButton).setOnClickListener(v -> confirmStartFresh());
	}

	private void retryMigration() {
		// Restart the app process so LifecycleManager is re-created
		// and migration is attempted again from scratch
		Intent restart = getPackageManager()
				.getLaunchIntentForPackage(getPackageName());
		if (restart != null) {
			restart.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);
			startActivity(restart);
		}
		Runtime.getRuntime().exit(0);
	}

	private void exportDiagnostics() {
		ioExecutor.execute(() -> {
			File diagnosticsFile = createDiagnosticsFile();
			if (diagnosticsFile == null) return;

			runOnUiThread(() -> {
				try {
					android.net.Uri uri = FileProvider.getUriForFile(
							this,
							getPackageName() + ".provider",
							diagnosticsFile);
					Intent share = new Intent(Intent.ACTION_SEND);
					share.setType("text/plain");
					share.putExtra(Intent.EXTRA_STREAM, uri);
					share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
					startActivity(Intent.createChooser(share,
							getString(R.string.migration_export)));
				} catch (Exception e) {
					// If FileProvider fails, share as text instead
					try {
						StringBuilder sb = new StringBuilder();
						java.io.BufferedReader reader =
								new java.io.BufferedReader(
										new java.io.FileReader(diagnosticsFile));
						String line;
						while ((line = reader.readLine()) != null) {
							sb.append(line).append('\n');
						}
						reader.close();
						Intent share = new Intent(Intent.ACTION_SEND);
						share.setType("text/plain");
						share.putExtra(Intent.EXTRA_TEXT, sb.toString());
						startActivity(Intent.createChooser(share,
								getString(R.string.migration_export)));
					} catch (Exception ignored) {
					}
				}
			});
		});
	}

	@Nullable
	private File createDiagnosticsFile() {
		try {
			File cacheDir = getCacheDir();
			File diagnosticsFile = new File(cacheDir,
					"zerion-migration-diagnostics.txt");
			FileWriter writer = new FileWriter(diagnosticsFile);

			writer.write("Zerion Migration Diagnostics\n");
			writer.write("============================\n\n");
			writer.write("App version: " + BuildConfig.VERSION_NAME
					+ " (" + BuildConfig.VERSION_CODE + ")\n");
			writer.write("Device: " + Build.MANUFACTURER + " "
					+ Build.MODEL + "\n");
			writer.write("Android: " + Build.VERSION.RELEASE
					+ " (SDK " + Build.VERSION.SDK_INT + ")\n\n");

			// List files in database directory (names and sizes only)
			File dbDir = getDir("db", MODE_PRIVATE);
			writer.write("Database directory: "
					+ (dbDir.exists() ? "exists" : "missing") + "\n");
			if (dbDir.exists()) {
				File[] files = dbDir.listFiles();
				if (files != null && files.length > 0) {
					writer.write("Files:\n");
					for (File f : files) {
						writer.write("  " + f.getName()
								+ " (" + f.length() + " bytes)\n");
					}
				} else {
					writer.write("Files: (empty)\n");
				}
			}

			writer.write("\n");

			// List files in key directory (names only, not contents)
			File keyDir = getDir("key", MODE_PRIVATE);
			writer.write("Key directory: "
					+ (keyDir.exists() ? "exists" : "missing") + "\n");
			if (keyDir.exists()) {
				File[] files = keyDir.listFiles();
				if (files != null && files.length > 0) {
					writer.write("Files:\n");
					for (File f : files) {
						writer.write("  " + f.getName()
								+ " (" + f.length() + " bytes)\n");
					}
				} else {
					writer.write("Files: (empty)\n");
				}
			}

			// Include migration error if available
			File errorFile = new File(dbDir, "migration-error.txt");
			if (errorFile.exists()) {
				writer.write("\nMigration error:\n");
				java.io.BufferedReader errReader =
						new java.io.BufferedReader(
								new java.io.FileReader(errorFile));
				String errLine;
				while ((errLine = errReader.readLine()) != null) {
					writer.write("  " + errLine + "\n");
				}
				errReader.close();
			}

			writer.write("\n");
			writer.write("NOTE: This file contains no passwords, keys,\n");
			writer.write("or personal data. It is safe to share.\n");

			writer.close();
			return diagnosticsFile;
		} catch (IOException e) {
			return null;
		}
	}

	private void confirmStartFresh() {
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.migration_delete_title)
				.setMessage(R.string.migration_delete_message)
				.setPositiveButton(R.string.migration_delete_confirm,
						(dialog, which) -> startFresh())
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void startFresh() {
		ioExecutor.execute(() -> {
			accountManager.deleteAccount();
			runOnUiThread(() -> {
				Intent i = new Intent(this, SetupActivity.class);
				i.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);
				startActivity(i);
				finish();
			});
		});
	}
}
