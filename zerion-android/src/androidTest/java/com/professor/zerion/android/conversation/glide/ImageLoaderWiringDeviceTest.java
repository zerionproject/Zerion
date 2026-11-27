package com.professor.zerion.android.conversation.glide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.resource.gif.ByteBufferGifDecoder;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import com.professor.zerion.android.util.ReceivedImageGate;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class ImageLoaderWiringDeviceTest {

	private static final byte[] TWO_FRAMES = {'G', 'I', 'F', '8', '9', 'a', 1,
			0, 1, 0, (byte) 0x80, 0, 0, (byte) 0xFF, 0, 0, 0, 0, (byte) 0xFF,
			0x21, (byte) 0xF9, 4, 0, 10, 0, 0, 0,
			0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0,
			0x21, (byte) 0xF9, 4, 0, 10, 0, 0, 0,
			0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x4C, 1, 0,
			0x3B};

	private static AttachmentHeader header(String type) {
		return new AttachmentHeader(new GroupId(new byte[32]),
				new MessageId(new byte[32]), type);
	}

	@Test
	public void aPictureFromSomeoneElseReachesTheScreenOnlyAsPixels() {
		Context ctx = ApplicationProvider.getApplicationContext();
		Registry registry = Glide.get(ctx).getRegistry();
		for (String type : new String[] {"image/jpeg", "image/png",
				"image/webp", "application/octet-stream"}) {
			List<ModelLoader<AttachmentHeader, ?>> loaders =
					registry.getModelLoaders(header(type));
			assertEquals(type, 1, loaders.size());
			assertTrue(type, loaders.get(0) instanceof ZerionModelLoader);
		}
		assertNotNull(registry.getLoadPath(Bitmap.class, Object.class,
				Drawable.class));
		assertNotNull(registry.getLoadPath(Bitmap.class, Bitmap.class,
				Bitmap.class));
	}

	@Test
	public void anAnimationIsReadByTheJavaDecoderAndNeverByAPlatformOne()
			throws Exception {
		Context ctx = ApplicationProvider.getApplicationContext();
		Glide glide = Glide.get(ctx);
		Registry registry = glide.getRegistry();
		List<ModelLoader<AttachmentHeader, ?>> loaders =
				registry.getModelLoaders(header("image/gif"));
		assertEquals(2, loaders.size());
		assertTrue(loaders.get(0) instanceof ZerionGifLoader);
		assertTrue(loaders.get(1) instanceof ZerionModelLoader);
		assertNotNull(registry.getLoadPath(CheckedGif.class, Object.class,
				Drawable.class));
		assertNull(registry.getLoadPath(CheckedGif.class, Bitmap.class,
				Bitmap.class));
		for (ModelLoader<AttachmentHeader, ?> loader : loaders) {
			ModelLoader.LoadData<?> data = loader.buildLoadData(
					header("image/gif"), 100, 100, new Options());
			assertNotNull(data);
			Class<?> delivered = data.fetcher.getDataClass();
			assertTrue(delivered.getName(), delivered != InputStream.class
					&& delivered != ByteBuffer.class);
		}

		byte[] checked = ReceivedImageGate.checked(TWO_FRAMES);
		CheckedGif.Decoder decoder = new CheckedGif.Decoder(
				new ByteBufferGifDecoder(ctx, registry.getImageHeaderParsers(),
						glide.getBitmapPool(), glide.getArrayPool()));
		CheckedGif gif = new CheckedGif(checked);
		assertTrue(decoder.handles(gif, new Options()));
		Resource<GifDrawable> shown =
				decoder.decode(gif, 100, 100, new Options());
		assertNotNull(shown);
		assertEquals(2, shown.get().getFrameCount());
	}
}
