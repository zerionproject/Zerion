package com.professor.zerion.android.contact.add.nearby;

import android.Manifest;
import android.content.pm.PackageManager;
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

import com.google.common.util.concurrent.ListenableFuture;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.contact.add.remote.QrCodeUtils;
import com.professor.zerion.android.fragment.BaseFragment;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class AddNearbyContactFragment extends BaseFragment {

	public static final String TAG = AddNearbyContactFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private AddNearbyContactViewModel viewModel;

	private PreviewView previewView;
	private ImageView qrCodeImage;
	private TextView statusText;

	private ExecutorService cameraExecutor;
	private final AtomicBoolean scanComplete = new AtomicBoolean(false);

	private final ActivityResultLauncher<String[]> requestPermsLauncher =
			registerForActivityResult(
					new ActivityResultContracts.RequestMultiplePermissions(),
					results -> onPermissionsResult());

	public static AddNearbyContactFragment newInstance() {
		return new AddNearbyContactFragment();
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_add_nearby_contact,
				container, false);
		previewView = v.findViewById(R.id.cameraPreview);
		qrCodeImage = v.findViewById(R.id.qrCodeImage);
		statusText = v.findViewById(R.id.statusText);
		cameraExecutor = Executors.newSingleThreadExecutor();

		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(AddNearbyContactViewModel.class);

		viewModel.getQrCode().observe(getViewLifecycleOwner(), bitmap -> {
			if (bitmap != null) qrCodeImage.setImageBitmap(bitmap);
		});
		viewModel.getState().observe(getViewLifecycleOwner(), this::onState);

		String[] needed = neededPermissions();
		if (allGranted(needed)) {
			startPairing();
		} else {
			requestPermsLauncher.launch(needed);
		}
		return v;
	}

	private String[] neededPermissions() {
		java.util.List<String> p = new java.util.ArrayList<>();
		p.add(Manifest.permission.CAMERA);
		if (android.os.Build.VERSION.SDK_INT >= 31) {
			p.add("android.permission.BLUETOOTH_ADVERTISE");
			p.add("android.permission.BLUETOOTH_SCAN");
			p.add("android.permission.BLUETOOTH_CONNECT");
		}
		return p.toArray(new String[0]);
	}

	private boolean allGranted(String[] perms) {
		for (String perm : perms) {
			if (ContextCompat.checkSelfPermission(requireContext(), perm)
					!= PackageManager.PERMISSION_GRANTED) {
				return false;
			}
		}
		return true;
	}

	private boolean hasBluetooth() {
		if (android.os.Build.VERSION.SDK_INT < 31) return true;
		return ContextCompat.checkSelfPermission(requireContext(),
				"android.permission.BLUETOOTH_ADVERTISE")
				== PackageManager.PERMISSION_GRANTED
				&& ContextCompat.checkSelfPermission(requireContext(),
				"android.permission.BLUETOOTH_SCAN")
				== PackageManager.PERMISSION_GRANTED
				&& ContextCompat.checkSelfPermission(requireContext(),
				"android.permission.BLUETOOTH_CONNECT")
				== PackageManager.PERMISSION_GRANTED;
	}

	private void onPermissionsResult() {
		if (getContext() == null) return;
		if (ContextCompat.checkSelfPermission(requireContext(),
				Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
			startCamera();
		} else {
			Toast.makeText(requireContext(), R.string.camera_error,
					Toast.LENGTH_LONG).show();
		}
		if (!hasBluetooth()) {
			Toast.makeText(requireContext(),
					R.string.nearby_pairing_no_bluetooth,
					Toast.LENGTH_LONG).show();
		}
		viewModel.startListening();
	}

	private void startPairing() {
		startCamera();
		viewModel.startListening();
	}

	private void onState(AddNearbyContactViewModel.PairingState s) {
		switch (s) {
			case SHOW_QR:
				statusText.setText(R.string.nearby_pairing_scan);
				break;
			case SCANNED:
			case CONNECTING:
				statusText.setText(R.string.nearby_pairing_connecting);
				break;
			case EXCHANGING:
				statusText.setText(R.string.nearby_pairing_exchanging);
				break;
			case SUCCESS:
				String name = viewModel.getContactName().getValue();
				Toast.makeText(requireContext(), getString(
						R.string.nearby_pairing_success,
						name == null ? "" : name), Toast.LENGTH_LONG).show();
				requireActivity().finish();
				break;
			case FAILED:
				statusText.setText(R.string.nearby_pairing_failed);
				break;
		}
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
						CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
			} catch (Exception e) {
				if (getContext() != null) {
					Toast.makeText(requireContext(), R.string.camera_error,
							Toast.LENGTH_LONG).show();
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
				String decoded = QrCodeUtils.decodeQrFromYuv(yPlane,
						imageProxy.getWidth(), imageProxy.getHeight(), 0, 0,
						rotation);
				if (decoded != null
						&& scanComplete.compareAndSet(false, true)) {
					viewModel.onQrScanned(decoded);
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

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		if (cameraExecutor != null) cameraExecutor.shutdown();
	}
}
