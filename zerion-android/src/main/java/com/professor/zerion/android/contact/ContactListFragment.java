package com.professor.zerion.android.contact;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.snackbar.Snackbar;

import org.briarproject.bramble.api.contact.ContactId;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.contact.add.remote.PendingContactListActivity;
import com.professor.zerion.android.conversation.ConversationActivity;
import com.professor.zerion.android.fragment.BaseFragment;
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
public class ContactListFragment extends BaseFragment
		implements
		OnContactClickListener<ContactListItem> {

	public static final String TAG = ContactListFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private ContactListViewModel viewModel;
	private final ContactListAdapter adapter = new ContactListAdapter(this);
	private ZerionRecyclerView list;

	@Nullable
	private Snackbar snackbar = null;

	public static ContactListFragment newInstance() {
		Bundle args = new Bundle();
		ContactListFragment fragment = new ContactListFragment();
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
				.get(ContactListViewModel.class);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		requireActivity().setTitle(R.string.contact_list_button);

		View contentView = inflater.inflate(R.layout.fragment_contact_list,
				container, false);

		list = contentView.findViewById(R.id.list);
		list.setLayoutManager(new LinearLayoutManager(requireContext()));
		list.setAdapter(adapter);
		list.setEmptyImage(R.drawable.il_empty_state_contact_list);
		list.setEmptyText(getString(R.string.no_contacts));
		list.setEmptyAction(getString(R.string.no_contacts_action));

		viewModel.getContactListItems()
				.observe(getViewLifecycleOwner(), result -> {
					result.onError(this::handleException)
							.onSuccess(adapter::submitList);
				});
		viewModel.getHasPendingContacts()
				.observe(getViewLifecycleOwner(), hasPending -> {
					if (hasPending != null && hasPending) showSnackBar();
					else dismissSnackBar();
				});

		return contentView;
	}

	@Override
	public void onItemClick(View view, ContactListItem item) {
		Intent i = new Intent(requireActivity(), ConversationActivity.class);
		ContactId contactId = item.getContact().getId();
		i.putExtra(CONTACT_ID, contactId.getInt());
		startActivity(i);
	}

	@Override
	public void onItemLongClick(View view, ContactListItem item) {
		ContactId contactId = item.getContact().getId();
		PopupMenu popup = new PopupMenu(requireContext(), view);
		if (item.isPinned()) {
			popup.getMenu().add(R.string.unpin_conversation);
		} else {
			popup.getMenu().add(R.string.pin_conversation);
		}
		popup.setOnMenuItemClickListener(menuItem -> {
			if (item.isPinned()) {
				viewModel.togglePinned(contactId);
			} else {
				if (viewModel.getPinnedCount() >= PinnedContactManager.MAX_PINNED) {
					Toast.makeText(requireContext(),
							R.string.max_pinned_conversations,
							Toast.LENGTH_SHORT).show();
				} else {
					viewModel.togglePinned(contactId);
				}
			}
			return true;
		});
		popup.show();
	}

	@Override
	public void onStart() {
		super.onStart();
		viewModel.clearAllContactNotifications();
		viewModel.clearAllContactAddedNotifications();
		viewModel.loadContacts();
		viewModel.checkForPendingContacts();
		list.startPeriodicUpdate();
	}

	@Override
	public void onStop() {
		super.onStop();
		list.stopPeriodicUpdate();
		dismissSnackBar();
	}

	@UiThread
	private void showSnackBar() {
		if (snackbar != null) return;
		View v = requireView();
		int stringRes = R.string.pending_contact_requests_snackbar;
		int bottomMargin = (int) (72 * getResources().getDisplayMetrics().density);
		snackbar = new ZerionSnackbarBuilder()
				.setAction(R.string.show, view -> showPendingContactList())
				.setBottomMargin(bottomMargin)
				.make(v, stringRes, LENGTH_INDEFINITE);
		snackbar.show();
	}

	@UiThread
	private void dismissSnackBar() {
		if (snackbar == null) return;
		snackbar.dismiss();
		snackbar = null;
	}

	private void showPendingContactList() {
		Intent i = new Intent(requireContext(), PendingContactListActivity.class);
		startActivity(i);
	}
}
