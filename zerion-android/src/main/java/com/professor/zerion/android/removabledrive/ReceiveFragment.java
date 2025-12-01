package com.professor.zerion.android.removabledrive;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.professor.zerion.R;
import com.professor.zerion.android.util.ActivityLaunchers.GetContentAdvanced;
import com.professor.zerion.android.util.ActivityLaunchers.OpenDocumentAdvanced;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static com.professor.zerion.android.AppModule.getAndroidComponent;
import static com.professor.zerion.android.util.UiUtils.hideViewOnSmallScreen;
import static com.professor.zerion.android.util.UiUtils.launchActivityToOpenFile;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ReceiveFragment extends Fragment {

	final static String TAG = ReceiveFragment.class.getName();

	private final ActivityResultLauncher<String[]> docLauncher =
			registerForActivityResult(new OpenDocumentAdvanced(),
					this::onDocumentChosen);
	private final ActivityResultLauncher<String> contentLauncher =
			registerForActivityResult(new GetContentAdvanced(),
					this::onDocumentChosen);

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private RemovableDriveViewModel viewModel;
	private Button button;
	private ProgressBar progressBar;

	private boolean checkForStateLoss = false;

	@Override
	public void onAttach(Context context) {
		super.onAttach(context);
		FragmentActivity activity = requireActivity();
		getAndroidComponent(activity).inject(this);
		viewModel = new ViewModelProvider(activity, viewModelFactory)
				.get(RemovableDriveViewModel.class);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_transfer_data_receive,
				container, false);

		progressBar = v.findViewById(R.id.progressBar);
		button = v.findViewById(R.id.fileButton);
		button.setOnClickListener(view ->
				launchActivityToOpenFile(requireContext(),
						docLauncher, contentLauncher, "*/*"));
		viewModel.getOldTaskResumedEvent()
				.observeEvent(getViewLifecycleOwner(), this::onOldTaskResumed);
		viewModel.getState()
				.observe(getViewLifecycleOwner(), this::onStateChanged);

		if (savedInstanceState != null) checkForStateLoss = true;

		return v;
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.removable_drive_title_receive);
		hideViewOnSmallScreen(requireView().findViewById(R.id.imageView));
	}

	@Override
	public void onResume() {
		super.onResume();
		if (checkForStateLoss && viewModel.hasNoState()) {
			getParentFragmentManager().popBackStack();
			if (viewModel.isAccountSignedIn()) viewModel.startReceiveData();
		}
	}

	private void onOldTaskResumed(boolean resumed) {
		if (resumed) {
			Toast.makeText(requireContext(),
					R.string.removable_drive_ongoing, LENGTH_LONG).show();
		}
	}

	private void onStateChanged(TransferDataState state) {
		if (state instanceof TransferDataState.NoDataToSend) {
			throw new IllegalStateException();
		} else if (state instanceof TransferDataState.Ready) {
			button.setEnabled(true);
		} else if (state instanceof TransferDataState.TaskAvailable) {
			button.setEnabled(false);
			progressBar.setVisibility(VISIBLE);
		}
	}

	private void onDocumentChosen(@Nullable Uri uri) {
		if (uri == null) return;
		checkForStateLoss = false;
		viewModel.importData(uri);
	}

}
