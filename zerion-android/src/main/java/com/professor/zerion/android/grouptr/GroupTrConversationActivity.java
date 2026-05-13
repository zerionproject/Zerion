package com.professor.zerion.android.grouptr;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.api.AndroidNotificationManager;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.util.StringUtils;
import org.briarproject.briar.api.grouptr.GroupTrManager;
import org.briarproject.briar.api.grouptr.GroupTrPost;
import org.briarproject.briar.api.grouptr.GroupTrState;
import org.briarproject.briar.api.messaging.event.GroupPostReceivedEvent;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import javax.inject.Inject;

public class GroupTrConversationActivity extends ZerionActivity
		implements EventListener {

	public static final String EXTRA_GROUP_ID = "groupTrId";

	@Inject
	GroupTrManager groupTrManager;
	@Inject
	IdentityManager identityManager;
	@Inject
	EventBus eventBus;
	@Inject
	AndroidNotificationManager notificationManager;
	@Inject
	@IoExecutor
	Executor ioExecutor;

	private byte[] groupId;
	private LinearLayout postsContainer;
	private ScrollView postsScroll;
	private EditText input;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final SimpleDateFormat tsFmt =
			new SimpleDateFormat("HH:mm", Locale.getDefault());
	@Nullable
	private byte[] localPub;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		String hex = getIntent().getStringExtra(EXTRA_GROUP_ID);
		if (hex == null) {
			finish();
			return;
		}
		try {
			groupId = StringUtils.fromHexString(hex);
		} catch (org.briarproject.bramble.api.FormatException ex) {
			finish();
			return;
		}
		buildUi();
		ioExecutor.execute(() -> {
			try {
				LocalAuthor la = identityManager.getLocalAuthor();
				localPub = la.getPublicKey().getEncoded();
				GroupTrState s = groupTrManager.getGroup(groupId);
				List<GroupTrPost> posts =
						groupTrManager.getRecentPosts(groupId);
				main.post(() -> {
					if (s != null) setTitle(s.getName());
					renderPosts(posts);
				});
			} catch (DbException ex) {
				main.post(() ->
						toast(R.string.grouptr_error_load));
			}
		});
	}

	@Override
	public void onStart() {
		super.onStart();
		eventBus.addListener(this);
		if (groupId != null) {
			notificationManager.blockGroupTrNotification(groupId);
			notificationManager.clearGroupTrPostNotification(groupId);
		}
	}

	@Override
	public void onStop() {
		super.onStop();
		eventBus.removeListener(this);
		if (groupId != null) {
			notificationManager.unblockGroupTrNotification(groupId);
		}
	}

	@Override
	public void eventOccurred(Event e) {
		if (!(e instanceof GroupPostReceivedEvent)) return;
		GroupPostReceivedEvent ev = (GroupPostReceivedEvent) e;
		if (!Arrays.equals(ev.getGroupId(), groupId)) return;
		main.post(() -> appendPost(new GroupTrPost(ev.getGroupId(),
				ev.getSenderPubKey(), ev.getSenderName(),
				ev.getCiphertext(), ev.getTimestamp(), ev.getEpoch(),
				false)));
	}

	private void buildUi() {
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setLayoutParams(new ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		setContentView(root);

		postsScroll = new ScrollView(this);
		postsContainer = new LinearLayout(this);
		postsContainer.setOrientation(LinearLayout.VERTICAL);
		int pad = (int) (16 * getResources().getDisplayMetrics().density);
		postsContainer.setPadding(pad, pad, pad, pad);
		postsScroll.addView(postsContainer);
		root.addView(postsScroll, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		LinearLayout inputRow = new LinearLayout(this);
		inputRow.setOrientation(LinearLayout.HORIZONTAL);
		inputRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
		int rowPad = (int) (12 * getResources().getDisplayMetrics().density);
		inputRow.setPadding(rowPad, rowPad / 2, rowPad / 2, rowPad);

		com.google.android.material.textfield.TextInputLayout inputWrap =
				new com.google.android.material.textfield.TextInputLayout(this);
		inputWrap.setHint(getString(R.string.grouptr_input_hint));
		input = new com.google.android.material.textfield.TextInputEditText(
				inputWrap.getContext());
		input.setInputType(InputType.TYPE_CLASS_TEXT
				| InputType.TYPE_TEXT_FLAG_MULTI_LINE);
		inputWrap.addView(input);
		inputRow.addView(inputWrap, new LinearLayout.LayoutParams(
				0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		com.google.android.material.floatingactionbutton.FloatingActionButton
				send = new com.google.android.material.floatingactionbutton
				.FloatingActionButton(this);
		send.setImageResource(android.R.drawable.ic_menu_send);
		send.setContentDescription(getString(android.R.string.ok));
		send.setSize(com.google.android.material.floatingactionbutton
				.FloatingActionButton.SIZE_MINI);
		send.setOnClickListener(v -> onSend());
		LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		sendLp.leftMargin = rowPad / 2;
		inputRow.addView(send, sendLp);

		root.addView(inputRow, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
	}

	@Override
	public boolean onCreateOptionsMenu(android.view.Menu menu) {
		menu.add(0, 1, 0, R.string.grouptr_manage_members)
				.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
		menu.add(0, 2, 0, R.string.grouptr_stealth_name_set)
				.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
		menu.add(0, 3, 0, R.string.grouptr_default_ttl_set)
				.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(android.view.MenuItem item) {
		int id = item.getItemId();
		if (id == 1) {
			startActivity(new Intent(this, GroupTrAdminActivity.class));
			return true;
		} else if (id == 2) {
			showStealthNameDialog();
			return true;
		} else if (id == 3) {
			showTtlDialog();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void renderPosts(List<GroupTrPost> posts) {
		postsContainer.removeAllViews();
		for (GroupTrPost p : posts) appendPost(p);
	}

	private void appendPost(GroupTrPost p) {
		TextView tv = new TextView(this);
		boolean mine = localPub != null
				&& Arrays.equals(p.getSenderPubKey(), localPub);
		String time = tsFmt.format(new Date(p.getTimestamp()));
		String prefix = mine
				? getString(R.string.grouptr_msg_you)
				: p.getSenderName();
		tv.setText(prefix + " · " + time + "\n" + p.getText());
		tv.setPadding(0, 8, 0, 8);
		postsContainer.addView(tv);
		postsScroll.post(() ->
				postsScroll.fullScroll(View.FOCUS_DOWN));
	}

	private void onSend() {
		String text = input.getText().toString().trim();
		if (text.isEmpty()) return;
		byte[] body = text.getBytes(StandardCharsets.UTF_8);
		input.setText("");
		ioExecutor.execute(() -> {
			try {
				groupTrManager.sendGroupPost(groupId, body, 0L);
				main.post(() -> {
					List<GroupTrPost> posts =
							groupTrManager.getRecentPosts(groupId);
					renderPosts(posts);
				});
			} catch (DbException ex) {
				main.post(() ->
						toast(R.string.grouptr_error_send));
			}
		});
	}

	private void showStealthNameDialog() {
		final EditText editor = new EditText(this);
		editor.setInputType(InputType.TYPE_CLASS_TEXT);
		ioExecutor.execute(() -> {
			try {
				String current =
						groupTrManager.getStealthName(groupId);
				main.post(() -> {
					if (current != null) editor.setText(current);
					new AlertDialog.Builder(this)
							.setTitle(R.string.grouptr_stealth_name_set)
							.setMessage(R.string.grouptr_stealth_name_msg)
							.setView(editor)
							.setPositiveButton(android.R.string.ok,
									(d, w) -> {
										String v = editor.getText()
												.toString().trim();
										persistStealth(
												v.isEmpty() ? null : v);
									})
							.setNeutralButton(R.string.grouptr_stealth_clear,
									(d, w) -> persistStealth(null))
							.setNegativeButton(android.R.string.cancel,
									null)
							.show();
				});
			} catch (DbException ex) {
				main.post(() -> toast(R.string.grouptr_error_load));
			}
		});
	}

	private void persistStealth(@Nullable String alias) {
		ioExecutor.execute(() -> {
			try {
				groupTrManager.setStealthName(groupId, alias);
			} catch (DbException ex) {
				main.post(() -> toast(R.string.grouptr_error_save));
			}
		});
	}

	private void showTtlDialog() {
		final String[] labels = {
				getString(R.string.grouptr_ttl_off),
				getString(R.string.grouptr_ttl_5min),
				getString(R.string.grouptr_ttl_1hr),
				getString(R.string.grouptr_ttl_1day),
				getString(R.string.grouptr_ttl_7days),
				getString(R.string.grouptr_ttl_30days),
		};
		final long[] values = {
				0L, 5L * 60 * 1000, 60L * 60 * 1000,
				24L * 60 * 60 * 1000, 7L * 24 * 60 * 60 * 1000,
				30L * 24L * 60 * 60 * 1000
		};
		new AlertDialog.Builder(this)
				.setTitle(R.string.grouptr_default_ttl_set)
				.setItems(labels, (d, which) -> {
					long v = values[which];
					ioExecutor.execute(() -> {
						try {
							groupTrManager.setGroupAutoDeleteTimer(
									groupId, v);
							main.post(() -> toast(
									R.string.grouptr_default_ttl_saved));
						} catch (DbException ex) {
							main.post(() -> toast(
									R.string.grouptr_error_save));
						}
					});
				})
				.show();
	}

	private void toast(int res) {
		Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
	}

	public static Intent intent(android.content.Context ctx, byte[] gid) {
		Intent i = new Intent(ctx, GroupTrConversationActivity.class);
		i.putExtra(EXTRA_GROUP_ID, StringUtils.toHexString(gid));
		return i;
	}
}
