package com.professor.zerion.android.contact.add.remote;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.common.util.concurrent.ListenableFuture;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class QrExchangeFragment extends BaseFragment {

	private static final String TAG = QrExchangeFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private AddContactViewModel viewModel;

	private PreviewView previewView;
	private ImageView qrCodeImage;
	private TextView statusText;
	private TextView qrHintText;
	private MaterialButton continueButton;

	private ExecutorService cameraExecutor;
	private final AtomicBoolean scanComplete = new AtomicBoolean(false);

	private final ActivityResultLauncher<String> requestCameraLauncher =
			registerForActivityResult(
					new ActivityResultContracts.RequestPermission(),
					granted -> {
						if (granted) {
							startCamera();
						} else {
							Toast.makeText(requireContext(),
									R.string.camera_permission_denied,
									LENGTH_LONG).show();
						}
					});

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(AddContactViewModel.class);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		if (getActivity() == null || getContext() == null) return null;

		View v = inflater.inflate(R.layout.fragment_qr_exchange,
				container, false);

		previewView = v.findViewById(R.id.previewView);
		qrCodeImage = v.findViewById(R.id.qrCodeImage);
		statusText = v.findViewById(R.id.statusText);
		qrHintText = v.findViewById(R.id.qrHintText);
		continueButton = v.findViewById(R.id.continueButton);

		cameraExecutor = Executors.newSingleThreadExecutor();

		viewModel.getHandshakeLink().observe(getViewLifecycleOwner(), link -> {
			if (link != null) {
				Bitmap qr = QrCodeUtils.generateQrCode(link);
				if (qr != null) {
					qrCodeImage.setImageBitmap(qr);
				}
			}
		});

		continueButton.setOnClickListener(btn -> {
			viewModel.onRemoteLinkEntered();
		});

		if (ContextCompat.checkSelfPermission(requireContext(),
				Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
			startCamera();
		} else {
			requestCameraLauncher.launch(Manifest.permission.CAMERA);
		}

		return v;
	}

	@SuppressWarnings({"RestrictedApi", "UnsafeOptInUsageError"})
	private void startCamera() {
		if (getContext() == null) return;

		ListenableFuture<ProcessCameraProvider> future =
				ProcessCameraProvider.getInstance(requireContext());

		future.addListener(() -> {
			try {
				ProcessCameraProvider provider = future.get();

				Preview preview = new Preview.Builder().build();
				preview.setSurfaceProvider(previewView.getSurfaceProvider());

				ImageAnalysis analysis = new ImageAnalysis.Builder()
						.setBackpressureStrategy(
								ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
						.build();

				analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

				provider.unbindAll();
				provider.bindToLifecycle(getViewLifecycleOwner(),
						CameraSelector.DEFAULT_BACK_CAMERA,
						preview, analysis);

			} catch (Exception e) {
				if (getContext() != null) {
					Toast.makeText(requireContext(),
							R.string.camera_error, LENGTH_LONG).show();
				}
			}
		}, ContextCompat.getMainExecutor(requireContext()));
	}

	private void analyzeFrame(ImageProxy imageProxy) {
		if (scanComplete.get()) {
			imageProxy.close();
			return;
		}
		try {
			byte[] yPlane = extractYPlane(imageProxy);
			if (yPlane != null) {
				int rotation = imageProxy.getImageInfo().getRotationDegrees();
				String decoded = QrCodeUtils.decodeQrFromYuv(
						yPlane, imageProxy.getWidth(), imageProxy.getHeight(),
						0, 0, rotation);
				if (decoded != null
						&& viewModel.isValidRemoteContactLink(decoded)) {
					onLinkScanned(decoded);
				}
			}
		} finally {
			imageProxy.close();
		}
	}

	@Nullable
	private static byte[] extractYPlane(ImageProxy proxy) {
		ImageProxy.PlaneProxy[] planes = proxy.getPlanes();
		if (planes.length == 0) return null;
		ImageProxy.PlaneProxy y = planes[0];
		ByteBuffer buf = y.getBuffer();
		int width = proxy.getWidth();
		int height = proxy.getHeight();
		int rowStride = y.getRowStride();
		byte[] out = new byte[width * height];
		if (rowStride == width) {
			buf.get(out, 0, width * height);
		} else {
			byte[] row = new byte[rowStride];
			for (int r = 0; r < height; r++) {
				buf.get(row, 0, rowStride);
				System.arraycopy(row, 0, out, r * width, width);
			}
		}
		return out;
	}

	private void onLinkScanned(String link) {
		if (!scanComplete.compareAndSet(false, true)) return;

		requireActivity().runOnUiThread(() -> {
			String ownLink = viewModel.getHandshakeLink().getValue();
			if (link.equals(ownLink)) {
				Toast.makeText(requireContext(),
						R.string.own_link_error, LENGTH_SHORT).show();
				scanComplete.set(false);
				return;
			}
			viewModel.setRemoteHandshakeLink(link);
			statusText.setText(R.string.qr_scanned_waiting);
			statusText.setVisibility(View.VISIBLE);
			qrHintText.setText(R.string.qr_scanned_hint);
			continueButton.setVisibility(View.VISIBLE);
		});
	}

	@Override
	public void onDestroyView() {
		if (cameraExecutor != null) {
			cameraExecutor.shutdown();
			cameraExecutor = null;
		}
		if (qrCodeImage != null) {
			qrCodeImage.setImageBitmap(null);
			qrCodeImage = null;
		}
		if (statusText != null) statusText.setText("");
		if (qrHintText != null) qrHintText.setText("");
		if (continueButton != null) continueButton.setOnClickListener(null);
		previewView = null;
		statusText = null;
		qrHintText = null;
		continueButton = null;
		super.onDestroyView();
		System.gc();
	}

}
