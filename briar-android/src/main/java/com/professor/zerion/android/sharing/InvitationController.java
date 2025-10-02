package com.professor.zerion.android.sharing;

import org.briarproject.bramble.api.db.DbException;
import com.professor.zerion.android.controller.ActivityLifecycleController;
import com.professor.zerion.android.controller.handler.ExceptionHandler;
import com.professor.zerion.android.controller.handler.ResultExceptionHandler;
import org.briarproject.briar.api.sharing.InvitationItem;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

@NotNullByDefault
public interface InvitationController<I extends InvitationItem>
		extends ActivityLifecycleController {

	void loadInvitations(boolean clear,
			ResultExceptionHandler<Collection<I>, DbException> handler);

	void respondToInvitation(I item, boolean accept,
			ExceptionHandler<DbException> handler);

	interface InvitationListener {

		void loadInvitations(boolean clear);

	}

}
