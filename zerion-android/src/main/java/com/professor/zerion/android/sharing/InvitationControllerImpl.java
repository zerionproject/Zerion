package com.professor.zerion.android.sharing;

import android.app.Activity;

import org.briarproject.bramble.api.contact.event.ContactRemovedEvent;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.event.GroupAddedEvent;
import org.briarproject.bramble.api.sync.event.GroupRemovedEvent;
import com.professor.zerion.android.controller.DbControllerImpl;
import com.professor.zerion.android.controller.handler.ResultExceptionHandler;
import org.briarproject.briar.api.sharing.InvitationItem;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.Executor;

import androidx.annotation.CallSuper;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public abstract class InvitationControllerImpl<I extends InvitationItem>
		extends DbControllerImpl
		implements InvitationController<I>, EventListener {

	private final EventBus eventBus;

	protected InvitationListener listener;

	public InvitationControllerImpl(@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager, EventBus eventBus) {
		super(dbExecutor, lifecycleManager);
		this.eventBus = eventBus;
	}

	@Override
	public void onActivityCreate(Activity activity) {
		listener = (InvitationListener) activity;
	}

	@Override
	public void onActivityStart() {
		eventBus.addListener(this);
	}

	@Override
	public void onActivityStop() {
		eventBus.removeListener(this);
	}

	@Override
	public void onActivityDestroy() {

	}

	@CallSuper
	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ContactRemovedEvent) {
			listener.loadInvitations(true);
		} else if (e instanceof GroupAddedEvent) {
			GroupAddedEvent g = (GroupAddedEvent) e;
			ClientId cId = g.getGroup().getClientId();
			if (cId.equals(getShareableClientId())) {
				listener.loadInvitations(false);
			}
		} else if (e instanceof GroupRemovedEvent) {
			GroupRemovedEvent g = (GroupRemovedEvent) e;
			ClientId cId = g.getGroup().getClientId();
			if (cId.equals(getShareableClientId())) {
				listener.loadInvitations(false);
			}
		}
	}

	protected abstract ClientId getShareableClientId();

	@Override
	public void loadInvitations(boolean clear,
			ResultExceptionHandler<Collection<I>, DbException> handler) {
		runOnDbThread(() -> {
			try {
				Collection<I> invitations = new ArrayList<>(getInvitations());
				handler.onResult(invitations);
			} catch (DbException e) {
				handler.onException(e);
			}
		});
	}

	@DatabaseExecutor
	protected abstract Collection<I> getInvitations() throws DbException;

}
