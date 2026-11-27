package com.professor.zerion.android.contact.request;

import android.os.Bundle;
import android.view.MenuItem;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;
import org.zerionproject.core.api.contact.request.ContactRequestManager;
import org.zerionproject.core.api.contact.request.event.ContactLinksChangedEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestsChangedEvent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;

import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.inject.Inject;


@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class InviteLinksActivity extends ZerionActivity
		implements EventListener {

	@Inject
	ContactRequestManager contactRequestManager;
	@Inject
	EventBus eventBus;
	@Inject
	@DatabaseExecutor
	Executor dbExecutor;

	@Nullable
	private InviteLinksController controller;

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
		setContentView(R.layout.activity_invite_links);
		setUpCustomToolbar(false);
		controller = new InviteLinksController(this,
				findViewById(R.id.inviteLinksRoot), contactRequestManager,
				dbExecutor);
	}

	@Override
	public void onStart() {
		super.onStart();
		eventBus.addListener(this);
		InviteLinksController c = controller;
		if (c != null) c.reload();
	}

	@Override
	protected void onStop() {
		super.onStop();
		eventBus.removeListener(this);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ContactLinksChangedEvent
				|| e instanceof ContactRequestsChangedEvent
				|| e instanceof ContactRequestEvent) {
			InviteLinksController c = controller;
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
