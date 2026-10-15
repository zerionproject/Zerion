package com.professor.zerion.android.settings;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.professor.zerion.R;
import com.professor.zerion.android.backup.AccountBackupManager;
import com.professor.zerion.android.backup.BackupException;
import com.professor.zerion.android.util.ActivityLaunchers.CreateDocumentAdvanced;
import com.professor.zerion.android.util.ActivityLaunchers.OpenDocumentAdvanced;

import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.io.IOException;
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

	static final String EXPORT_FILE_NAME = "backup.bin";

	@Inject
	@IoExecutor
	Executor ioExecutor;

	@Inject
	AccountBackupManager backupManager;

	@Inject
	org.zerionproject.core.api.crypto.PasswordStrengthEstimator
			strengthEstimator;

	@Inject
	AccountManager accountManager;

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
				withAccountPassword(() ->
						exportLauncher.launch(EXPORT_FILE_NAME)));
		view.findViewById(R.id.import_card).setOnClickListener(v ->
				withAccountPassword(() ->
						importLauncher.launch(new String[] {"*/*"})));
		view.findViewById(R.id.transfer_send_card).setOnClickListener(v ->
				withAccountPassword(() ->
						navigate(new TransferSendFragment())));
		view.findViewById(R.id.transfer_receive_card).setOnClickListener(v ->
				withAccountPassword(() ->
						navigate(new TransferReceiveFragment())));
	}

	private void withAccountPassword(Runnable action) {
		if (!accountManager.hasDatabaseKey()) {
			action.run();
			return;
		}
		AccountPasswordGate.prompt(requireContext(), accountManager,
				ioExecutor, mainHandler,
				R.string.settings_password_required_title,
				R.string.backup_password_gate_message,
				() -> {
					if (isAdded()) action.run();
				}, () -> {
				});
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
		uri = com.professor.zerion.android.util.PickedUris.accept(
				requireContext(), uri);
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
		androidx.appcompat.app.AlertDialog dlg =
				new SecureAlertDialogBuilder(context)
						.setTitle(R.string.backup_export_title)
						.setMessage(R.string.backup_export_warning)
						.setView(layout)
						.setPositiveButton(R.string.ok, null)
						.setNegativeButton(R.string.cancel, null)
						.create();
		dlg.setOnShowListener(dd -> dlg.getButton(
				androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
				.setOnClickListener(x -> {
						char[] p1 = peekChars(pass);
						char[] p2 = peekChars(confirm);
						try {
							if (p1.length < 1) {
								toast(R.string.backup_password_empty);
							} else if (!com.professor.zerion.android.backup
									.BackupPassphrase.longEnough(p1)) {
								toast(R.string.backup_password_too_short);
							} else if (!com.professor.zerion.android.login
									.AccountPasswordPolicy
									.sameNormalForm(p1, p2)) {
								toast(R.string.backup_passwords_mismatch);
							} else {
								pass.getText().clear();
								confirm.getText().clear();
								runExport(dest, p1.clone());
								dlg.dismiss();
							}
						} finally {
							Arrays.fill(p1, '\0');
							Arrays.fill(p2, '\0');
						}
				}));
		dlg.show();
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
		androidx.appcompat.app.AlertDialog dlg =
				new SecureAlertDialogBuilder(context)
						.setTitle(R.string.backup_import_title)
						.setMessage(R.string.backup_import_warning)
						.setView(layout)
						.setPositiveButton(R.string.ok, null)
						.setNegativeButton(R.string.cancel, null)
						.create();
		dlg.setOnShowListener(dd -> dlg.getButton(
				androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
				.setOnClickListener(x -> {
						char[] p = peekChars(pass);
						char[] np1 = peekChars(newPass);
						char[] np2 = peekChars(confirm);
						try {
							if (p.length < 1 || np1.length < 1) {
								toast(R.string.backup_password_empty);
							} else if (!com.professor.zerion.android.login
									.AccountPasswordPolicy
									.acceptable(strengthEstimator, np1)) {
								toast(R.string.password_too_weak);
							} else if (!com.professor.zerion.android.login
									.AccountPasswordPolicy
									.sameNormalForm(np1, np2)) {
								toast(R.string.backup_passwords_mismatch);
							} else {
								pass.getText().clear();
								newPass.getText().clear();
								confirm.getText().clear();
								runImport(src, p.clone(), np1.clone());
								dlg.dismiss();
							}
						} finally {
							Arrays.fill(p, '\0');
							Arrays.fill(np1, '\0');
							Arrays.fill(np2, '\0');
						}
				}));
		dlg.show();
	}

	private void runExport(Uri dest, char[] passphrase) {
		Context appContext = requireContext().getApplicationContext();
		toast(R.string.backup_export_in_progress);
		ioExecutor.execute(() -> {
			int failMessage;
			try {
				failMessage = export(backupManager, appContext, dest,
						passphrase);
			} finally {
				Arrays.fill(passphrase, '\0');
			}
			boolean success = failMessage == 0;
			int message = failMessage;
			mainHandler.post(() -> onExportFinished(success, message));
		});
	}

	static int export(AccountBackupManager backupManager, Context context,
			Uri dest, char[] passphrase) {
		boolean ok = false;
		boolean[] opened = {false};
		int failMessage = R.string.backup_export_failed;
		try {
			backupManager.exportAccount(passphrase,
					new AccountBackupManager.Destination() {
						@Override
						public OutputStream open() throws IOException {
							opened[0] = true;
							OutputStream os = context.getContentResolver()
									.openOutputStream(dest, "wt");
							if (os == null) {
								throw new IOException("null output stream");
							}
							return os;
						}

						@Override
						public void discard() {
							discardIncompleteExport(context, dest, opened[0]);
						}
					});
			ok = true;
		} catch (BackupException e) {
			failMessage = exportFailureMessage(e.reason);
		} catch (Throwable e) {
			failMessage = R.string.backup_export_failed;
		} finally {
			if (!ok) discardIncompleteExport(context, dest, opened[0]);
		}
		return ok ? 0 : failMessage;
	}

	static void discardIncompleteExport(Context context, Uri dest,
			boolean opened) {
		if (!opened && !isEmptyDocument(context, dest)) return;
		try {
			android.provider.DocumentsContract.deleteDocument(
					context.getContentResolver(), dest);
		} catch (Exception ignored) {
		}
	}

	private static boolean isEmptyDocument(Context context, Uri dest) {
		try (Cursor c = context.getContentResolver().query(dest,
				new String[] {OpenableColumns.SIZE}, null, null, null)) {
			return c != null && c.moveToFirst() && !c.isNull(0)
					&& c.getLong(0) == 0;
		} catch (Exception e) {
			return false;
		}
	}

	private static int exportFailureMessage(BackupException.Reason reason) {
		if (reason == BackupException.Reason.NOT_SIGNED_IN) {
			return R.string.backup_export_not_signed_in;
		}
		if (reason == BackupException.Reason.TOO_LARGE) {
			return R.string.backup_export_too_large;
		}
		return R.string.backup_export_failed;
	}

	private void onExportFinished(boolean success, int failMessage) {
		if (!isAdded()) {
			toast(success ? R.string.backup_export_success : failMessage);
			return;
		}
		if (success) {
			new SecureAlertDialogBuilder(requireContext())
					.setTitle(R.string.backup_export_success_title)
					.setMessage(R.string.backup_export_warning_after)
					.setCancelable(false)
					.setPositiveButton(R.string.ok, null)
					.show();
		} else {
			toast(failMessage);
		}
	}

	private void runImport(Uri src, char[] passphrase, char[] newPassword) {
		toast(R.string.backup_import_in_progress);
		ioExecutor.execute(() -> {
			boolean ok = false;
			int failMessage = R.string.backup_import_failed;
			try {
				backupManager.importBackupFile(src, passphrase, newPassword);
				ok = true;
			} catch (BackupException e) {
				failMessage = importFailureMessage(e.reason);
			} catch (Throwable e) {
				failMessage = R.string.backup_import_failed;
			} finally {
				Arrays.fill(passphrase, '\0');
				Arrays.fill(newPassword, '\0');
			}
			boolean success = ok;
			int message = failMessage;
			mainHandler.post(() -> onImportFinished(success, message));
		});
	}

	private int importFailureMessage(BackupException.Reason reason) {
		switch (reason) {
			case WRONG_PASSPHRASE:
				return R.string.backup_import_wrong_passphrase;
			case NOT_A_BACKUP:
				return R.string.backup_import_not_a_backup;
			case UNSUPPORTED_VERSION:
				return R.string.backup_import_unsupported;
			case CORRUPT:
				return R.string.backup_import_corrupt;
			case IO_ERROR:
				return R.string.backup_import_io_error;
			case TOO_LARGE:
				return R.string.backup_import_too_large;
			default:
				return R.string.backup_import_failed;
		}
	}

	private void onImportFinished(boolean success, int failMessage) {
		if (!isAdded()) {
			toast(success ? R.string.backup_import_success : failMessage);
			return;
		}
		if (success) {
			new SecureAlertDialogBuilder(requireContext())
					.setTitle(R.string.backup_import_success_title)
					.setMessage(R.string.backup_import_success_message)
					.setPositiveButton(R.string.ok, null)
					.show();
		} else {
			toast(failMessage);
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

	private char[] chars(EditText field) {
		android.text.Editable e = field.getText();
		char[] out = new char[e.length()];
		e.getChars(0, e.length(), out, 0);
		e.clear();
		return out;
	}

	private char[] peekChars(EditText field) {
		android.text.Editable e = field.getText();
		char[] out = new char[e.length()];
		e.getChars(0, e.length(), out, 0);
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
