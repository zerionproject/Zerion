package com.professor.zerion.android.conversation.glide;

import android.content.Context;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.module.AppGlideModule;

import com.professor.zerion.android.ZerionApplication;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;

@GlideModule
@NotNullByDefault
public final class ZerionGlideModule extends AppGlideModule {

	@Override
	public void registerComponents(Context context, Glide glide,
			Registry registry) {
		ZerionApplication app =
				(ZerionApplication) context.getApplicationContext();
		ZerionModelLoaderFactory factory = new ZerionModelLoaderFactory(app);
		registry.prepend(AttachmentHeader.class, InputStream.class, factory);
	}

	@Override
	public void applyOptions(Context context, GlideBuilder builder) {
		builder.setLogLevel(android.util.Log.WARN);
	}

	@Override
	public boolean isManifestParsingEnabled() {
		return false;
	}

}