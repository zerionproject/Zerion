package com.professor.zerion.android.conversation.glide;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.ModelLoader;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ZerionGlideRegistryTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final Set<Class<?>> RAW_BYTES = new HashSet<>(Arrays.asList(
			InputStream.class, ByteBuffer.class, byte[].class, File.class,
			ParcelFileDescriptor.class, AssetFileDescriptor.class));

	private final Context ctx = ApplicationProvider.getApplicationContext();

	private static AttachmentHeader header(String contentType) {
		return new AttachmentHeader(new GroupId(getRandomId()),
				new MessageId(getRandomId()), contentType);
	}

	private static List<Class<?>> loaderClasses(
			List<ModelLoader<AttachmentHeader, ?>> loaders) {
		List<Class<?>> classes = new ArrayList<>();
		for (ModelLoader<AttachmentHeader, ?> l : loaders) {
			classes.add(l.getClass());
		}
		return classes;
	}

	private static Set<Class<?>> dataClasses(AttachmentHeader header,
			List<ModelLoader<AttachmentHeader, ?>> loaders) {
		Set<Class<?>> data = new HashSet<>();
		for (ModelLoader<AttachmentHeader, ?> l : loaders) {
			ModelLoader.LoadData<?> load =
					l.buildLoadData(header, 100, 100, new Options());
			data.add(load.fetcher.getDataClass());
		}
		return data;
	}

	@Test
	public void aStillImageAttachmentReachesGlideOnlyAsACheckedBitmap() {
		Registry registry = Glide.get(ctx).getRegistry();
		for (String type : new String[] {"image/jpeg", "image/png",
				"image/webp", "image/svg+xml", "text/html",
				"application/octet-stream"}) {
			AttachmentHeader header = header(type);
			List<ModelLoader<AttachmentHeader, ?>> loaders =
					registry.getModelLoaders(header);
			assertEquals(type, Arrays.<Class<?>>asList(ZerionModelLoader.class),
					loaderClasses(loaders));
			assertEquals(type, new HashSet<Class<?>>(
					Arrays.asList(Bitmap.class)), dataClasses(header, loaders));
		}
	}

	@Test
	public void aDeclaredGifReachesGlideOnlyAsACheckedBitmapOrCheckedGif() {
		Registry registry = Glide.get(ctx).getRegistry();
		AttachmentHeader header = header("image/gif");
		List<ModelLoader<AttachmentHeader, ?>> loaders =
				registry.getModelLoaders(header);
		assertEquals(new HashSet<Class<?>>(Arrays.asList(
				ZerionModelLoader.class, ZerionGifLoader.class)),
				new HashSet<>(loaderClasses(loaders)));
		assertEquals(2, loaders.size());
		assertEquals(new HashSet<Class<?>>(Arrays.asList(Bitmap.class,
				CheckedGif.class)), dataClasses(header, loaders));
	}

	@Test
	public void noLoaderHandsGlideTheRawBytesOfAnAttachment() {
		Registry registry = Glide.get(ctx).getRegistry();
		for (String type : new String[] {"image/jpeg", "image/gif",
				"image/png", "video/mp4", "application/pdf"}) {
			AttachmentHeader header = header(type);
			Set<Class<?>> data =
					dataClasses(header, registry.getModelLoaders(header));
			for (Class<?> raw : RAW_BYTES) {
				assertFalse(type + " reaches Glide as " + raw.getSimpleName(),
						data.contains(raw));
			}
		}
	}
}
