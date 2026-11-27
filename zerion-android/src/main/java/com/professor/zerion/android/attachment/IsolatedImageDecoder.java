package com.professor.zerion.android.attachment;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.OsConstants;

import com.professor.zerion.android.util.ReceivedImageGate;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

@NotNullByDefault
public final class IsolatedImageDecoder
		implements ReceivedImageGate.PixelDecoder {

	private static final long BIND_TIMEOUT_MS = 10_000L;
	private static final long CALL_TIMEOUT_MS = 30_000L;
	private static final long IDLE_UNBIND_MS = 10_000L;
	private static final int REMEMBERED_FAILURES = 64;
	private static final int PARALLEL_DECODES = 2;

	private static final ScheduledExecutorService TIMER =
			Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "image-decode-timer");
				t.setDaemon(true);
				return t;
			});

	private final Context context;
	private final Semaphore slots = new Semaphore(PARALLEL_DECODES, true);
	private final Object lock = new Object();
	private final Set<String> failed = new LinkedHashSet<>();

	@Nullable
	private IBinder binder;
	@Nullable
	private ServiceConnection connection;
	@Nullable
	private ScheduledFuture<?> idle;
	private int running;

	public IsolatedImageDecoder(Context context) {
		this.context = context.getApplicationContext();
	}

	@Override
	@Nullable
	public Bitmap decode(byte[] checkedImage, int maxSide) {
		return decode(checkedImage, maxSide, false);
	}

	@Override
	@Nullable
	public Bitmap decodeAlone(byte[] checkedImage, int maxSide) {
		return decode(checkedImage, maxSide, true);
	}

	@Override
	public void release() {
		if (Looper.myLooper() == Looper.getMainLooper()) return;
		try {
			slots.acquire(PARALLEL_DECODES);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		try {
			drop();
		} finally {
			slots.release(PARALLEL_DECODES);
		}
	}

	@Nullable
	private Bitmap decode(byte[] image, int maxSide, boolean alone) {
		if (Looper.myLooper() == Looper.getMainLooper()) return null;
		long size = ReceivedImageGate.dimensions(image);
		if (size == -1) return null;
		String key = key(image);
		boolean[] died = new boolean[1];
		Bitmap bitmap = attempt(alone, image, maxSide, size, key, died);
		if (bitmap != null || !died[0]) return bitmap;
		died[0] = false;
		bitmap = attempt(true, image, maxSide, size, key, died);
		if (bitmap == null && died[0]) remember(key);
		return bitmap;
	}

	@Nullable
	private Bitmap attempt(boolean alone, byte[] image, int maxSide, long size,
			String key, boolean[] died) {
		synchronized (lock) {
			if (failed.contains(key)) return null;
		}
		int permits = alone ? PARALLEL_DECODES : 1;
		try {
			slots.acquire(permits);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
		try {
			if (alone) drop();
			return decoded(image, maxSide, size, died);
		} finally {
			if (alone) drop();
			slots.release(permits);
		}
	}

	private static String key(byte[] image) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(image);
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	@Nullable
	private Bitmap decoded(byte[] image, int maxSide, long size,
			boolean[] died) {
		started();
		try {
			IBinder service = bound();
			if (service == null) return null;
			return answered(service, image, maxSide, size, died);
		} finally {
			finished();
		}
	}

	@Nullable
	private Bitmap answered(IBinder service, byte[] image, int maxSide,
			long size, boolean[] died) {
		int fullWidth = (int) (size >> 32);
		int fullHeight = (int) size;
		int side = ImageDecodeService.side(maxSide);
		int sample = ImageDecodeService.sampleSize(fullWidth, fullHeight, side);
		int capacity = ((fullWidth + sample - 1) / sample)
				* ((fullHeight + sample - 1) / sample) * 4;
		Parcel data = Parcel.obtain();
		Parcel reply = Parcel.obtain();
		SharedMemory in = null;
		SharedMemory out = null;
		ScheduledFuture<?> deadline =
				TIMER.schedule(this::drop, CALL_TIMEOUT_MS,
						TimeUnit.MILLISECONDS);
		try {
			in = SharedMemory.create("image", image.length);
			ByteBuffer source = in.mapReadWrite();
			try {
				source.put(image);
			} finally {
				SharedMemory.unmap(source);
			}
			in.setProtect(OsConstants.PROT_READ);
			out = SharedMemory.create("pixels", capacity);
			data.writeTypedObject(in, 0);
			data.writeInt(image.length);
			data.writeInt(side);
			data.writeTypedObject(out, 0);
			if (!service.transact(ImageDecodeService.DECODE, data, reply,
					0)) {
				drop();
				return null;
			}
			if (reply.readInt() != ImageDecodeService.OK) return null;
			int width = reply.readInt();
			int height = reply.readInt();
			boolean alpha = reply.readInt() != 0;
			if (!ImageDecodeService.matches(width, height, fullWidth,
					fullHeight, sample)) {
				drop();
				return null;
			}
			return copied(out, width, height, alpha);
		} catch (RemoteException e) {
			died[0] = true;
			drop();
			return null;
		} catch (ErrnoException | RuntimeException | OutOfMemoryError e) {
			return null;
		} finally {
			deadline.cancel(false);
			data.recycle();
			reply.recycle();
			if (in != null) in.close();
			if (out != null) out.close();
		}
	}

	private static Bitmap copied(SharedMemory out, int width, int height,
			boolean alpha) throws ErrnoException {
		ByteBuffer pixels = out.mapReadOnly();
		Bitmap bitmap = null;
		try {
			bitmap = Bitmap.createBitmap(width, height,
					Bitmap.Config.ARGB_8888);
			bitmap.copyPixelsFromBuffer(pixels);
			bitmap.setHasAlpha(alpha);
			Bitmap done = bitmap;
			bitmap = null;
			return done;
		} finally {
			if (bitmap != null) bitmap.recycle();
			SharedMemory.unmap(pixels);
		}
	}

	private void remember(String key) {
		synchronized (lock) {
			failed.add(key);
			if (failed.size() > REMEMBERED_FAILURES) {
				failed.remove(failed.iterator().next());
			}
		}
	}

	private void started() {
		synchronized (lock) {
			running++;
			if (idle != null) idle.cancel(false);
			idle = null;
		}
	}

	private void finished() {
		synchronized (lock) {
			if (--running > 0) return;
			idle = TIMER.schedule(this::dropIfIdle, IDLE_UNBIND_MS,
					TimeUnit.MILLISECONDS);
		}
	}

	private void dropIfIdle() {
		ServiceConnection current;
		synchronized (lock) {
			if (running > 0) return;
			current = connection;
			connection = null;
			binder = null;
		}
		if (current != null) unbind(current);
	}

	@Nullable
	private IBinder bound() {
		synchronized (lock) {
			if (binder != null && binder.isBinderAlive()) return binder;
		}
		CountDownLatch answered = new CountDownLatch(1);
		AtomicReference<IBinder> found = new AtomicReference<>();
		ServiceConnection attempt = new ServiceConnection() {
			@Override
			public void onServiceConnected(ComponentName name,
					IBinder service) {
				found.set(service);
				answered.countDown();
			}

			@Override
			public void onServiceDisconnected(ComponentName name) {
				forget(this);
				answered.countDown();
			}

			@Override
			public void onBindingDied(ComponentName name) {
				forget(this);
				answered.countDown();
			}

			@Override
			public void onNullBinding(ComponentName name) {
				answered.countDown();
			}
		};
		boolean binding;
		try {
			binding = context.bindService(
					new Intent(context, ImageDecodeService.class), attempt,
					Context.BIND_AUTO_CREATE);
		} catch (RuntimeException e) {
			return null;
		}
		try {
			if (!binding || !answered.await(BIND_TIMEOUT_MS,
					TimeUnit.MILLISECONDS)) {
				unbind(attempt);
				return null;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			unbind(attempt);
			return null;
		}
		IBinder service = found.get();
		if (service == null) {
			unbind(attempt);
			return null;
		}
		ServiceConnection unused;
		IBinder current;
		synchronized (lock) {
			if (binder != null && binder.isBinderAlive()) {
				unused = attempt;
				current = binder;
			} else {
				unused = connection;
				binder = service;
				connection = attempt;
				current = service;
			}
		}
		if (unused != null) unbind(unused);
		return current;
	}

	private void forget(ServiceConnection gone) {
		synchronized (lock) {
			if (connection != gone) return;
			connection = null;
			binder = null;
		}
		unbind(gone);
	}

	private void drop() {
		ServiceConnection current;
		synchronized (lock) {
			current = connection;
			connection = null;
			binder = null;
		}
		if (current != null) unbind(current);
	}

	private void unbind(ServiceConnection c) {
		try {
			context.unbindService(c);
		} catch (IllegalArgumentException ignored) {
		}
	}
}
