package com.professor.zerion.android.channel;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.professor.zerion.R;
import com.professor.zerion.android.channel.ChannelMember.Action;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.core.api.db.DbException;

import java.util.List;
import java.util.concurrent.Executor;

import static com.professor.zerion.android.util.UiUtils.resolveColorAttribute;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
final class ChannelMemberSheet {

	interface Listener {
		void onChanged();
	}

	private interface Change {
		void run() throws DbException;
	}

	private final Activity activity;
	private final ChannelManager manager;
	private final Executor ioExecutor;
	private final byte[] channelId;
	private final Listener listener;

	private ChannelMemberSheet(Activity activity, ChannelManager manager,
			Executor ioExecutor, byte[] channelId, Listener listener) {
		this.activity = activity;
		this.manager = manager;
		this.ioExecutor = ioExecutor;
		this.channelId = channelId;
		this.listener = listener;
	}

	static void open(Activity activity, ChannelManager manager,
			Executor ioExecutor, byte[] channelId, byte[] ed25519,
			String nameHint, byte[] mlDsaHint, Listener listener) {
		ChannelMemberSheet sheet = new ChannelMemberSheet(activity, manager,
				ioExecutor, channelId, listener);
		ioExecutor.execute(() -> {
			ChannelMember member;
			try {
				member = ChannelMember.load(manager, channelId, ed25519,
						nameHint, mlDsaHint);
			} catch (DbException | RuntimeException e) {
				return;
			}
			ChannelShare.onUi(activity, () -> sheet.show(member));
		});
	}

	private void show(ChannelMember m) {
		View view = LayoutInflater.from(activity).inflate(
				R.layout.sheet_channel_member, null);
		String name = m.displayName(activity);
		((TextView) view.findViewById(R.id.channelMemberAvatar)).setText(
				ChannelSettingsActivity.initial(name));
		((TextView) view.findViewById(R.id.channelMemberName)).setText(name);
		((TextView) view.findViewById(R.id.channelMemberRole)).setText(
				m.role(activity));
		BottomSheetDialog dialog = ChannelSheets.create(activity, view, false);
		List<Action> actions = m.actions();
		boolean trust = actions.contains(Action.TRUST);
		row(view, dialog, R.id.channelMemberTrust,
				trust || actions.contains(Action.UNTRUST),
				() -> change(() -> manager.setSubscriberTrusted(channelId,
						m.ed25519, trust), 0,
						R.string.channels_subscribers_trust_failed));
		((TextView) view.findViewById(R.id.channelMemberTrustText)).setText(
				trust ? R.string.channels_member_trust
						: R.string.channels_member_untrust);
		boolean make = actions.contains(Action.MAKE_EDITOR);
		row(view, dialog, R.id.channelMemberEditor,
				make || actions.contains(Action.REMOVE_EDITOR),
				() -> {
					if (make) makeEditor(m);
					else removeEditor(m, name);
				});
		((TextView) view.findViewById(R.id.channelMemberEditorText)).setText(
				make ? R.string.channels_member_make_editor
						: R.string.channels_member_remove_editor);
		row(view, dialog, R.id.channelMemberRemove,
				actions.contains(Action.REMOVE), () -> remove(m, name));
		row(view, dialog, R.id.channelMemberBlock,
				actions.contains(Action.BLOCK), () -> confirm(
						activity.getString(
								R.string.channels_member_block_confirm, name),
						R.string.channels_member_block,
						() -> change(() -> manager.banSubscriber(channelId,
								m.ed25519), 0,
								R.string.channels_member_block_failed)));
		row(view, dialog, R.id.channelMemberUnblock,
				actions.contains(Action.UNBLOCK), () -> confirm(
						activity.getString(
								R.string.channels_member_unblock_confirm,
								name),
						R.string.channels_member_unblock,
						() -> change(() -> manager.unbanSubscriber(channelId,
								m.ed25519), 0,
								R.string.channels_member_unblock_failed)));
		row(view, dialog, R.id.channelMemberSafetyCode,
				actions.contains(Action.SAFETY_CODE),
				() -> showSafetyCode(m, name));
		dialog.show();
	}

	private static void row(View sheet, BottomSheetDialog dialog, int id,
			boolean shown, Runnable action) {
		View row = sheet.findViewById(id);
		row.setVisibility(shown ? View.VISIBLE : View.GONE);
		row.setOnClickListener(v -> {
			dialog.dismiss();
			action.run();
		});
	}

	private void makeEditor(ChannelMember m) {
		if (m.editorsFull) {
			toast(R.string.channels_delegations_error_full);
			return;
		}
		change(() -> manager.delegatePublisher(channelId, m.ed25519, m.mlDsa,
				0L), R.string.channels_delegations_added,
				R.string.channels_member_editor_failed);
	}

	private void removeEditor(ChannelMember m, String name) {
		confirm(activity.getString(
				R.string.channels_member_remove_editor_confirm, name),
				R.string.channels_member_remove_editor,
				() -> change(() -> manager.revokeDelegation(channelId,
						m.delegationSeq), R.string.channels_delegations_revoked,
						R.string.channels_delegations_revoke_failed));
	}

	private void remove(ChannelMember m, String name) {
		confirm(activity.getString(m.requiresApproval
						? R.string.channels_member_remove_confirm_approval
						: R.string.channels_member_remove_confirm, name),
				R.string.channels_member_remove, () -> ioExecutor.execute(() -> {
					try {
						manager.banSubscriber(channelId, m.ed25519);
					} catch (DbException | RuntimeException e) {
						ChannelShare.onUi(activity, () -> {
							toast(R.string.channels_member_block_failed);
							listener.onChanged();
						});
						return;
					}
					boolean renewed;
					try {
						manager.rotateJoinCapability(channelId);
						renewed = true;
					} catch (DbException | RuntimeException e) {
						renewed = false;
					}
					boolean finalRenewed = renewed;
					ChannelShare.onUi(activity, () -> {
						listener.onChanged();
						if (finalRenewed) {
							ChannelShare.copyInvite(activity, manager,
									ioExecutor, channelId);
						} else {
							toast(R.string.channels_invite_rotate_failed);
						}
					});
				}));
	}

	private void showSafetyCode(ChannelMember m, String name) {
		new SecureAlertDialogBuilder(activity)
				.setTitle(R.string.channels_member_safety_code)
				.setMessage(activity.getString(
						R.string.channels_member_safety_code_message, name,
						ChannelMember.safetyCode(m.ed25519, m.mlDsa)))
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	private void confirm(String message, @StringRes int action,
			Runnable onConfirm) {
		AlertDialog dialog = new SecureAlertDialogBuilder(activity)
				.setMessage(message)
				.setPositiveButton(action, (d, w) -> onConfirm.run())
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		dialog.setOnShowListener(d -> {
			Button go = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
			if (go != null) {
				go.setTextColor(ColorStateList.valueOf(resolveColorAttribute(
						activity, R.attr.zerionColorDangerText)));
			}
		});
		dialog.show();
	}

	private void change(Change change, @StringRes int done,
			@StringRes int failed) {
		ioExecutor.execute(() -> {
			boolean ok;
			try {
				change.run();
				ok = true;
			} catch (DbException | RuntimeException e) {
				ok = false;
			}
			boolean finalOk = ok;
			ChannelShare.onUi(activity, () -> {
				if (!finalOk) toast(failed);
				else if (done != 0) toast(done);
				listener.onChanged();
			});
		});
	}

	private void toast(@StringRes int text) {
		Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
	}
}
