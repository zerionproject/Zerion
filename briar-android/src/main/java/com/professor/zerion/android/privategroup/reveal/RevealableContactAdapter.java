package com.professor.zerion.android.privategroup.reveal;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.briarproject.bramble.api.contact.ContactId;
import com.professor.zerion.R;
import com.professor.zerion.android.contact.OnContactClickListener;
import com.professor.zerion.android.contactselection.BaseContactSelectorAdapter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;

@NotNullByDefault
class RevealableContactAdapter extends
		BaseContactSelectorAdapter<RevealableContactItem, RevealableContactViewHolder> {

	RevealableContactAdapter(Context context,
			OnContactClickListener<RevealableContactItem> listener) {
		super(context, RevealableContactItem.class, listener);
	}

	@Override
	public RevealableContactViewHolder onCreateViewHolder(ViewGroup viewGroup,
			int i) {
		View v = LayoutInflater.from(ctx).inflate(
				R.layout.list_item_revealable_contact, viewGroup, false);
		return new RevealableContactViewHolder(v);
	}

	Collection<ContactId> getDisabledContactIds() {
		Collection<ContactId> disabled = new ArrayList<>();

		for (int i = 0; i < items.size(); i++) {
			RevealableContactItem item = items.get(i);
			if (item.isDisabled()) disabled.add(item.getContact().getId());
		}
		return disabled;
	}

}
