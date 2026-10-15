package com.professor.zerion.android.attachment;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import com.professor.zerion.android.util.CacheSweeper;
import com.professor.zerion.android.vault.utils.SecureMemory;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

@NotNullByDefault
public final class PdfCleaner {

	private static final long BIND_TIMEOUT_MS = 20_000L;
	private static final long RENDER_TIMEOUT_MS =
			PdfFlattenService.TIME_LIMIT_MS + 15_000L;
	private static final int JPEG_QUALITY = 85;
	static final long MAX_PDF_BYTES = 64L * 1024 * 1024;

	private static final Object ONE_AT_A_TIME = new Object();
	private static final ScheduledThreadPoolExecutor TIMER = timer();

	private static ScheduledThreadPoolExecutor timer() {
		ScheduledThreadPoolExecutor t = new ScheduledThreadPoolExecutor(1,
				r -> {
					Thread thread = new Thread(r, "pdf-clean-timer");
					thread.setDaemon(true);
					return thread;
				});
		t.setRemoveOnCancelPolicy(true);
		return t;
	}

	private final Context context;

	public PdfCleaner(Context context) {
		this.context = context.getApplicationContext();
	}

	public byte[] clean(byte[] pdf, long maxBytes) throws IOException {
		if (pdf.length > MAX_PDF_BYTES) {
			throw MediaRefusedException.tooLarge("document too large to clean");
		}
		long cap = Math.min(maxBytes, MAX_PDF_BYTES);
		try {
			byte[] drawn;
			synchronized (ONE_AT_A_TIME) {
				drawn = drawn(pdf, cap);
			}
			return reencoded(drawn, cap);
		} catch (MediaRefusedException e) {
			throw e;
		} catch (IOException e) {
			throw MediaRefusedException.cannotClean(e);
		} catch (OutOfMemoryError e) {
			throw MediaRefusedException.tooLarge("document too large to clean");
		}
	}

	private static byte[] reencoded(byte[] drawn, long maxBytes)
			throws IOException {
		List<CleanPdfPages.Page> pages = CleanPdfPages.read(drawn);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CleanPdfWriter writer = new CleanPdfWriter(out, maxBytes);
		try {
			for (CleanPdfPages.Page page : pages) {
				writer.addPage(reencodedPage(page), page.width, page.height,
						page.pointWidth, page.pointHeight);
			}
			writer.finish();
		} catch (CleanPdfWriter.TooLargeException e) {
			throw MediaRefusedException.tooLarge("cleaned document too large");
		}
		return out.toByteArray();
	}

