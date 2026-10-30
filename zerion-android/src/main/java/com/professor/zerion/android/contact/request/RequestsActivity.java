package com.professor.zerion.android.contact.request;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.api.AndroidNotificationManager;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.event.ContactAddedEvent;
import org.zerionproject.core.api.contact.event.PendingContactRemovedEvent;
import org.zerionproject.core.api.contact.event.PendingContactStateChangedEvent;
import org.zerionproject.core.api.contact.request.ContactRequestManager;
import org.zerionproject.core.api.contact.request.event.ContactRequestAddedEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestsChangedEvent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.rendezvous.event.RendezvousPollEvent;

import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.inject.Inject;


@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class RequestsActivity extends ZerionActivity implements EventListener {

	public static final String EXTRA_TAB =
			"com.professor.zerion.android.contact.request.TAB";
	public static final int TAB_RECEIVED = RequestsController.TAB_RECEIVED;
	public static final int TAB_SENT = RequestsController.TAB_SENT;

	@Inject
	ContactRequestManager contactRequestManager;
	@Inject
	ContactManager contactManager;
	@Inject
	EventBus eventBus;
	@Inject
	AndroidNotificationManager notificationManager;
	@Inject
	@DatabaseExecutor
	Executor dbExecutor;

	@Nullable
	private RequestsController controller;

	public static Intent intent(Context ctx, int tab) {
		Intent i = new Intent(ctx, RequestsActivity.class);
		i.putExtra(EXTRA_TAB, tab);
		i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
				| Intent.FLAG_ACTIVITY_SINGLE_TOP);
		return i;
	}

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		getWindow().setFlags(
				android.view.WindowManager.LayoutParams.FLAG_SECURE,
				android.view.WindowManager.LayoutParams.FLAG_SECURE);
		setContentView(R.layout.activity_requests);
		setUpCustomToolbar(false);
		int tab = state != null ? state.getInt(EXTRA_TAB, TAB_RECEIVED)
				: getIntent().getIntExtra(EXTRA_TAB, TAB_RECEIVED);
		controller = new RequestsController(this,
				findViewById(R.id.requestsRoot), contactRequestManager,
				contactManager, dbExecutor, tab);
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
		RequestsController c = controller;
		if (c != null && intent.hasExtra(EXTRA_TAB)) {
			c.selectTab(intent.getIntExtra(EXTRA_TAB, TAB_RECEIVED));
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		eventBus.addListener(this);
		RequestsController c = controller;
		if (c != null) {
			c.onStart();
			c.reload();
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		notificationManager.blockContactRequestNotifications();
	}

	@Override
	public void onPause() {
		super.onPause();
		notificationManager.unblockContactRequestNotifications();
	}

	@Override
	protected void onStop() {
		super.onStop();
		eventBus.removeListener(this);
		RequestsController c = controller;
		if (c != null) {
			c.flushDeclines();
			c.onStop();
		}
	}

	@Override
	protected void onDestroy() {
		RequestsController c = controller;
		controller = null;
		if (c != null) c.destroy();
		super.onDestroy();
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		RequestsController c = controller;
		if (c != null) outState.putInt(EXTRA_TAB, c.getTab());
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ContactRequestAddedEvent) {
			ContactRequestAddedEvent a = (ContactRequestAddedEvent) e;
			RequestsController c = controller;
			if (c != null) c.onContactAdded(a.getRequestId(), a.getContactId());
		} else if (e instanceof ContactRequestEvent
				|| e instanceof ContactRequestsChangedEvent
				|| e instanceof PendingContactStateChangedEvent
				|| e instanceof PendingContactRemovedEvent
				|| e instanceof RendezvousPollEvent
				|| e instanceof ContactAddedEvent) {
			RequestsController c = controller;
			if (c != null) c.reload();
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
}
