package com.professor.zerion.android.contact.add.remote;

import android.content.DialogInterface.OnClickListener;
import android.os.Bundle;
import android.view.MenuItem;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.briarproject.bramble.api.contact.PendingContactId;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.util.ZerionSnackbarBuilder;
import com.professor.zerion.android.view.ZerionRecyclerView;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.Collection;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.appcompat.app.ActionBar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import static com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE;
import static org.briarproject.bramble.api.contact.PendingContactState.FAILED;
import static com.professor.zerion.android.contact.add.remote.PendingContactItem.POLL_DURATION_MS;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class PendingContactListActivity extends ZerionActivity
		implements PendingContactListener {

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private PendingContactListViewModel viewModel;
	private PendingContactListAdapter adapter;
	private ZerionRecyclerView list;
	private Snackbar offlineSnackbar;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(PendingContactListViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);

		setContentView(R.layout.activity_pending_contact_list);

		ActionBar ab = getSupportActionBar();
		if (ab != null) {
			ab.setDisplayHomeAsUpEnabled(true);
		}

		viewModel.onCreate();
		viewModel.getPendingContacts()
				.observe(this, this::onPendingContactsChanged);
		viewModel.getHasInternetConnection()
				.observe(this, this::onInternetConnectionChanged);

		adapter = new PendingContactListAdapter(this, this,
				PendingContactItem.class);
		list = findViewById(R.id.list);
		list.setEmptyText(R.string.no_pending_contacts);
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);
		list.showProgressBar();

		offlineSnackbar = new ZerionSnackbarBuilder()
				.setBackgroundColor(R.color.zerion_error_red)
				.make(list, R.string.offline_state, LENGTH_INDEFINITE);
	}

	@Override
	public void onStart() {
		super.onStart();
		list.startPeriodicUpdate(POLL_DURATION_MS);
	}

	@Override
	protected void onStop() {
		super.onStop();
		list.stopPeriodicUpdate();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onPendingContactItemRemoved(PendingContactItem item) {
		if (item.getState() == FAILED) {
			removePendingContact(item.getPendingContact().getId());
			return;
		}
		OnClickListener removeListener = (dialog, which) ->
				removePendingContact(item.getPendingContact().getId());
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
				PendingContactListActivity.this, R.style.ZerionDialogTheme);
		builder.setTitle(
				getString(R.string.dialog_title_remove_pending_contact));
		builder.setMessage(
				getString(R.string.dialog_message_remove_pending_contact));
		builder.setNegativeButton(R.string.cancel, null);
		builder.setPositiveButton(
				R.string.pending_contact_remove_action, removeListener);
		builder.setOnDismissListener(dialog -> adapter.notifyDataSetChanged());
		builder.show();
	}

	private void removePendingContact(PendingContactId id) {
		viewModel.removePendingContact(id);
	}

	private void onPendingContactsChanged(
			Collection<PendingContactItem> items) {
		if (items.isEmpty()) {
			if (adapter.isEmpty()) {
				list.showData();
			} else {
				supportFinishAfterTransition();
			}
		} else {
			adapter.setItems(items);
		}
	}

	private void onInternetConnectionChanged(boolean online) {
		if (online) offlineSnackbar.dismiss();
		else offlineSnackbar.show();
	}

}
