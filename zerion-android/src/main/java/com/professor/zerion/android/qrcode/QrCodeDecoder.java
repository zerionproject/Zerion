package com.professor.zerion.android.qrcode;

import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Reader;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.NotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.concurrent.Executor;

import androidx.annotation.UiThread;

import static com.google.zxing.DecodeHintType.CHARACTER_SET;
import static java.util.Collections.singletonMap;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class QrCodeDecoder implements PreviewConsumer, PreviewCallback {


	private final AndroidExecutor androidExecutor;
	private final Executor ioExecutor;
	private final Reader reader = new QRCodeReader();
	private final ResultCallback callback;

	private Camera camera = null;

	public QrCodeDecoder(AndroidExecutor androidExecutor,
			@IoExecutor Executor ioExecutor, ResultCallback callback) {
		this.androidExecutor = androidExecutor;
		this.ioExecutor = ioExecutor;
		this.callback = callback;
	}

	@Override
	public void start(Camera camera, int cameraIndex) {
		this.camera = camera;
		askForPreviewFrame();
	}

	@Override
	public void stop() {
		camera = null;
	}

	@UiThread
	private void askForPreviewFrame() {
		if (camera != null) camera.setOneShotPreviewCallback(this);
	}

	@UiThread
	@Override
	public void onPreviewFrame(byte[] data, Camera camera) {
		if (camera == this.camera) {
			try {
				Size size = camera.getParameters().getPreviewSize();
				if (data.length == size.width * size.height * 3 / 2) {
					decode(data, size.width, size.height);
				} else {
					askForPreviewFrame();
				}
			} catch (RuntimeException e) {
			}
		} else {
		}
	}

	private void decode(byte[] data, int width, int height) {
		ioExecutor.execute(() -> {
			BinaryBitmap bitmap = binarize(data, width, height);
			Result result;
			try {
				result = reader.decode(bitmap,
						singletonMap(CHARACTER_SET, "ISO8859_1"));
				callback.onQrCodeDecoded(result);
			} catch (ReaderException e) {
			} catch (RuntimeException e) {
			} finally {
				reader.reset();
				androidExecutor.runOnUiThread(this::askForPreviewFrame);
			}
		});
	}

	private static BinaryBitmap binarize(byte[] data, int width, int height) {
		LuminanceSource src = new PlanarYUVLuminanceSource(data, width, height,
				0, 0, width, height, false);
		return new BinaryBitmap(new HybridBinarizer(src));
	}

	@NotNullByDefault
	public interface ResultCallback {
		@IoExecutor
		void onQrCodeDecoded(Result result);
	}
}
