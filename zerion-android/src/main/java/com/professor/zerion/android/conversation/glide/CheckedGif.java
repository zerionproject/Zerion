package com.professor.zerion.android.conversation.glide;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.resource.gif.ByteBufferGifDecoder;
import com.bumptech.glide.load.resource.gif.GifDrawable;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.annotation.Nullable;

@NotNullByDefault
final class CheckedGif {

	final byte[] bytes;

	CheckedGif(byte[] bytes) {
		this.bytes = bytes;
	}

	static final class Decoder
			implements ResourceDecoder<CheckedGif, GifDrawable> {

		private final ByteBufferGifDecoder frames;

		Decoder(ByteBufferGifDecoder frames) {
			this.frames = frames;
		}

		@Override
		public boolean handles(CheckedGif source, Options options)
				throws IOException {
			return frames.handles(ByteBuffer.wrap(source.bytes), options);
		}

		@Override
		@Nullable
		public Resource<GifDrawable> decode(CheckedGif source, int width,
				int height, Options options) {
			return frames.decode(ByteBuffer.wrap(source.bytes), width, height,
					options);
		}
	}
}
