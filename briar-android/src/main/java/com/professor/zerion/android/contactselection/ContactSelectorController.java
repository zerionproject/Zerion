package com.professor.zerion.android.contactselection;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.GroupId;
import com.professor.zerion.android.controller.DbController;
import com.professor.zerion.android.controller.handler.ResultExceptionHandler;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

@NotNullByDefault
public interface ContactSelectorController<I extends BaseSelectableContactItem>
		extends DbController {

	void loadContacts(GroupId g, Collection<ContactId> selection,
			ResultExceptionHandler<Collection<I>, DbException> handler);

}
