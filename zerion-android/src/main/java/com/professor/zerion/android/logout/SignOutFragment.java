package com.professor.zerion.android.logout;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.professor.zerion.R;
import com.professor.zerion.android.fragment.BaseFragment;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class SignOutFragment extends BaseFragment {

	public static final String TAG = SignOutFragment.class.getName();

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_sign_out, container, false);
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}
}
