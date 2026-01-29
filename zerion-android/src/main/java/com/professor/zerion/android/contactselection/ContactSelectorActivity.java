package com.professor.zerion.android.contactselection;

import android.os.Bundle;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.sync.GroupId;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.fragment.BaseFragment.BaseFragmentListener;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

import androidx.annotation.CallSuper;
import androidx.annotation.LayoutRes;
import androidx.annotation.UiThread;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public abstract class ContactSelectorActivity
		extends ZerionActivity
		implements BaseFragmentListener, ContactSelectorListener {

	protected final static String CONTACTS = "contacts";

	protected GroupId groupId;
	protected Collection<ContactId> contacts;

	@Override
	public void onCreate(@Nullable Bundle bundle) {
		super.onCreate(bundle);

		setContentView(getLayout());
		setUpCustomToolbar(false);

		if (bundle != null) {
			byte[] groupBytes = bundle.getByteArray(GROUP_ID);
			if (groupBytes != null) groupId = new GroupId(groupBytes);
			ArrayList<Integer> intContacts =
					bundle.getIntegerArrayList(CONTACTS);
			if (intContacts != null) {
				contacts = getContactsFromIntegers(intContacts);
			}
		}
	}

	@LayoutRes
	protected int getLayout() {
		return R.layout.activity_fragment_container_toolbar;
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		if (groupId != null) {
			outState.putByteArray(GROUP_ID, groupId.getBytes());
		}
		if (contacts != null) {
			outState.putIntegerArrayList(CONTACTS,
					getContactsFromIds(contacts));
		}
	}

	@CallSuper
	@UiThread
	@Override
	public void contactsSelected(Collection<ContactId> contacts) {
		this.contacts = contacts;
	}

	static ArrayList<Integer> getContactsFromIds(
			Collection<ContactId> contacts) {
		ArrayList<Integer> intContacts = new ArrayList<>(contacts.size());
		for (ContactId contactId : contacts) {
			intContacts.add(contactId.getInt());
		}
		return intContacts;
	}

	static Collection<ContactId> getContactsFromIntegers(
			ArrayList<Integer> intContacts) {
		List<ContactId> contacts = new ArrayList<>(intContacts.size());
		for (Integer c : intContacts) {
			contacts.add(new ContactId(c));
		}
		return contacts;
	}


}
