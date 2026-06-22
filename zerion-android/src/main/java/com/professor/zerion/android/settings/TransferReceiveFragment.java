package com.professor.zerion.android.settings;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.professor.zerion.R;
import com.professor.zerion.android.backup.AccountTransferManager;
import com.professor.zerion.android.backup.AccountTransferManager.Callback;
import com.professor.zerion.android.backup.AccountTransferManager.Status;
import com.professor.zerion.android.backup.TransferException;
import com.professor.zerion.android.contact.add.remote.QrCodeUtils;

import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import static com.professor.zerion.android.AppModule.getAndroidComponent;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class TransferReceiveFragment extends Fragment implements Callback {

	@Inject
	@IoExecutor
	Executor ioExecutor;

	@Inject
	AccountTransferManager transferManager;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final BlockingQueue<Boolean> sasResult = new ArrayBlockingQueue<>(1);

	@Nullable
	private TextView statusText;
	@Nullable
	private ImageView qrImage;

	public static TransferReceiveFragment newInstance(boolean firstRun) {
		TransferReceiveFragment f = new TransferReceiveFragment();
		Bundle args = new Bundle();
		args.putBoolean("firstRun", firstRun);
		f.setArguments(args);
		return f;
	}

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
		return inflater.inflate(R.layout.fragment_transfer_receive, container,
				false);
	}

	@Override
	public void onViewCreated(@NonNull View view,
			@Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		statusText = view.findViewById(R.id.transfer_status);
		qrImage = view.findViewById(R.id.transfer_qr);
		promptPasswordAndStart();
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.transfer_receive_title);
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		statusText = null;
		qrImage = null;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		transferManager.cancel();
		sasResult.offer(false);
	}

	private void promptPasswordAndStart() {
		Context context = requireContext();
		LinearLayout layout = new LinearLayout(context);
		layout.setOrientation(LinearLayout.VERTICAL);
		int pad = (int) (16 * getResources().getDisplayMetrics().density);
		layout.setPadding(pad, pad, pad, 0);
		EditText pass =
				passwordField(context, R.string.backup_new_password_hint);
		EditText confirm = passwordField(context,
				R.string.backup_new_password_confirm_hint);
		layout.addView(pass);
		layout.addView(confirm);
		new MaterialAlertDialogBuilder(context)
				.setTitle(R.string.transfer_receive_title)
				.setMessage(R.string.transfer_receive_password_message)
				.setView(layout)
				.setCancelable(false)
				.setPositiveButton(R.string.ok, (d, w) -> {
					char[] p1 = chars(pass);
					char[] p2 = chars(confirm);
					try {
						if (p1.length < 1) {
							toast(R.string.backup_password_empty);
							back();
						} else if (!Arrays.equals(p1, p2)) {
							toast(R.string.backup_passwords_mismatch);
							back();
						} else {
							start(p1.clone());
						}
					} finally {
						Arrays.fill(p1, '\0');
						Arrays.fill(p2, '\0');
					}
				})
				.setNegativeButton(R.string.cancel, (d, w) -> back())
				.show();
	}

	private void start(char[] newPassword) {
		ioExecutor.execute(() -> {
			boolean ok = false;
			try {
				transferManager.receive(newPassword, this);
				ok = true;
			} catch (TransferException | RuntimeException e) {
				ok = false;
			} finally {
				Arrays.fill(newPassword, '\0');
			}
			boolean success = ok;
			mainHandler.post(() -> finishResult(success));
		});
	}

	@Override
	public void onStatus(Status status) {
		mainHandler.post(() -> setStatus(statusFor(status)));
	}

	@Override
	public void onPairingReady(String qrPayload) {
		mainHandler.post(() -> showQr(qrPayload));
	}

	@Override
	public boolean onSasConfirm(String safetyNumber) {
		sasResult.clear();
		mainHandler.post(() -> showSasDialog(safetyNumber));
		try {
			Boolean r = sasResult.poll(5, TimeUnit.MINUTES);
			return r != null && r;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private void showQr(String payload) {
		if (!isAdded() || qrImage == null) return;
		Bitmap bmp = QrCodeUtils.generateQrCode(payload);
		if (bmp != null) qrImage.setImageBitmap(bmp);
		setStatus(getString(R.string.transfer_receive_show_qr));
	}

	private void showSasDialog(String sas) {
		if (!isAdded()) {
			sasResult.offer(false);
			return;
		}
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.transfer_sas_title)
				.setMessage(getString(R.string.transfer_sas_message, sas))
				.setCancelable(false)
				.setPositiveButton(R.string.transfer_sas_matches,
						(d, w) -> sasResult.offer(true))
				.setNegativeButton(R.string.cancel,
						(d, w) -> sasResult.offer(false))
				.show();
	}

	private void finishResult(boolean success) {
		if (!isAdded()) return;
		boolean firstRun = getArguments() != null
				&& getArguments().getBoolean("firstRun", false);
		if (success && firstRun) {
			android.content.Intent i = new android.content.Intent(
					requireContext(),
					com.professor.zerion.android.login.StartupActivity.class);
			i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
					| android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
					| android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
			startActivity(i);
			requireActivity().finish();
			return;
		}
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(success ? R.string.transfer_done_title
						: R.string.transfer_failed_title)
				.setMessage(success ? R.string.transfer_receive_done_message
						: R.string.transfer_failed_message)
				.setCancelable(false)
				.setPositiveButton(R.string.ok, (d, w) -> back())
				.show();
	}

	private String statusFor(Status status) {
		switch (status) {
			case PUBLISHING:
				return getString(R.string.transfer_status_publishing);
			case WAITING_FOR_PEER:
				return getString(R.string.transfer_receive_show_qr);
			case AUTHENTICATING:
				return getString(R.string.transfer_status_authenticating);
			case IMPORTING:
				return getString(R.string.transfer_status_importing);
			case DONE:
				return getString(R.string.transfer_status_done);
			default:
				return getString(R.string.transfer_status_working);
		}
	}

	private void setStatus(String s) {
		if (statusText != null) statusText.setText(s);
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

	private void back() {
		if (isAdded()) {
			requireActivity().getSupportFragmentManager().popBackStack();
		}
	}

	private void toast(int resId) {
		Context c = getContext();
		if (c != null) {
			Toast.makeText(c.getApplicationContext(), resId,
					Toast.LENGTH_LONG).show();
		}
	}
}
