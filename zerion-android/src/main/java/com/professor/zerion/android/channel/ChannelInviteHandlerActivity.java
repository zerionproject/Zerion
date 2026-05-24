package com.professor.zerion.android.channel;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
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
		getWindow().setFlags(
				android.view.WindowManager.LayoutParams.FLAG_SECURE,
				android.view.WindowManager.LayoutParams.FLAG_SECURE);
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
		if (link.requiresApproval()) {
			showApplyDialog(link);
		} else {
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
	}

	private void showApplyDialog(ChannelInviteLink link) {
		View view = LayoutInflater.from(this).inflate(
				R.layout.dialog_apply_to_join, null);
		TextInputEditText nameInput =
				view.findViewById(R.id.channelApplyNameInput);
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.channels_apply_title)
				.setView(view)
				.setPositiveButton(R.string.channels_apply_action,
						(d, w) -> handleApply(link, nameInput))
				.setNegativeButton(android.R.string.cancel,
						(d, w) -> finish())
				.show();
	}

	private void handleApply(ChannelInviteLink link,
			TextInputEditText nameInput) {
		String name = nameInput.getText() == null
				? "" : nameInput.getText().toString().trim();
		if (name.isEmpty()) {
			Toast.makeText(this,
					R.string.channels_create_error_name,
					Toast.LENGTH_SHORT).show();
			finish();
			return;
		}
		byte[] cid = link.getChannelId();
		ioExecutor.execute(() -> {
			try {
				ChannelState s = channelManager.getChannel(cid);
				if (s == null) {
					channelManager.joinChannel(link);
				}
				channelManager.applyToJoin(cid, name);
				runOnUiThread(() -> openChannel(cid));
			} catch (DbException ex) {
				runOnUiThread(() -> {
					Toast.makeText(this,
							R.string.channels_apply_failed,
							Toast.LENGTH_LONG).show();
					finish();
				});
			}
		});
	}

	private void handleJoin(ChannelInviteLink link) {
		byte[] cid = link.getChannelId();
		ioExecutor.execute(() -> {
			try {
				ChannelState s = channelManager.getChannel(cid);
				if (s == null) {
					channelManager.joinChannel(link);
					try {
						channelManager.bootstrapChannel(cid);
					} catch (DbException ignored) {
					}
				}
				runOnUiThread(() -> openChannel(cid));
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

	private void openChannel(byte[] channelId) {
		startActivity(ChannelFeedActivity.intent(this, channelId));
		finish();
	}
}
