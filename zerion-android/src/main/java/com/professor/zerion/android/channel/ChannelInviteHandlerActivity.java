package com.professor.zerion.android.channel;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.briar.api.channel.ChannelInviteLink;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.api.channel.ChannelState;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.concurrent.Executor;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ChannelInviteHandlerActivity extends ZerionActivity {

	@Inject
	ChannelManager channelManager;
	@Inject
	@IoExecutor
	Executor ioExecutor;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Uri data = getIntent() == null ? null : getIntent().getData();
		if (data == null) {
			finish();
			return;
		}
		ChannelInviteLink link =
				channelManager.parseInviteLink(data.toString());
		if (link == null) {
			Toast.makeText(this,
					R.string.channels_join_error_link,
					Toast.LENGTH_LONG).show();
			finish();
			return;
		}
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.channels_join_title)
				.setMessage(data.toString())
				.setPositiveButton(R.string.channels_join_action,
						(d, w) -> handleJoin(link))
				.setNegativeButton(android.R.string.cancel,
						(d, w) -> finish())
				.setOnDismissListener(d -> {
				})
				.show();
	}

	private void handleJoin(ChannelInviteLink link) {
		ioExecutor.execute(() -> {
			try {
				ChannelState s = channelManager.getChannel(
						link.getChannelId());
				if (s == null) {
					channelManager.joinChannel(link);
					try {
						channelManager.bootstrapChannel(
								link.getChannelId());
					} catch (DbException ignored) {
					}
				}
				runOnUiThread(this::openChannel);
			} catch (DbException ex) {
				runOnUiThread(() -> {
					Toast.makeText(this,
							R.string.channels_join_error_link,
							Toast.LENGTH_LONG).show();
					finish();
				});
			}
		});
	}

	private void openChannel() {
		Uri data = getIntent().getData();
		if (data == null) {
			finish();
			return;
		}
		ChannelInviteLink link =
				channelManager.parseInviteLink(data.toString());
		if (link != null) {
			startActivity(ChannelFeedActivity.intent(this,
					link.getChannelId()));
		}
		finish();
	}
}
