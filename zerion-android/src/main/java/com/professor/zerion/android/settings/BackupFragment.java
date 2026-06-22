package com.professor.zerion.android.settings;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.professor.zerion.R;
import com.professor.zerion.android.backup.AccountBackupManager;
import com.professor.zerion.android.backup.BackupException;
import com.professor.zerion.android.util.ActivityLaunchers.CreateDocumentAdvanced;
import com.professor.zerion.android.util.ActivityLaunchers.OpenDocumentAdvanced;

import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import static com.professor.zerion.android.AppModule.getAndroidComponent;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class BackupFragment extends Fragment {

	private static final int MAX_BACKUP_BYTES = 512 * 1024 * 1024;

	@Inject
	@IoExecutor
	Executor ioExecutor;

	@Inject
	AccountBackupManager backupManager;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	private final ActivityResultLauncher<String> exportLauncher =
			registerForActivityResult(new CreateDocumentAdvanced(),
					this::onExportDestinationChosen);
	private final ActivityResultLauncher<String[]> importLauncher =
			registerForActivityResult(new OpenDocumentAdvanced(),
					this::onImportSourceChosen);

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		getAndroidComponent(context).inject(this);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_settings_backup, container,
				false);
	}

	@Override
	public void onViewCreated(@NonNull View view,
			@Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		view.findViewById(R.id.export_card).setOnClickListener(v ->
				exportLauncher.launch("zerion-backup.zbk"));
		view.findViewById(R.id.import_card).setOnClickListener(v ->
				importLauncher.launch(new String[] {"*/*"}));
		view.findViewById(R.id.transfer_send_card).setOnClickListener(v ->
				navigate(new TransferSendFragment()));
		view.findViewById(R.id.transfer_receive_card).setOnClickListener(v ->
				navigate(new TransferReceiveFragment()));
	}

	private void navigate(androidx.fragment.app.Fragment fragment) {
		requireActivity().getSupportFragmentManager().beginTransaction()
				.replace(R.id.fragmentContainer, fragment)
				.addToBackStack(null)
				.commit();
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.backup_settings_title);
	}

	private void onExportDestinationChosen(@Nullable Uri uri) {
		if (uri != null) showExportPasswordDialog(uri);
	}

	private void onImportSourceChosen(@Nullable Uri uri) {
		if (uri != null) showImportPasswordDialog(uri);
	}

	private void showExportPasswordDialog(Uri dest) {
		Context context = requireContext();
		LinearLayout layout = dialogLayout(context);
		EditText pass = passwordField(context, R.string.backup_password_hint);
		EditText confirm =
				passwordField(context, R.string.backup_password_confirm_hint);
		layout.addView(pass);
		layout.addView(confirm);
		new MaterialAlertDialogBuilder(context)
				.setTitle(R.string.backup_export_title)
				.setMessage(R.string.backup_export_warning)
				.setView(layout)
				.setPositiveButton(R.string.ok, (d, w) -> {
					char[] p1 = chars(pass);
					char[] p2 = chars(confirm);
					try {
						if (p1.length < 1) {
							toast(R.string.backup_password_empty);
						} else if (!Arrays.equals(p1, p2)) {
							toast(R.string.backup_passwords_mismatch);
						} else {
							runExport(dest, p1.clone());
						}
					} finally {
						Arrays.fill(p1, '\0');
						Arrays.fill(p2, '\0');
					}
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void showImportPasswordDialog(Uri src) {
		Context context = requireContext();
		LinearLayout layout = dialogLayout(context);
		EditText pass = passwordField(context, R.string.backup_password_hint);
		EditText newPass =
				passwordField(context, R.string.backup_new_password_hint);
		EditText confirm = passwordField(context,
				R.string.backup_new_password_confirm_hint);
		layout.addView(pass);
		layout.addView(newPass);
		layout.addView(confirm);
		new MaterialAlertDialogBuilder(context)
				.setTitle(R.string.backup_import_title)
				.setMessage(R.string.backup_import_warning)
				.setView(layout)
				.setPositiveButton(R.string.ok, (d, w) -> {
					char[] p = chars(pass);
					char[] np1 = chars(newPass);
					char[] np2 = chars(confirm);
					try {
						if (p.length < 1 || np1.length < 1) {
							toast(R.string.backup_password_empty);
						} else if (!Arrays.equals(np1, np2)) {
							toast(R.string.backup_passwords_mismatch);
						} else {
							runImport(src, p.clone(), np1.clone());
						}
					} finally {
						Arrays.fill(p, '\0');
						Arrays.fill(np1, '\0');
						Arrays.fill(np2, '\0');
					}
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void runExport(Uri dest, char[] passphrase) {
		Context appContext = requireContext().getApplicationContext();
		toast(R.string.backup_export_in_progress);
		ioExecutor.execute(() -> {
			boolean ok = false;
			byte[] data = null;
			try {
				data = backupManager.exportAccount(passphrase);
				try (OutputStream os = appContext.getContentResolver()
						.openOutputStream(dest, "wt")) {
					if (os == null) throw new IOException("null output stream");
					os.write(data);
					os.flush();
				}
				ok = true;
			} catch (BackupException | IOException | RuntimeException e) {
				ok = false;
			} finally {
				if (data != null) Arrays.fill(data, (byte) 0);
				Arrays.fill(passphrase, '\0');
			}
			toast(ok ? R.string.backup_export_success
					: R.string.backup_export_failed);
		});
	}

	private void runImport(Uri src, char[] passphrase, char[] newPassword) {
		Context appContext = requireContext().getApplicationContext();
		toast(R.string.backup_import_in_progress);
		ioExecutor.execute(() -> {
			boolean ok = false;
			byte[] data = null;
			try {
				try (InputStream is =
						appContext.getContentResolver().openInputStream(src)) {
					if (is == null) throw new IOException("null input stream");
					data = readAll(is);
				}
				backupManager.importAccount(data, passphrase, newPassword);
				ok = true;
			} catch (BackupException | IOException | RuntimeException e) {
				ok = false;
			} finally {
				if (data != null) Arrays.fill(data, (byte) 0);
				Arrays.fill(passphrase, '\0');
				Arrays.fill(newPassword, '\0');
			}
			boolean success = ok;
			mainHandler.post(() -> onImportFinished(success));
		});
	}

	private void onImportFinished(boolean success) {
		if (!isAdded()) {
			toast(success ? R.string.backup_import_success
					: R.string.backup_import_failed);
			return;
		}
		if (success) {
			new MaterialAlertDialogBuilder(requireContext())
					.setTitle(R.string.backup_import_success_title)
					.setMessage(R.string.backup_import_success_message)
					.setPositiveButton(R.string.ok, null)
					.show();
		} else {
			toast(R.string.backup_import_failed);
		}
	}

	private EditText passwordField(Context context, int hintRes) {
		EditText field = new EditText(context);
		field.setInputType(InputType.TYPE_CLASS_TEXT
				| InputType.TYPE_TEXT_VARIATION_PASSWORD);
		field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
		field.setPrivateImeOptions("nm");
		field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
		field.setHint(hintRes);
		return field;
	}

	private LinearLayout dialogLayout(Context context) {
		LinearLayout layout = new LinearLayout(context);
		layout.setOrientation(LinearLayout.VERTICAL);
		int pad = (int) (16 * getResources().getDisplayMetrics().density);
		layout.setPadding(pad, pad, pad, 0);
		return layout;
	}

	private byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int total = 0;
		int n;
		while ((n = in.read(buf)) != -1) {
			total += n;
			if (total > MAX_BACKUP_BYTES) {
				throw new IOException("Backup file too large");
			}
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}

	private char[] chars(EditText field) {
		android.text.Editable e = field.getText();
		char[] out = new char[e.length()];
		e.getChars(0, e.length(), out, 0);
		e.clear();
		return out;
	}

	private void toast(int resId) {
		Context context = getContext();
		if (context == null) return;
		Context appContext = context.getApplicationContext();
		mainHandler.post(() ->
				Toast.makeText(appContext, resId, Toast.LENGTH_LONG).show());
	}
}
