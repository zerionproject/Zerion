package com.professor.zerion.android.channel;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.briar.api.channel.ChannelInviteLink;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.api.channel.ChannelState;
import org.briarproject.briar.api.channel.event.ChannelPostReceivedEvent;
import org.briarproject.briar.api.channel.event.ChannelStateChangedEvent;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ChannelListFragment extends BaseFragment
		implements EventListener {

	public static final String TAG = ChannelListFragment.class.getName();

	public static ChannelListFragment newInstance() {
		return new ChannelListFragment();
	}

	@Inject
	ChannelManager channelManager;
	@Inject
	EventBus eventBus;
	@Inject
	@IoExecutor
	Executor ioExecutor;

	private LinearLayout listContainer;
	private TextView emptyView;

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		requireActivity().setTitle(R.string.channels_button);

		FrameLayout root = new FrameLayout(requireContext());
		root.setLayoutParams(new ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));

		ScrollView scroll = new ScrollView(requireContext());
		listContainer = new LinearLayout(requireContext());
		listContainer.setOrientation(LinearLayout.VERTICAL);
		listContainer.setPadding(0, 0, 0, dp(120));
		scroll.addView(listContainer, new ScrollView.LayoutParams(
				ScrollView.LayoutParams.MATCH_PARENT,
				ScrollView.LayoutParams.WRAP_CONTENT));
		root.addView(scroll, new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT));

		emptyView = new TextView(requireContext());
		emptyView.setText(R.string.channels_list_empty);
		emptyView.setTextSize(15);
		emptyView.setTextColor(getResources()
				.getColor(R.color.zerion_text_secondary));
		emptyView.setGravity(Gravity.CENTER);
		emptyView.setPadding(dp(32), dp(64), dp(32), dp(32));
		FrameLayout.LayoutParams emptyLp = new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.WRAP_CONTENT);
		emptyLp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
		emptyLp.topMargin = dp(96);
		root.addView(emptyView, emptyLp);

		return root;
	}

	@Override
	public void onStart() {
		super.onStart();
		eventBus.addListener(this);
		loadChannels();
	}

	@Override
	public void onStop() {
		super.onStop();
		eventBus.removeListener(this);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ChannelStateChangedEvent
				|| e instanceof ChannelPostReceivedEvent) {
			if (isAdded()) requireActivity().runOnUiThread(this::loadChannels);
		}
	}

	private void loadChannels() {
		ioExecutor.execute(() -> {
			List<ChannelState> channels;
			Map<String, Integer> unread = new HashMap<>();
			try {
				Collection<ChannelState> c = channelManager.getChannels();
				channels = new ArrayList<>(c);
				for (ChannelState s : channels) {
					unread.put(hex(s.getChannelId()),
							channelManager.getUnreadCount(s.getChannelId()));
				}
			} catch (DbException ex) {
				channels = Collections.emptyList();
			}
			List<ChannelState> finalChannels = channels;
			Map<String, Integer> finalUnread = unread;
			if (isAdded()) {
				requireActivity().runOnUiThread(
						() -> render(finalChannels, finalUnread));
			}
		});
	}

	private void render(List<ChannelState> channels,
			Map<String, Integer> unread) {
		listContainer.removeAllViews();
		if (channels.isEmpty()) {
			emptyView.setVisibility(View.VISIBLE);
		} else {
			emptyView.setVisibility(View.GONE);
			for (ChannelState s : channels) {
				int count = unread.containsKey(hex(s.getChannelId()))
						? unread.get(hex(s.getChannelId())) : 0;
				listContainer.addView(buildRow(s, count));
			}
		}
		listContainer.addView(buildActionsCluster());
	}

	private static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
		return sb.toString();
	}

	private View buildRow(ChannelState s, int unreadCount) {
		View row = LayoutInflater.from(requireContext()).inflate(
				R.layout.list_item_channel, listContainer, false);
		String name = s.getName().isEmpty()
				? getString(R.string.channels_button) : s.getName();

		TextView avatar = row.findViewById(R.id.avatarView);
		TextView nameView = row.findViewById(R.id.channelNameView);
		TextView subtitle = row.findViewById(R.id.channelSubtitleView);
		TextView unreadView = row.findViewById(R.id.channelUnreadCountView);

		avatar.setText(name.substring(0, 1).toUpperCase(Locale.ROOT));
		nameView.setText(name);

		String visibility = s.isPublicChannel()
				? getString(R.string.channels_row_public)
				: getString(R.string.channels_row_closed);
		String sub = s.weArePublisher()
				? visibility + " · "
						+ getString(R.string.channels_row_publisher_self)
				: visibility;
		subtitle.setText(sub);

		if (unreadCount > 0) {
			unreadView.setText(unreadCount > 99 ? "99+"
					: String.valueOf(unreadCount));
			unreadView.setVisibility(View.VISIBLE);
		} else {
			unreadView.setVisibility(View.GONE);
		}

		row.setOnClickListener(v -> startActivity(
				ChannelFeedActivity.intent(requireContext(),
						s.getChannelId())));
		row.setOnLongClickListener(v -> {
			showChannelMenu(s);
			return true;
		});

		View divider = new View(requireContext());
		divider.setBackgroundColor(0x14FFFFFF);
		LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				(int) (0.5f * getResources().getDisplayMetrics().density));
		dlp.leftMargin = dp(82);
		divider.setLayoutParams(dlp);

		LinearLayout wrapper = new LinearLayout(requireContext());
		wrapper.setOrientation(LinearLayout.VERTICAL);
		wrapper.addView(row);
		wrapper.addView(divider);
		return wrapper;
	}

	private View buildActionsCluster() {
		LinearLayout cluster = new LinearLayout(requireContext());
		cluster.setOrientation(LinearLayout.VERTICAL);
		cluster.setPadding(dp(16), dp(16), dp(16), dp(16));

		TextView create = new TextView(requireContext());
		create.setText(R.string.channels_action_create);
		create.setTextColor(getResources()
				.getColor(R.color.zerion_primary_accent));
		create.setTextSize(15);
		create.setPadding(0, dp(12), 0, dp(12));
		create.setOnClickListener(v -> showCreateDialog());
		cluster.addView(create);

		TextView join = new TextView(requireContext());
		join.setText(R.string.channels_action_join);
		join.setTextColor(getResources()
				.getColor(R.color.zerion_primary_accent));
		join.setTextSize(15);
		join.setPadding(0, dp(12), 0, dp(12));
		join.setOnClickListener(v -> showJoinDialog());
		cluster.addView(join);

		return cluster;
	}

	private void showCreateDialog() {
		View view = LayoutInflater.from(requireContext()).inflate(
				R.layout.dialog_create_channel, null);
		TextInputEditText nameInput =
				view.findViewById(R.id.channelCreateNameInput);
		TextInputEditText descInput =
				view.findViewById(R.id.channelCreateDescriptionInput);
		android.widget.RadioGroup group = view.findViewById(
				R.id.channelCreateVisibilityGroup);

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.channels_create_title)
				.setView(view)
				.setPositiveButton(R.string.channels_create_action,
						(d, w) -> handleCreate(nameInput, descInput, group))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void handleCreate(TextInputEditText nameInput,
			TextInputEditText descInput, android.widget.RadioGroup group) {
		String name = nameInput.getText() == null
				? "" : nameInput.getText().toString().trim();
		String desc = descInput.getText() == null
				? "" : descInput.getText().toString().trim();
		if (name.isEmpty()) {
			Toast.makeText(requireContext(),
					R.string.channels_create_error_name,
					Toast.LENGTH_SHORT).show();
			return;
		}
		boolean publicChannel = group.getCheckedRadioButtonId()
				== R.id.channelCreatePublicRadio;
		ioExecutor.execute(() -> {
			try {
				ChannelState created = channelManager.createChannel(
						name, desc, publicChannel);
				if (!isAdded()) return;
				requireActivity().runOnUiThread(() -> {
					loadChannels();
					shareInvite(created);
				});
			} catch (DbException ex) {
				if (!isAdded()) return;
				requireActivity().runOnUiThread(() ->
						Toast.makeText(requireContext(),
								R.string.channels_create_error_name,
								Toast.LENGTH_SHORT).show());
			}
		});
	}

	private void showJoinDialog() {
		View view = LayoutInflater.from(requireContext()).inflate(
				R.layout.dialog_join_channel, null);
		TextInputEditText linkInput =
				view.findViewById(R.id.channelJoinLinkInput);

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.channels_join_title)
				.setView(view)
				.setPositiveButton(R.string.channels_join_action,
						(d, w) -> handleJoin(linkInput))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void handleJoin(TextInputEditText linkInput) {
		String url = linkInput.getText() == null
				? "" : linkInput.getText().toString().trim();
		ChannelInviteLink link = channelManager.parseInviteLink(url);
		if (link == null) {
			Toast.makeText(requireContext(),
					R.string.channels_join_error_link,
					Toast.LENGTH_LONG).show();
			return;
		}
		ioExecutor.execute(() -> {
			try {
				ChannelState s = channelManager.getChannel(
						link.getChannelId());
				if (s != null) {
					if (isAdded()) {
						requireActivity().runOnUiThread(() ->
								Toast.makeText(requireContext(),
										R.string.channels_join_already_subscribed,
										Toast.LENGTH_SHORT).show());
					}
					return;
				}
				channelManager.joinChannel(link);
				if (!isAdded()) return;
				requireActivity().runOnUiThread(this::loadChannels);
			} catch (DbException ex) {
				if (!isAdded()) return;
				requireActivity().runOnUiThread(() ->
						Toast.makeText(requireContext(),
								R.string.channels_join_error_link,
								Toast.LENGTH_SHORT).show());
			}
		});
	}

	private void showChannelMenu(ChannelState s) {
		List<CharSequence> labels = new ArrayList<>();
		List<Runnable> actions = new ArrayList<>();

		labels.add(getString(R.string.channels_action_share_invite));
		actions.add(() -> shareInvite(s));

		if (s.weArePublisher() && !s.isPublicChannel()) {
			labels.add(getString(R.string.channels_action_rotate_capability));
			actions.add(() -> confirmRotate(s));
		}

		labels.add(getString(R.string.channels_action_leave));
		actions.add(() -> confirmLeave(s));

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(s.getName())
				.setItems(labels.toArray(new CharSequence[0]),
						(d, which) -> actions.get(which).run())
				.show();
	}

	private void shareInvite(ChannelState s) {
		ioExecutor.execute(() -> {
			try {
				String link = channelManager.exportInviteLink(
						s.getChannelId());
				if (!isAdded()) return;
				requireActivity().runOnUiThread(() ->
						copyAndToast(link));
			} catch (DbException ignored) {
			}
		});
	}

	private void copyAndToast(String link) {
		ClipboardManager cm = (ClipboardManager) requireContext()
				.getSystemService(Context.CLIPBOARD_SERVICE);
		cm.setPrimaryClip(ClipData.newPlainText("zerion-channel", link));
		Toast.makeText(requireContext(),
				R.string.channels_invite_copied,
				Toast.LENGTH_SHORT).show();
	}

	private void confirmRotate(ChannelState s) {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.channels_invite_rotate_confirm_title)
				.setMessage(R.string.channels_invite_rotate_confirm_message)
				.setPositiveButton(R.string.channels_action_rotate_capability,
						(d, w) -> rotate(s))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void rotate(ChannelState s) {
		ioExecutor.execute(() -> {
			try {
				channelManager.rotateJoinCapability(s.getChannelId());
				if (!isAdded()) return;
				requireActivity().runOnUiThread(() -> shareInvite(s));
			} catch (DbException ignored) {
			}
		});
	}

	private void confirmLeave(ChannelState s) {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.channels_leave_confirm_title)
				.setMessage(R.string.channels_leave_confirm_message)
				.setPositiveButton(R.string.channels_leave_confirm_action,
						(d, w) -> doLeave(s))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void doLeave(ChannelState s) {
		ioExecutor.execute(() -> {
			try {
				channelManager.leaveChannel(s.getChannelId());
				if (!isAdded()) return;
				requireActivity().runOnUiThread(this::loadChannels);
			} catch (DbException ignored) {
			}
		});
	}

	private int dp(int value) {
		return (int) (value
				* getResources().getDisplayMetrics().density);
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}
}