	private static byte[] reencodedPage(CleanPdfPages.Page page)
			throws IOException {
		if (page.width > PdfFlattenService.MAX_SIDE_PX
				|| page.height > PdfFlattenService.MAX_SIDE_PX) {
			throw new IOException("page image too large");
		}
		BitmapFactory.Options bounds = new BitmapFactory.Options();
		bounds.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(page.source, page.offset, page.length,
				bounds);
		if (bounds.outWidth != page.width || bounds.outHeight != page.height) {
			throw new IOException("page image does not match its page");
		}
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inPreferredConfig = Bitmap.Config.ARGB_8888;
		options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
		Bitmap bitmap = BitmapFactory.decodeByteArray(page.source,
				page.offset, page.length, options);
		if (bitmap == null) throw new IOException("page image unreadable");
		try {
			ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
			if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY,
					jpeg)) {
				throw new IOException("page image not encoded");
			}
			return ImageScrubber.jpeg(jpeg.toByteArray());
		} finally {
			bitmap.recycle();
		}
	}

	private byte[] drawn(byte[] pdf, long maxBytes) throws IOException {
		try (Buffer in = Buffer.create(context)) {
			in.fill(pdf);
			ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
			Drain drain = new Drain(pipe[0], maxBytes);
			drain.start();
			int status;
			try {
				status = flatten(in, pipe[1], maxBytes);
			} finally {
				closeQuietly(pipe[1]);
			}
			byte[] out = drain.result();
			if (status == PdfFlattenService.TOO_LARGE || drain.tooLarge) {
				throw MediaRefusedException.tooLarge(
						"cleaned document too large");
			}
			if (status == PdfFlattenService.TOO_MANY_PAGES) {
				throw MediaRefusedException.tooLarge(
						"document has too many pages");
			}
			if (status != PdfFlattenService.OK || out == null) {
				throw MediaRefusedException.cannotClean(
						"document cannot be cleaned");
			}
			return out;
		}
	}

	private int flatten(Buffer in, ParcelFileDescriptor out, long maxBytes)
			throws IOException {
		CountDownLatch bound = new CountDownLatch(1);
		AtomicReference<IBinder> service = new AtomicReference<>();
		ServiceConnection connection = new ServiceConnection() {
			@Override
			public void onServiceConnected(ComponentName name,
					IBinder binder) {
				service.set(binder);
				bound.countDown();
			}

			@Override
			public void onServiceDisconnected(ComponentName name) {
				bound.countDown();
			}

			@Override
			public void onBindingDied(ComponentName name) {
				bound.countDown();
			}

			@Override
			public void onNullBinding(ComponentName name) {
				bound.countDown();
			}
		};
		Intent i = new Intent(context, PdfFlattenService.class);
		boolean binding;
		try {
			binding = context.bindService(i, connection,
					Context.BIND_AUTO_CREATE);
		} catch (RuntimeException e) {
			throw new IOException("cleaner unavailable", e);
		}
		if (!binding) {
			unbind(connection);
			throw new IOException("cleaner unavailable");
		}
		ScheduledFuture<?> deadline = TIMER.schedule(() -> unbind(connection),
				RENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		Parcel data = Parcel.obtain();
		Parcel reply = Parcel.obtain();
		try {
			if (!bound.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				throw new IOException("cleaner unavailable");
			}
			IBinder binder = service.get();
			if (binder == null) throw new IOException("cleaner unavailable");
			ParcelFileDescriptor source = in.forReading();
			try {
				data.writeTypedObject(source, 0);
			} finally {
				closeQuietly(source);
			}
			data.writeTypedObject(out, 0);
			data.writeLong(maxBytes);
			closeQuietly(out);
			if (!binder.transact(PdfFlattenService.FLATTEN, data, reply, 0)) {
				throw new IOException("cleaner unavailable");
			}
			return reply.readInt();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException(e);
		} catch (RemoteException e) {
			return PdfFlattenService.FAILED;
		} finally {
			deadline.cancel(false);
			data.recycle();
			reply.recycle();
			unbind(connection);
		}
	}

	private void unbind(ServiceConnection connection) {
		try {
			context.unbindService(connection);
		} catch (IllegalArgumentException ignored) {
		}
	}

	private static void closeQuietly(@Nullable ParcelFileDescriptor pfd) {
		if (pfd == null) return;
		try {
			pfd.close();
		} catch (IOException ignored) {
		}
	}

	private static final class Drain extends Thread {

		private final ParcelFileDescriptor source;
		private final long maxBytes;
		@Nullable
		private volatile byte[] result;
		private volatile boolean tooLarge = false;

		Drain(ParcelFileDescriptor source, long maxBytes) {
			super("pdf-clean-drain");
			setDaemon(true);
			this.source = source;
			this.maxBytes = maxBytes;
		}

		@Override
		public void run() {
			try (InputStream in =
						new ParcelFileDescriptor.AutoCloseInputStream(source)) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				byte[] buf = new byte[64 * 1024];
				long total = 0;
				int n;
				while ((n = in.read(buf)) != -1) {
					total += n;
					if (total > maxBytes) {
						tooLarge = true;
						return;
					}
					out.write(buf, 0, n);
				}
				result = out.toByteArray();
			} catch (IOException | OutOfMemoryError e) {
				result = null;
			}
		}

		@Nullable
		byte[] result() throws IOException {
			try {
				join(RENDER_TIMEOUT_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException(e);
			}
			if (isAlive()) {
				closeQuietly(source);
				return null;
			}
			return result;
		}
	}

	private abstract static class Buffer implements AutoCloseable {

		static Buffer create(Context ctx) throws IOException {
			if (Build.VERSION.SDK_INT >= 30) return new MemoryBuffer();
			return new FileBuffer(ctx);
		}

		abstract void fill(byte[] data) throws IOException;

		abstract ParcelFileDescriptor forReading() throws IOException;

		@Override
		public abstract void close();
	}

	private static final class MemoryBuffer extends Buffer {

		private final FileDescriptor fd;

		MemoryBuffer() throws IOException {
			try {
				fd = Os.memfd_create("in", OsConstants.MFD_CLOEXEC);
			} catch (ErrnoException e) {
				throw new IOException(e);
			}
		}

		@Override
		void fill(byte[] data) throws IOException {
			try (OutputStream out = new ParcelFileDescriptor
					.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd))) {
				out.write(data);
			}
		}

		@Override
		ParcelFileDescriptor forReading() throws IOException {
			try {
				Os.lseek(fd, 0, OsConstants.SEEK_SET);
			} catch (ErrnoException e) {
				throw new IOException(e);
			}
			return ParcelFileDescriptor.dup(fd);
		}

		@Override
		public void close() {
			try {
				Os.ftruncate(fd, 0);
			} catch (ErrnoException ignored) {
			}
			try {
				Os.close(fd);
			} catch (ErrnoException ignored) {
			}
		}
	}

	private static final class FileBuffer extends Buffer {

		private final File file;

		FileBuffer(Context ctx) throws IOException {
			file = CacheSweeper.claim(File.createTempFile("pdf_clean_", null,
					ctx.getCacheDir()));
		}

		@Override
		void fill(byte[] data) throws IOException {
			try (OutputStream out = new FileOutputStream(file)) {
				out.write(data);
			}
		}

		@Override
		ParcelFileDescriptor forReading() throws IOException {
			return ParcelFileDescriptor.open(file,
					ParcelFileDescriptor.MODE_READ_ONLY);
		}

		@Override
		public void close() {
			SecureMemory.secureDeleteFile(file);
			CacheSweeper.release(file);
		}
	}
}
