package com.professor.zerion.android.vault.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

public final class VaultProbeScreens {

	private VaultProbeScreens() {
	}

	public static class ProbeSeedScreen extends XmrRecoveryPhraseFragment {

		@Override
		public void injectFragment(ActivityComponent component) {
		}

		@Nullable
		@Override
		public View onCreateView(LayoutInflater inflater,
				@Nullable ViewGroup container, @Nullable Bundle state) {
			return new View(requireContext());
		}
	}

	public static class ProbeVaultScreen extends BaseFragment {

		@Override
		public String getUniqueTag() {
			return "ProbeVaultScreen";
		}

		@Override
		public View onCreateView(LayoutInflater inflater,
				@Nullable ViewGroup container, @Nullable Bundle state) {
			return new View(requireContext());
		}
	}

	public static class ProbeHost extends FragmentActivity
			implements BaseFragment.BaseFragmentListener {

		@Override
		public void runOnDbThread(Runnable runnable) {
		}

		@Override
		public ActivityComponent getActivityComponent() {
			return null;
		}

		@Override
		public void showNextFragment(BaseFragment f) {
		}

		@Override
		public void handleException(Exception e) {
		}
	}
}
