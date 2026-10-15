package com.professor.zerion.android.attachment;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import javax.annotation.Nullable;

@NotNullByDefault
public class PdfFlattenService extends Service {

	static final int FLATTEN = IBinder.FIRST_CALL_TRANSACTION;

	static final int OK = 0;
	static final int FAILED = 1;
	static final int TOO_LARGE = 2;
	static final int TOO_MANY_PAGES = 3;

	static final int MAX_PAGES = 200;
	static final long TIME_LIMIT_MS = 120_000L;

	private static final float RENDER_DPI = 150f;
	static final int MAX_SIDE_PX = 2400;
	private static final int JPEG_QUALITY = 85;

	@Override
	public IBinder onBind(Intent intent) {
		return new Binder() {
			@Override
			protected boolean onTransact(int code, Parcel data,
					@Nullable Parcel reply, int flags)
					throws RemoteException {
				if (code != FLATTEN || reply == null) {
					return super.onTransact(code, data, reply, flags);
				}
				ParcelFileDescriptor in =
						data.readTypedObject(ParcelFileDescriptor.CREATOR);
				ParcelFileDescriptor out =
						data.readTypedObject(ParcelFileDescriptor.CREATOR);
				long maxBytes = data.readLong();
				int status = in == null || out == null ? FAILED
						: guarded(in, out, maxBytes);
				reply.writeInt(status);
				return true;
			}
		};
	}

	private static int guarded(ParcelFileDescriptor in,
			ParcelFileDescriptor out, long maxBytes) {
		Thread watchdog = new Thread(() -> {
			try {
				Thread.sleep(TIME_LIMIT_MS);
			} catch (InterruptedException e) {
				return;
			}
			Process.killProcess(Process.myPid());
		});
		watchdog.setDaemon(true);
		watchdog.start();
		try (ParcelFileDescriptor source = in;
				OutputStream sink = new FileOutputStream(
						out.getFileDescriptor())) {
			return flatten(source, sink, maxBytes,
					SystemClock.uptimeMillis() + TIME_LIMIT_MS);
		} catch (IOException e) {
			return FAILED;
		} finally {
			watchdog.interrupt();
			try {
				out.close();
			} catch (IOException ignored) {
			}
		}
	}

	private static int flatten(ParcelFileDescriptor in, OutputStream out,
			long maxBytes, long deadline) {
		try (PdfRenderer renderer = new PdfRenderer(in)) {
			int count = renderer.getPageCount();
			if (count < 1) return FAILED;
			if (count > MAX_PAGES) return TOO_MANY_PAGES;
			CleanPdfWriter writer = new CleanPdfWriter(out, maxBytes);
			for (int i = 0; i < count; i++) {
				if (SystemClock.uptimeMillis() > deadline) return FAILED;
				try (PdfRenderer.Page page = renderer.openPage(i)) {
					int status = drawPage(page, writer);
					if (status != OK) return status;
				}
			}
			writer.finish();
			return OK;
		} catch (CleanPdfWriter.TooLargeException e) {
			return TOO_LARGE;
		} catch (IOException | RuntimeException | OutOfMemoryError e) {
			return FAILED;
		}
	}

	private static int drawPage(PdfRenderer.Page page, CleanPdfWriter writer)
			throws IOException {
		int pointWidth = page.getWidth();
		int pointHeight = page.getHeight();
		if (pointWidth < 1 || pointHeight < 1) return FAILED;
		float scale = Math.min(RENDER_DPI / 72f,
				MAX_SIDE_PX / (float) Math.max(pointWidth, pointHeight));
		int width = Math.max(1, Math.round(pointWidth * scale));
		int height = Math.max(1, Math.round(pointHeight * scale));
		Bitmap bitmap = Bitmap.createBitmap(width, height,
				Bitmap.Config.ARGB_8888);
		try {
			bitmap.eraseColor(Color.WHITE);
			page.render(bitmap, null, null,
					PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
			ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
			if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY,
					jpeg)) {
				return FAILED;
			}
			writer.addPage(ImageScrubber.jpeg(jpeg.toByteArray()), width,
					height, pointWidth, pointHeight);
			return OK;
		} finally {
			bitmap.recycle();
		}
	}
}
