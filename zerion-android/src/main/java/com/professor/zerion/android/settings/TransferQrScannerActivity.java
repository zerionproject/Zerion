package com.professor.zerion.android.settings;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.professor.zerion.R;
import com.professor.zerion.android.backup.AccountTransferManager;
import com.professor.zerion.android.contact.add.remote.QrCodeUtils;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class TransferQrScannerActivity extends AppCompatActivity {

	public static final String EXTRA_SCANNED_LINK = "scanned_link";
	private static final int CAMERA_PERMISSION_REQUEST = 1002;

	private PreviewView previewView;
	private ExecutorService cameraExecutor;
	private final AtomicBoolean scanComplete = new AtomicBoolean(false);

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_qr_scanner);

		previewView = findViewById(R.id.previewView);
		cameraExecutor = Executors.newSingleThreadExecutor();

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
				== PackageManager.PERMISSION_GRANTED) {
			startCamera();
		} else {
			ActivityCompat.requestPermissions(this,
					new String[]{Manifest.permission.CAMERA},
					CAMERA_PERMISSION_REQUEST);
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode,
			@NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions,
				grantResults);
		if (requestCode == CAMERA_PERMISSION_REQUEST) {
			if (grantResults.length > 0
					&& grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				startCamera();
			} else {
				Toast.makeText(this, R.string.camera_permission_denied,
						Toast.LENGTH_LONG).show();
				finish();
			}
		}
	}

	@SuppressWarnings("RestrictedApi")
	private void startCamera() {
		ListenableFuture<ProcessCameraProvider> future =
				ProcessCameraProvider.getInstance(this);

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
				provider.bindToLifecycle(this,
						CameraSelector.DEFAULT_BACK_CAMERA,
						preview, analysis);

			} catch (Exception e) {
				Toast.makeText(this, R.string.camera_error,
						Toast.LENGTH_LONG).show();
				finish();
			}
		}, ContextCompat.getMainExecutor(this));
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
				if (decoded != null && isTransferLink(decoded)) {
					onLinkScanned(decoded);
				}
			}
		} finally {
			imageProxy.close();
		}
	}

	@androidx.annotation.Nullable
	private static byte[] extractYPlane(ImageProxy proxy) {
		ImageProxy.PlaneProxy[] planes = proxy.getPlanes();
		if (planes.length == 0) return null;
		ImageProxy.PlaneProxy y = planes[0];
		ByteBuffer buf = y.getBuffer();
		int width = proxy.getWidth();
		int height = proxy.getHeight();
		if (width <= 0 || height <= 0) return null;
		int rowStride = y.getRowStride();
		int pixelStride = y.getPixelStride();
		if (rowStride <= 0) rowStride = width;
		if (pixelStride <= 0) pixelStride = 1;

		byte[] out = new byte[width * height];
		byte[] row = new byte[rowStride];
		for (int r = 0; r < height; r++) {
			int toRead = Math.min(rowStride, buf.remaining());
			if (toRead <= 0) break;
			buf.get(row, 0, toRead);
			if (pixelStride == 1) {
				System.arraycopy(row, 0, out, r * width,
						Math.min(width, toRead));
			} else {
				int outPos = r * width;
				for (int c = 0; c < width && c * pixelStride < toRead; c++) {
					out[outPos + c] = row[c * pixelStride];
				}
			}
		}
		return out;
	}

	private boolean isTransferLink(String text) {
		return text.startsWith(AccountTransferManager.LINK_PREFIX);
	}

	private void onLinkScanned(String link) {
		if (!scanComplete.compareAndSet(false, true)) return;

		runOnUiThread(() -> {
			Intent result = new Intent();
			result.putExtra(EXTRA_SCANNED_LINK, link);
			setResult(RESULT_OK, result);
			finish();
		});
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		cameraExecutor.shutdown();
	}
}
