package com.professor.zerion.android.conversation.glide;

import android.content.Context;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.resource.gif.ByteBufferGifDecoder;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import com.bumptech.glide.module.AppGlideModule;

import com.professor.zerion.android.ZerionApplication;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import android.graphics.Bitmap;

import static android.util.Log.ERROR;

@GlideModule
@NotNullByDefault
public final class ZerionGlideModule extends AppGlideModule {

	@Override
	public void registerComponents(Context context, Glide glide,
			Registry registry) {
		ZerionApplication app =
				(ZerionApplication) context.getApplicationContext();
		ZerionModelLoaderFactory factory = new ZerionModelLoaderFactory(app);
		registry.prepend(AttachmentHeader.class, Bitmap.class, factory);
		registry.prepend(AttachmentHeader.class, CheckedGif.class,
				new ZerionGifLoader.Factory(app));
		registry.prepend(Registry.BUCKET_ANIMATION, CheckedGif.class,
				GifDrawable.class, new CheckedGif.Decoder(
						new ByteBufferGifDecoder(context,
								registry.getImageHeaderParsers(),
								glide.getBitmapPool(), glide.getArrayPool())));
	}

	@Override
	public void applyOptions(Context context, GlideBuilder builder) {
		builder.setLogLevel(ERROR);
		builder.setDiskCache(() -> null);
	}

	@Override
	public boolean isManifestParsingEnabled() {
		return false;
	}

}