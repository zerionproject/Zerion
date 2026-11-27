package com.professor.zerion.android.attachment;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.SharedMemory;

import com.professor.zerion.android.util.ReceivedImageGate;

import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

public class ImageDecodeService extends Service {

	static final int DECODE = IBinder.FIRST_CALL_TRANSACTION;

	static final int OK = 0;
	static final int FAILED = 1;

	static final int MAX_SIDE = 4096;
	static final int MAX_IMAGE_BYTES = 64 * 1024 * 1024;
	static final long TIME_LIMIT_MS = 20_000L;

	private static final ScheduledExecutorService WATCHDOG =
			Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "image-decode-watchdog");
				t.setDaemon(true);
				return t;
			});

	@Override
	public IBinder onBind(Intent intent) {
		return new Binder() {
			@Override
			protected boolean onTransact(int code, Parcel data,
					@Nullable Parcel reply, int flags) {
				if (code != DECODE || reply == null) return false;
				SharedMemory in =
						data.readTypedObject(SharedMemory.CREATOR);
				int length = data.readInt();
				int maxSide = data.readInt();
				SharedMemory out =
						data.readTypedObject(SharedMemory.CREATOR);
				answer(in, length, maxSide, out, reply);
				return true;
			}
		};
	}

	private static void answer(@Nullable SharedMemory in, int length,
			int maxSide, @Nullable SharedMemory out, Parcel reply) {
		ScheduledFuture<?> limit = WATCHDOG.schedule(
				() -> Process.killProcess(Process.myPid()), TIME_LIMIT_MS,
				TimeUnit.MILLISECONDS);
		Bitmap bitmap = null;
		try {
			byte[] image = received(in, length);
			bitmap = image == null || out == null ? null
					: pixels(image, maxSide);
			if (bitmap == null) {
				reply.writeInt(FAILED);
				return;
			}
			int width = bitmap.getWidth();
			int height = bitmap.getHeight();
			if ((long) width * height * 4 > out.getSize()) {
				reply.writeInt(FAILED);
				return;
			}
			ByteBuffer mapped = out.mapReadWrite();
			try {
				bitmap.copyPixelsToBuffer(mapped);
			} finally {
				SharedMemory.unmap(mapped);
			}
			reply.writeInt(OK);
			reply.writeInt(width);
			reply.writeInt(height);
			reply.writeInt(bitmap.hasAlpha() ? 1 : 0);
		} catch (Exception | OutOfMemoryError e) {
			reply.setDataPosition(0);
			reply.setDataSize(0);
			reply.writeInt(FAILED);
		} finally {
			limit.cancel(false);
			if (bitmap != null) bitmap.recycle();
			if (out != null) out.close();
			if (in != null) in.close();
		}
	}

	@Nullable
	private static byte[] received(@Nullable SharedMemory in, int length)
			throws Exception {
		if (in == null || length < 12 || length > MAX_IMAGE_BYTES
				|| length > in.getSize()) {
			return null;
		}
		ByteBuffer mapped = in.mapReadOnly();
		try {
			byte[] image = new byte[length];
			mapped.get(image);
			return image;
		} finally {
			SharedMemory.unmap(mapped);
		}
	}

	@Nullable
	static Bitmap pixels(byte[] image, int maxSide) {
		if (!ReceivedImageGate.isAcceptedFormat(image)) return null;
		long size = ReceivedImageGate.dimensions(image);
		if (size == -1) return null;
		int fullWidth = (int) (size >> 32);
		int fullHeight = (int) size;
		if ((long) fullWidth * fullHeight > ReceivedImageGate.MAX_PIXELS) {
			return null;
		}
		int sample = sampleSize(fullWidth, fullHeight, side(maxSide));
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inSampleSize = sample;
		options.inPreferredConfig = Bitmap.Config.ARGB_8888;
		options.inPreferredColorSpace =
				ColorSpace.get(ColorSpace.Named.SRGB);
		Bitmap decoded =
				BitmapFactory.decodeByteArray(image, 0, image.length, options);
		if (decoded == null) return null;
		Bitmap bitmap = decoded;
		if (decoded.getConfig() != Bitmap.Config.ARGB_8888) {
			bitmap = decoded.copy(Bitmap.Config.ARGB_8888, false);
			decoded.recycle();
			if (bitmap == null) return null;
		}
		if (!matches(bitmap.getWidth(), bitmap.getHeight(), fullWidth,
				fullHeight, sample)) {
			bitmap.recycle();
			return null;
		}
		return bitmap;
	}

	static int side(int maxSide) {
		return Math.max(1, Math.min(maxSide, MAX_SIDE));
	}

	static int sampleSize(int width, int height, int side) {
		int longest = Math.max(width, height);
		int shortest = Math.min(width, height);
		int sample = 1;
		while ((longest + sample - 1) / sample > side
				&& shortest >= sample * 2) {
			sample *= 2;
		}
		while ((longest + sample - 1) / sample > MAX_SIDE) sample *= 2;
		return sample;
	}

	static boolean matches(int width, int height, int fullWidth,
			int fullHeight, int sample) {
		return scaled(width, fullWidth, sample)
				&& scaled(height, fullHeight, sample);
	}

	private static boolean scaled(int actual, int full, int sample) {
		return actual >= Math.max(1, full / sample)
				&& actual <= (full + sample - 1) / sample;
	}
}
