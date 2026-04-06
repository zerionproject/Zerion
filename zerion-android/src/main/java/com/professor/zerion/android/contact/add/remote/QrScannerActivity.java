package com.professor.zerion.android.contact.add.remote;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.professor.zerion.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;

import static org.briarproject.bramble.api.contact.HandshakeLinkConstants.LINK_REGEX;

/**
 * Camera-based QR code scanner for reading Zerion handshake links.
 * Uses CameraX + ML Kit barcode scanning.
 *
 * Returns the scanned link via EXTRA_SCANNED_LINK in the result Intent.
 */
public class QrScannerActivity extends AppCompatActivity {

	public static final String EXTRA_SCANNED_LINK = "scanned_link";
	private static final int CAMERA_PERMISSION_REQUEST = 1001;

	private PreviewView previewView;
	private ExecutorService cameraExecutor;
	private BarcodeScanner barcodeScanner;
	private final AtomicBoolean scanComplete = new AtomicBoolean(false);

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_qr_scanner);

		previewView = findViewById(R.id.previewView);
		cameraExecutor = Executors.newSingleThreadExecutor();

		BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
				.setBarcodeFormats(Barcode.FORMAT_QR_CODE)
				.build();
		barcodeScanner = BarcodeScanning.getClient(options);

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

				analysis.setAnalyzer(cameraExecutor, imageProxy -> {
					if (scanComplete.get()) {
						imageProxy.close();
						return;
					}
					@SuppressWarnings("UnsafeOptInUsageError")
					android.media.Image mediaImage = imageProxy.getImage();
					if (mediaImage == null) {
						imageProxy.close();
						return;
					}

					InputImage image = InputImage.fromMediaImage(mediaImage,
							imageProxy.getImageInfo().getRotationDegrees());

					barcodeScanner.process(image)
							.addOnSuccessListener(barcodes -> {
								for (Barcode barcode : barcodes) {
									String raw = barcode.getRawValue();
									if (raw != null && isZerionLink(raw)) {
										onLinkScanned(raw);
										break;
									}
								}
							})
							.addOnCompleteListener(task -> imageProxy.close());
				});

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

	private boolean isZerionLink(String text) {
		Matcher matcher = LINK_REGEX.matcher(text);
		return matcher.find();
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
		barcodeScanner.close();
	}
}
