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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.channel.ChannelFeedActivity;
import com.professor.zerion.android.contact.add.remote.PendingContactListActivity;
import com.professor.zerion.android.conversation.ConversationActivity;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.grouptr.GroupTrConversationActivity;
import com.professor.zerion.android.util.ZerionSnackbarBuilder;
import com.professor.zerion.android.view.ZerionRecyclerView;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.UiThread;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import static com.google.android.material.snackbar.BaseTransientBottomBar.LENGTH_INDEFINITE;
import static com.professor.zerion.android.conversation.ConversationActivity.CONTACT_ID;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ChatsFragment extends BaseFragment
		implements ChatsAdapter.OnChatClickListener {

	public static final String TAG = ChatsFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private ChatsViewModel viewModel;
	private final ChatsAdapter adapter = new ChatsAdapter(this);
	private ZerionRecyclerView list;

	@Nullable
	private Snackbar snackbar = null;

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

		View contentView =
				inflater.inflate(R.layout.fragment_chats, container, false);

		list = contentView.findViewById(R.id.list);
		list.setLayoutManager(new LinearLayoutManager(requireContext()));
		list.setAdapter(adapter);
		list.setEmptyText(getString(R.string.no_chats));

		viewModel.getItems().observe(getViewLifecycleOwner(), items -> {
			if (items != null) adapter.submit(items);
		});
		viewModel.getHasPendingContacts()
				.observe(getViewLifecycleOwner(), hasPending -> {
					if (hasPending != null && hasPending) showSnackBar();
					else dismissSnackBar();
				});

		return contentView;
	}

	@Override
	public void onStart() {
		super.onStart();
		viewModel.load();
		viewModel.checkForPendingContacts();
	}

	@Override
	public void onStop() {
		super.onStop();
		dismissSnackBar();
	}

	@UiThread
	private void showSnackBar() {
		if (snackbar != null) return;
		int bottomMargin =
				(int) (72 * getResources().getDisplayMetrics().density);
		snackbar = new ZerionSnackbarBuilder()
				.setAction(R.string.show, v -> showPendingContactList())
				.setBottomMargin(bottomMargin)
				.make(requireView(),
						R.string.pending_contact_requests_snackbar,
						LENGTH_INDEFINITE);
		snackbar.show();
	}

	@UiThread
	private void dismissSnackBar() {
		if (snackbar == null) return;
		snackbar.dismiss();
		snackbar = null;
	}

	private void showPendingContactList() {
		startActivity(new Intent(requireContext(),
				PendingContactListActivity.class));
	}

	@Override
	public void onChatClick(ChatItem item) {
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
	public boolean onChatLongClick(ChatItem item, View anchor) {
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

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.set_contact_alias)
				.setView(container)
				.setPositiveButton(android.R.string.ok, (d, w) ->
						viewModel.setAlias(contactId,
								input.getText().toString()))
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void showDeleteDialog(int contactId) {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_title_delete_contact)
				.setMessage(R.string.dialog_message_delete_contact)
				.setPositiveButton(R.string.delete_contact, (d, w) -> {
					viewModel.deleteContact(contactId);
					Toast.makeText(requireContext(),
							R.string.contact_deleted_toast,
							Toast.LENGTH_SHORT).show();
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private static final int MENU_PIN = 1;
	private static final int MENU_RENAME = 2;
	private static final int MENU_DELETE = 3;
}
