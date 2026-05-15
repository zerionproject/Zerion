package com.professor.zerion.android.sharing;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;

import org.briarproject.bramble.api.connection.ConnectionRegistry;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.event.GroupRemovedEvent;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.contact.ContactItem;
import com.professor.zerion.android.view.ZerionRecyclerView;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.briar.api.identity.AuthorManager;
import org.briarproject.briar.api.sharing.event.ContactLeftShareableEvent;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.CallSuper;
import androidx.annotation.StringRes;
import androidx.recyclerview.widget.LinearLayoutManager;
@MethodsNotNullByDefault
@ParametersNotNullByDefault
abstract class SharingStatusActivity extends ZerionActivity
		implements EventListener {

	@Inject
	volatile AuthorManager authorManager;
	@Inject
	volatile ConnectionRegistry connectionRegistry;

	@Inject
	EventBus eventBus;

	private GroupId groupId;
	private ZerionRecyclerView list;
	private SharingStatusAdapter adapter;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_sharing_status);

		Intent i = getIntent();
		byte[] b = i.getByteArrayExtra(GROUP_ID);
		if (b == null) throw new IllegalStateException("No GroupId");
		groupId = new GroupId(b);

		list = findViewById(R.id.list);
		adapter = new SharingStatusAdapter(this);
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);
		list.setEmptyText(getString(R.string.nobody));

		TextView info = findViewById(R.id.info);
		info.setText(getInfoText());
	}

	@Override
	public void onStart() {
		super.onStart();
		eventBus.addListener(this);
		loadSharedWith();
	}

	@Override
	public void onStop() {
		super.onStop();
		adapter.clear();
		eventBus.removeListener(this);
		list.showProgressBar();
	}

	@Override
	@CallSuper
	public void eventOccurred(Event e) {
		if (e instanceof ContactLeftShareableEvent) {
			ContactLeftShareableEvent c = (ContactLeftShareableEvent) e;
			if (c.getGroupId().equals(getGroupId())) {
				loadSharedWith();
			}
		} else if (e instanceof GroupRemovedEvent) {
			GroupRemovedEvent g = (GroupRemovedEvent) e;
			if (g.getGroup().getId().equals(getGroupId())) {
				supportFinishAfterTransition();
			}
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@StringRes
	abstract int getInfoText();

	@DatabaseExecutor
	abstract protected Collection<Contact> getSharedWith() throws DbException;

	protected GroupId getGroupId() {
		return groupId;
	}

	protected void loadSharedWith() {
		runOnDbThread(() -> {
			try {
				List<ContactItem> contactItems = new ArrayList<>();
				for (Contact c : getSharedWith()) {
					AuthorInfo authorInfo = authorManager.getAuthorInfo(c);
					boolean online = connectionRegistry.isConnected(c.getId());
					ContactItem item = new ContactItem(c, authorInfo, online);
					contactItems.add(item);
				}
				displaySharedWith(contactItems);
			} catch (DbException e) {
			}
		});
	}

	private void displaySharedWith(List<ContactItem> contacts) {
		runOnUiThreadUnlessDestroyed(() -> {
			adapter.clear();
			if (contacts.isEmpty()) list.showData();
			else adapter.addAll(contacts);
		});
	}

}
