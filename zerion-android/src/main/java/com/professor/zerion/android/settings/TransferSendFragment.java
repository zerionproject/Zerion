package com.professor.zerion.android.settings;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.professor.zerion.R;
import com.professor.zerion.android.backup.AccountTransferManager;
import com.professor.zerion.android.backup.AccountTransferManager.Callback;
import com.professor.zerion.android.backup.AccountTransferManager.Status;
import com.professor.zerion.android.backup.TransferException;

import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import static android.app.Activity.RESULT_OK;
import static com.professor.zerion.android.AppModule.getAndroidComponent;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class TransferSendFragment extends Fragment implements Callback {

	@Inject
	@IoExecutor
	Executor ioExecutor;

	@Inject
	AccountTransferManager transferManager;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final BlockingQueue<Boolean> sasResult = new ArrayBlockingQueue<>(1);

	@Nullable
	private TextView statusText;
	private boolean started = false;

	private final ActivityResultLauncher<Intent> scanLauncher =
			registerForActivityResult(new StartActivityForResult(),
					this::onScanResult);

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
		return inflater.inflate(R.layout.fragment_transfer_send, container,
				false);
	}

	@Override
	public void onViewCreated(@NonNull View view,
			@Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		statusText = view.findViewById(R.id.transfer_status);
		if (!started) {
			started = true;
			scanLauncher.launch(new Intent(requireContext(),
					TransferQrScannerActivity.class));
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.transfer_send_title);
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		statusText = null;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		transferManager.cancel();
		sasResult.offer(false);
	}

	private void onScanResult(ActivityResult result) {
		if (result.getResultCode() == RESULT_OK && result.getData() != null) {
			String payload = result.getData().getStringExtra(
					TransferQrScannerActivity.EXTRA_SCANNED_LINK);
			if (payload != null && !payload.isEmpty()) {
				start(payload);
				return;
			}
		}
		back();
	}

	private void start(String payload) {
		ioExecutor.execute(() -> {
			boolean ok = false;
			try {
				transferManager.send(payload, this);
				ok = true;
			} catch (TransferException | RuntimeException e) {
				ok = false;
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
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(success ? R.string.transfer_done_title
						: R.string.transfer_failed_title)
				.setMessage(success ? R.string.transfer_send_done_message
						: R.string.transfer_failed_message)
				.setCancelable(false)
				.setPositiveButton(R.string.ok, (d, w) -> back())
				.show();
	}

	private String statusFor(Status status) {
		switch (status) {
			case CONNECTING:
				return getString(R.string.transfer_status_connecting);
			case AUTHENTICATING:
				return getString(R.string.transfer_status_authenticating);
			case TRANSFERRING:
				return getString(R.string.transfer_status_transferring);
			case DONE:
				return getString(R.string.transfer_status_done);
			default:
				return getString(R.string.transfer_status_working);
		}
	}

	private void setStatus(String s) {
		if (statusText != null) statusText.setText(s);
	}

	private void back() {
		if (isAdded()) {
			requireActivity().getSupportFragmentManager().popBackStack();
		}
	}
}
