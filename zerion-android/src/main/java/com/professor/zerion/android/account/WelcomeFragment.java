package com.professor.zerion.android.account;

import android.content.Context;
import android.content.Intent;
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
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.professor.zerion.R;
import com.professor.zerion.android.backup.AccountBackupManager;
import com.professor.zerion.android.backup.BackupException;
import com.professor.zerion.android.login.StartupActivity;
import com.professor.zerion.android.settings.TransferReceiveFragment;
import com.professor.zerion.android.util.ActivityLaunchers.OpenDocumentAdvanced;

import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.professor.zerion.android.AppModule.getAndroidComponent;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class WelcomeFragment extends Fragment {

	private static final int MAX_BACKUP_BYTES = 512 * 1024 * 1024;

	@Inject
	@IoExecutor
	Executor ioExecutor;

	@Inject
	AccountBackupManager backupManager;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	@Nullable
	private TextView statusText;

	private final ActivityResultLauncher<String[]> importLauncher =
			registerForActivityResult(new OpenDocumentAdvanced(),
					this::onImportFileChosen);

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
		return inflater.inflate(R.layout.fragment_welcome, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view,
			@Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		statusText = view.findViewById(R.id.welcome_status);
		view.findViewById(R.id.create_button).setOnClickListener(v ->
				startCreate());
		view.findViewById(R.id.import_button).setOnClickListener(v ->
				importLauncher.launch(new String[] {"*/*"}));
		view.findViewById(R.id.receive_button).setOnClickListener(v ->
				requireActivity().getSupportFragmentManager().beginTransaction()
						.replace(R.id.fragmentContainer,
								TransferReceiveFragment.newInstance(true))
						.addToBackStack(null)
						.commit());
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		statusText = null;
	}

	private void startCreate() {
		Intent i = new Intent(requireContext(), SetupActivity.class);
		i.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK
				| FLAG_ACTIVITY_CLEAR_TOP);
		startActivity(i);
		requireActivity().finish();
	}

	private void onImportFileChosen(@Nullable Uri uri) {
		if (uri != null) showImportPasswordDialog(uri);
	}

	private void showImportPasswordDialog(Uri src) {
		Context context = requireContext();
		LinearLayout layout = new LinearLayout(context);
		layout.setOrientation(LinearLayout.VERTICAL);
		int pad = (int) (16 * getResources().getDisplayMetrics().density);
		layout.setPadding(pad, pad, pad, 0);
		EditText pass = passwordField(context, R.string.backup_password_hint);
		EditText newPass =
				passwordField(context, R.string.backup_new_password_hint);
		EditText confirm = passwordField(context,
				R.string.backup_new_password_confirm_hint);
		layout.addView(pass);
		layout.addView(newPass);
		layout.addView(confirm);
		new MaterialAlertDialogBuilder(context)
				.setTitle(R.string.welcome_import_file)
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

	private void runImport(Uri src, char[] passphrase, char[] newPassword) {
		Context appContext = requireContext().getApplicationContext();
		setStatus(getString(R.string.backup_import_in_progress));
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
		if (!isAdded()) return;
		if (success) {
			goToSignIn();
		} else {
			setStatus("");
			toast(R.string.backup_import_failed);
		}
	}

	private void goToSignIn() {
		Intent i = new Intent(requireContext(), StartupActivity.class);
		i.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK
				| FLAG_ACTIVITY_CLEAR_TOP);
		startActivity(i);
		requireActivity().finish();
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

	private char[] chars(EditText field) {
		android.text.Editable e = field.getText();
		char[] out = new char[e.length()];
		e.getChars(0, e.length(), out, 0);
		e.clear();
		return out;
	}

	private void setStatus(String s) {
		if (statusText != null) statusText.setText(s);
	}

	private void toast(int resId) {
		Context c = getContext();
		if (c != null) {
			Toast.makeText(c.getApplicationContext(), resId,
					Toast.LENGTH_LONG).show();
		}
	}
}
