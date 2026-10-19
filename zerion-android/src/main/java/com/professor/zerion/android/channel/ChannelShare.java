package com.professor.zerion.android.channel;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import com.professor.zerion.R;
import com.professor.zerion.android.util.SecureClipboard;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.core.api.db.DbException;

import java.util.concurrent.Executor;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
final class ChannelShare {

	private ChannelShare() {
	}

	static void copyInvite(Activity activity, ChannelManager manager,
			Executor ioExecutor, byte[] channelId) {
		Toast.makeText(activity, R.string.channels_invite_preparing,
				Toast.LENGTH_SHORT).show();
		ioExecutor.execute(() -> {
			String link;
			try {
				link = manager.exportInviteLink(channelId);
			} catch (DbException e) {
				onUi(activity, () -> notReady(activity));
				return;
			}
			onUi(activity, () -> {
				SecureClipboard.copy(activity, "zerion-channel", link);
				Toast.makeText(activity, R.string.channels_invite_copied,
						Toast.LENGTH_SHORT).show();
			});
		});
	}

	static void sendInvite(Activity activity, ChannelManager manager,
			Executor ioExecutor, byte[] channelId) {
		Toast.makeText(activity, R.string.channels_invite_preparing,
				Toast.LENGTH_SHORT).show();
		ioExecutor.execute(() -> {
			String link;
			try {
				link = manager.exportInviteLink(channelId);
			} catch (DbException e) {
				onUi(activity, () -> notReady(activity));
				return;
			}
			onUi(activity, () -> {
				Intent send = new Intent(Intent.ACTION_SEND);
				send.setType("text/plain");
				send.putExtra(Intent.EXTRA_TEXT, activity.getString(
						R.string.channels_share_message_prefix) + link);
				activity.startActivity(Intent.createChooser(send,
						activity.getString(
								R.string.channels_share_chooser_title)));
			});
		});
	}

	private static void notReady(Activity activity) {
		Toast.makeText(activity, R.string.channels_invite_not_ready,
				Toast.LENGTH_LONG).show();
	}

	static void onUi(Activity activity, Runnable r) {
		activity.runOnUiThread(() -> {
			if (!activity.isFinishing() && !activity.isDestroyed()) r.run();
		});
	}
}
