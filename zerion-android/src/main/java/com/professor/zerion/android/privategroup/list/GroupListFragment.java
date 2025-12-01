package com.professor.zerion.android.privategroup.list;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.privategroup.creation.CreateGroupActivity;
import com.professor.zerion.android.privategroup.invitation.GroupInvitationActivity;
import com.professor.zerion.android.privategroup.list.GroupViewHolder.OnGroupRemoveClickListener;
import com.professor.zerion.android.view.ZerionRecyclerView;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.UiThread;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import static java.util.Objects.requireNonNull;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class GroupListFragment extends BaseFragment implements
		OnGroupRemoveClickListener, OnClickListener {

	public final static String TAG = GroupListFragment.class.getName();

	public static GroupListFragment newInstance() {
		return new GroupListFragment();
	}

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private GroupListViewModel viewModel;
	private ZerionRecyclerView list;
	private GroupListAdapter adapter;
	private MaterialCardView invitationBanner;
	private TextView invitationText;

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(GroupListViewModel.class);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {

		requireActivity().setTitle(R.string.groups_button);

		View v = inflater.inflate(R.layout.fragment_group_list, container, false);

		// Setup list
		adapter = new GroupListAdapter(this);
		list = v.findViewById(R.id.list);
		list.setEmptyImage(R.drawable.il_empty_state_group_list);
		list.setEmptyText(R.string.groups_list_empty);
		list.setEmptyAction(R.string.groups_list_empty_action);
		list.setLayoutManager(new LinearLayoutManager(getContext()));
		list.setAdapter(adapter);
		viewModel.getGroupItems().observe(getViewLifecycleOwner(), result ->
				result.onError(this::handleException).onSuccess(items -> {
					adapter.submitList(items);
					if (requireNonNull(items).size() == 0) list.showData();
				})
		);

		// Setup FAB
		FloatingActionButton fab = v.findViewById(R.id.fab_add_group);
		fab.setOnClickListener(view -> {
			Intent i = new Intent(getContext(), CreateGroupActivity.class);
			startActivity(i);
		});

		// Setup floating invitation banner (replaces Snackbar)
		invitationBanner = v.findViewById(R.id.invitation_banner);
		invitationText = v.findViewById(R.id.invitation_text);
		invitationBanner.setOnClickListener(this);

		viewModel.getNumInvitations().observe(getViewLifecycleOwner(), num -> {
			if (num == 0) {
				invitationBanner.setVisibility(View.GONE);
			} else {
				invitationText.setText(getResources().getQuantityString(
						R.plurals.groups_invitations_open, num, num));
				invitationBanner.setVisibility(View.VISIBLE);
			}
		});

		return v;
	}

	@Override
	public void onStart() {
		super.onStart();
		viewModel.blockAllGroupMessageNotifications();
		viewModel.clearAllGroupMessageNotifications();
		viewModel.loadGroups();
		viewModel.loadNumInvitations();
		list.startPeriodicUpdate();
	}

	@Override
	public void onStop() {
		super.onStop();
		list.stopPeriodicUpdate();
		viewModel.unblockAllGroupMessageNotifications();
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.groups_list_actions, menu);
		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.action_add_group) {
			Intent i = new Intent(getContext(), CreateGroupActivity.class);
			startActivity(i);
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@UiThread
	@Override
	public void onGroupRemoveClick(GroupItem item) {
		viewModel.removeGroup(item.getId());
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	public void onClick(View v) {
		Intent i = new Intent(getContext(), GroupInvitationActivity.class);
		startActivity(i);
	}

}
