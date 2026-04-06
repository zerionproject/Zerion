package com.professor.zerion.android.contact.add.remote;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;

// Replaced by AddContactChooserFragment. Kept to avoid removing inject entry during transition.
@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ContactTypeSelectionFragment extends BaseFragment {

	private static final String TAG = ContactTypeSelectionFragment.class.getName();

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		return null;
	}

}
