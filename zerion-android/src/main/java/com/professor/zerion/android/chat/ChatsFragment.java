package com.professor.zerion.android.chat;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.channel.ChannelFeedActivity;
import com.professor.zerion.android.contact.request.RequestsEntry;
import com.professor.zerion.android.conversation.ConversationActivity;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.grouptr.GroupTrConversationActivity;
import com.professor.zerion.android.homelist.HomeList;
import com.professor.zerion.android.homelist.HomeListAdapter;
import com.professor.zerion.android.homelist.HomeRow;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;
import org.zerionproject.core.api.contact.event.PendingContactAddedEvent;
import org.zerionproject.core.api.contact.event.PendingContactRemovedEvent;
import org.zerionproject.core.api.contact.request.ContactRequestManager;
import org.zerionproject.core.api.contact.request.event.ContactRequestEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestsChangedEvent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.lifecycle.ViewModelProvider;

import static com.professor.zerion.android.conversation.ConversationActivity.CONTACT_ID;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ChatsFragment extends BaseFragment
		implements HomeListAdapter.Listener {

	public static final String TAG = ChatsFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;
	@Inject
	@IoExecutor
	Executor ioExecutor;
	@Inject
	ContactRequestManager contactRequestManager;
	@Inject
	EventBus eventBus;
	@Inject
	@DatabaseExecutor
	Executor dbExecutor;

	ChatsViewModel viewModel;

	@Nullable
	private RequestsEntry requestsEntry;
	private final EventListener requestEvents = e -> {
		RequestsEntry entry = requestsEntry;
		if (entry != null && (e instanceof ContactRequestEvent
				|| e instanceof ContactRequestsChangedEvent
				|| e instanceof PendingContactAddedEvent
				|| e instanceof PendingContactRemovedEvent)) {
			entry.refresh();
		}
	};

	public static ChatsFragment newInstance() {
		Bundle args = new Bundle();
		ChatsFragment fragment = new ChatsFragment();
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(ChatsViewModel.class);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		requireActivity().setTitle(R.string.chats_button);

		HomeList home = HomeList.inflate(inflater, container, this,
				ioExecutor, R.drawable.ic_chat, R.string.no_chats,
				R.string.no_contacts_action);
		View contentView = inflater.inflate(R.layout.view_chats_home,
				container, false);
		((ViewGroup) contentView.findViewById(R.id.chatsHomeList))
				.addView(home.getView());
		requestsEntry = new RequestsEntry(requireActivity(),
				contentView.findViewById(R.id.contactRequestsEntry),
				contactRequestManager, dbExecutor);

		viewModel.getItems().observe(getViewLifecycleOwner(), items -> {
			if (items != null) home.show(rows(items));
		});
		viewModel.getContactDeleted().observeEvent(getViewLifecycleOwner(),
				deleted -> Toast.makeText(requireContext(), deleted
						? R.string.contact_deleted_toast
						: R.string.contact_delete_failed,
						Toast.LENGTH_SHORT).show());

		return contentView;
	}

	@Override
	public void onStart() {
		super.onStart();
		viewModel.load();
		eventBus.addListener(requestEvents);
		RequestsEntry entry = requestsEntry;
		if (entry != null) entry.refresh();
	}

	@Override
	public void onStop() {
		super.onStop();
		eventBus.removeListener(requestEvents);
	}

	@Override
	public void onDestroyView() {
		requestsEntry = null;
		super.onDestroyView();
	}

	private List<HomeRow> rows(List<ChatItem> items) {
		List<HomeRow> rows = new ArrayList<>(items.size());
		for (ChatItem item : items) rows.add(row(item));
		return rows;
	}

	private HomeRow row(ChatItem item) {
		String key;
		String status;
		HomeRow.Presence presence = HomeRow.Presence.NONE;
		byte[] blob = item.getBlobId();
		switch (item.getType()) {
			case GROUP:
				key = "group:" + (blob == null ? ""
						: StringUtils.toHexString(blob));
				status = getString(R.string.chat_type_group);
				break;
			case CHANNEL:
				key = "channel:" + (blob == null ? ""
						: StringUtils.toHexString(blob));
				status = getString(R.string.chat_type_channel);
				break;
			default:
				key = "contact:" + item.getContactId();
				status = getString(item.isOnline()
						? R.string.online : R.string.offline);
				presence = item.isOnline() ? HomeRow.Presence.ONLINE
						: HomeRow.Presence.OFFLINE;
				break;
		}
		return HomeRow.builder(key, item.getName())
				.avatar(item.getAvatarHeader())
				.time(item.getTime())
				.preview(null, status)
				.unread(item.getUnread())
				.pinned(item.isPinned())
				.muted(item.isMuted())
				.presence(presence)
				.payload(item)
				.build();
	}

	@Override
	public void onRowClick(HomeRow row) {
		if (!(row.getPayload() instanceof ChatItem)) return;
		ChatItem item = (ChatItem) row.getPayload();
		Intent i;
		switch (item.getType()) {
			case GROUP:
				byte[] gid = item.getBlobId();
				if (gid == null) return;
				i = GroupTrConversationActivity.intent(requireActivity(), gid);
				break;
			case CHANNEL:
				byte[] cid = item.getBlobId();
				if (cid == null) return;
				i = ChannelFeedActivity.intent(requireActivity(), cid);
				break;
			default:
				i = new Intent(requireActivity(), ConversationActivity.class);
				i.putExtra(CONTACT_ID, item.getContactId());
				break;
		}
		startActivity(i);
	}

	@Override
	public boolean onRowLongClick(HomeRow row, View anchor) {
		if (!(row.getPayload() instanceof ChatItem)) return false;
		ChatItem item = (ChatItem) row.getPayload();
		if (item.getType() != ChatItem.Type.CONTACT) return false;
		int contactId = item.getContactId();

		PopupMenu popup = new PopupMenu(requireContext(), anchor);
		popup.getMenu().add(0, MENU_PIN, 0, item.isPinned()
				? R.string.unpin_conversation : R.string.pin_conversation);
		popup.getMenu().add(0, MENU_RENAME, 1, R.string.set_contact_alias);
		popup.getMenu().add(0, MENU_DELETE, 2, R.string.delete_contact);
		popup.setOnMenuItemClickListener(menuItem -> {
			int id = menuItem.getItemId();
			if (id == MENU_PIN) {
				if (!viewModel.togglePin(contactId)) {
					Toast.makeText(requireContext(),
							R.string.max_pinned_conversations,
							Toast.LENGTH_SHORT).show();
				}
				return true;
			} else if (id == MENU_RENAME) {
				showRenameDialog(contactId, item.getName());
				return true;
			} else if (id == MENU_DELETE) {
				showDeleteDialog(contactId);
				return true;
			}
			return false;
		});
		popup.show();
		return true;
	}

	private void showRenameDialog(int contactId, String currentName) {
		EditText input = new EditText(requireContext());
		input.setText(currentName);
		input.setHint(R.string.set_contact_alias_hint);
		input.setSingleLine(true);
		int pad = (int) (20 * getResources().getDisplayMetrics().density);
		FrameLayout container = new FrameLayout(requireContext());
		container.setPadding(pad, pad / 2, pad, 0);
		container.addView(input);

		new SecureAlertDialogBuilder(requireContext())
				.setTitle(R.string.set_contact_alias)
				.setView(container)
				.setPositiveButton(android.R.string.ok, (d, w) ->
						viewModel.setAlias(contactId,
								input.getText().toString()))
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void showDeleteDialog(int contactId) {
		new SecureAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_title_delete_contact)
				.setMessage(R.string.dialog_message_delete_contact)
				.setPositiveButton(R.string.delete_contact, (d, w) ->
						viewModel.deleteContact(contactId))
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private static final int MENU_PIN = 1;
	private static final int MENU_RENAME = 2;
	private static final int MENU_DELETE = 3;
}
