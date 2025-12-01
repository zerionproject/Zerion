package com.professor.zerion.android.privategroup.reveal;

import android.content.Context;
import android.os.Bundle;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.sync.GroupId;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.contact.OnContactClickListener;
import com.professor.zerion.android.contactselection.BaseContactSelectorFragment;
import com.professor.zerion.android.contactselection.ContactSelectorController;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.Collection;

import javax.inject.Inject;

import static com.professor.zerion.android.activity.ZerionActivity.GROUP_ID;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class RevealContactsFragment extends
		BaseContactSelectorFragment<RevealableContactItem, RevealableContactAdapter> {

	private final static String TAG = RevealContactsFragment.class.getName();

	@Inject
	RevealContactsController controller;

	public static RevealContactsFragment newInstance(GroupId groupId) {
		Bundle args = new Bundle();
		args.putByteArray(GROUP_ID, groupId.getBytes());
		RevealContactsFragment fragment = new RevealContactsFragment();
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	protected ContactSelectorController<RevealableContactItem> getController() {
		return controller;
	}

	@Override
	protected RevealableContactAdapter getAdapter(Context context,
			OnContactClickListener<RevealableContactItem> listener) {
		return new RevealableContactAdapter(context, listener);
	}

	@Override
	protected void onSelectionChanged() {
		Collection<ContactId> selected = adapter.getSelectedContactIds();
		Collection<ContactId> disabled = adapter.getDisabledContactIds();
		selected.removeAll(disabled);

		listener.contactsSelected(selected);
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

}
