package com.professor.zerion.android.channel;

import android.content.Context;

import com.professor.zerion.R;
import com.professor.zerion.android.util.DisplayNames;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
final class ChannelNames {

	private ChannelNames() {
	}

	static String shown(Context ctx, @Nullable String name) {
		String clean = DisplayNames.clean(name).trim();
		return clean.isEmpty() ? ctx.getString(R.string.channels_button)
				: clean;
	}
}
