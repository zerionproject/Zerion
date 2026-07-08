package com.professor.zerion.android.vault.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.professor.zerion.R;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.util.UiUtils;
import com.professor.zerion.android.vault.model.VaultItem;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.Arrays;

import javax.annotation.Nullable;

import androidx.lifecycle.ViewModelProvider;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class SecureNoteFragment extends BaseFragment {

	private static final String ARG_NOTE_ID = "note_id";

	private VaultViewModel viewModel;
	private String noteId;
	private VaultItem currentNote;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	private TextInputEditText titleInput;
	private EditText contentInput;
	private TextInputLayout titleLayout;
	private TextInputLayout notePasswordLayout;
	private TextInputEditText notePasswordInput;
	private SwitchMaterial lockNoteSwitch;
	private ExtendedFloatingActionButton saveFab;
	private FrameLayout progressOverlay;

	private boolean hasChanges = false;
	@Nullable
	private char[] notePassword = null;
	private boolean isSaving = false;
	private boolean isLoading = false;
	private boolean contentLoaded = false;
	private int loadRetryCount = 0;
	private boolean isShowingUnsavedDialog = false;
	private static final int MAX_LOAD_RETRIES = 3;

	public static SecureNoteFragment newInstance(@Nullable String noteId) {
		SecureNoteFragment fragment = new SecureNoteFragment();
		Bundle args = new Bundle();
		if (noteId != null) {
			args.putString(ARG_NOTE_ID, noteId);
		}
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setHasOptionsMenu(true);

		if (getArguments() != null) {
			noteId = getArguments().getString(ARG_NOTE_ID);
		}
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_secure_note, container, false);

		titleInput = view.findViewById(R.id.note_title_input);
		contentInput = view.findViewById(R.id.note_content_input);
		titleLayout = view.findViewById(R.id.note_title_layout);
		notePasswordLayout = view.findViewById(R.id.note_password_layout);
		notePasswordInput = view.findViewById(R.id.note_password_input);
		lockNoteSwitch = view.findViewById(R.id.lock_note_switch);
		saveFab = view.findViewById(R.id.save_note_fab);
		progressOverlay = view.findViewById(R.id.progress_overlay);

		IncognitoInputHelper.configureForVault(titleInput);
		IncognitoInputHelper.configureForVault(contentInput);
		IncognitoInputHelper.configurePasswordField(notePasswordInput);

		return view;
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity())
				.get(VaultViewModel.class);

		if (noteId == null) {
			viewModel.clearMessages();
		}

		setupTextWatchers();
		setupListeners();
		observeViewModel();

		if (noteId != null) {
			loadNote();
		}
	}

	private TextWatcher titleWatcher;
	private TextWatcher contentWatcher;

	private void setupTextWatchers() {
		titleWatcher = new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				hasChanges = true;
			}

			@Override
			public void afterTextChanged(Editable s) {}
		};

		contentWatcher = new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				hasChanges = true;
			}

			@Override
			public void afterTextChanged(Editable s) {}
		};

		titleInput.addTextChangedListener(titleWatcher);
		contentInput.addTextChangedListener(contentWatcher);
	}

	private void removeTextWatchers() {
		if (titleWatcher != null) {
			titleInput.removeTextChangedListener(titleWatcher);
		}
		if (contentWatcher != null) {
			contentInput.removeTextChangedListener(contentWatcher);
		}
	}

	private void addTextWatchers() {
		if (titleWatcher != null) {
			titleInput.addTextChangedListener(titleWatcher);
		}
		if (contentWatcher != null) {
			contentInput.addTextChangedListener(contentWatcher);
		}
	}

	private void setupListeners() {
		lockNoteSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			notePasswordLayout.setVisibility(isChecked ? View.VISIBLE : View.GONE);
			if (!isChecked) {
				notePasswordInput.setText("");
				if (notePassword != null) {
					java.util.Arrays.fill(notePassword, '\0');
				}
				notePassword = null;
			}
		});

		notePasswordInput.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				notePasswordLayout.setError(null);
			}

			@Override
			public void afterTextChanged(Editable s) {}
		});

		saveFab.setOnClickListener(v -> saveNote());
	}

	private void loadNote() {
		if (isLoading) {
			return;
		}
		isLoading = true;

		progressOverlay.setVisibility(View.VISIBLE);
		setInputsEnabled(false);

		UiUtils.observeOnce(viewModel.getVaultItems(), getViewLifecycleOwner(), items -> {
			if (items != null && !items.isEmpty()) {
				for (VaultItem item : items) {
					if (item.id.equals(noteId)) {
						currentNote = item;
						String displayTitle = item.name.startsWith("🔒 ") ?
							item.name.substring(2) : item.name;

						removeTextWatchers();
						titleInput.setText(displayTitle);
						addTextWatchers();
						break;
					}
				}
			}

			loadNoteContent();
		});
	}

	private void loadNoteContent() {
		if (contentLoaded) {
			return;
		}

		UiUtils.observeOnce(viewModel.loadNoteContent(noteId), getViewLifecycleOwner(), content -> {
			isLoading = false;
			progressOverlay.setVisibility(View.GONE);
			setInputsEnabled(true);

			if (content != null) {
				if (content.equals("__PASSWORD_REQUIRED__")) {
					promptForPassword();
					contentLoaded = true;
				} else if (content.equals("__RETRY__")) {
					if (loadRetryCount < MAX_LOAD_RETRIES) {
						loadRetryCount++;
						progressOverlay.setVisibility(View.VISIBLE);
						setInputsEnabled(false);

						mainHandler.postDelayed(() -> {
							if (isAdded() && isResumed() && getView() != null) {
								loadNoteContent();
							}
						}, 500);
					} else {
						showSnackbar(getString(R.string.secure_note_vault_locked));
						if (isAdded() && getActivity() != null) {
							requireActivity().getOnBackPressedDispatcher().onBackPressed();
						}
					}
				} else {
					removeTextWatchers();
					contentInput.setText(content);
					addTextWatchers();
					hasChanges = false;
					contentLoaded = true;
					loadRetryCount = 0;
				}
			} else {
				showSnackbar(getString(R.string.secure_note_load_failed));
				if (isAdded() && getActivity() != null) {
					requireActivity().getOnBackPressedDispatcher().onBackPressed();
				}
			}
		});
	}

	private void promptForPassword() {
		android.widget.EditText passwordInput = new android.widget.EditText(requireContext());
		passwordInput.setHint(getString(R.string.vault_onboarding_password_hint));
		IncognitoInputHelper.configurePasswordField(passwordInput);

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.vault_password_required_title)
				.setMessage(R.string.secure_note_password_protected_message)
				.setView(passwordInput)
				.setPositiveButton(R.string.vault_unlock_button, (dialog, which) -> {
					char[] password = readChars(passwordInput);
					if (password.length > 0) {
						progressOverlay.setVisibility(View.VISIBLE);

						UiUtils.observeOnce(viewModel.loadPasswordProtectedNote(noteId, password),
								getViewLifecycleOwner(), content -> {
									progressOverlay.setVisibility(View.GONE);

									if (content == null) {
										showSnackbar(getString(
												R.string.vault_incorrect_password));
										promptForPassword();
									} else {
										removeTextWatchers();
										contentInput.setText(content);
										addTextWatchers();
										hasChanges = false;
										lockNoteSwitch.setChecked(true);
									}
								});
					}
				})
				.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
					if (isAdded() && getActivity() != null) {
						requireActivity().getOnBackPressedDispatcher().onBackPressed();
					}
				})
				.setCancelable(false)
				.show();
	}

	private void saveNote() {
		if (isSaving) {
			return;
		}

		String title = titleInput.getText() != null ?
				titleInput.getText().toString().trim() : "";
		String content = contentInput.getText() != null ?
				contentInput.getText().toString() : "";

		if (title.isEmpty()) {
			titleLayout.setError(getString(R.string.vault_error_title_empty));
			return;
		}

		if (lockNoteSwitch.isChecked()) {
			char[] password = readChars(notePasswordInput);
			if (password.length == 0) {
				notePasswordLayout.setError(getString(
						R.string.vault_error_password_empty));
				return;
			}
			if (password.length < 4) {
				java.util.Arrays.fill(password, '\0');
				notePasswordLayout.setError(getString(
						R.string.vault_error_password_short));
				return;
			}
			if (notePassword != null) {
				java.util.Arrays.fill(notePassword, '\0');
			}
			notePassword = password;
		}

		isSaving = true;
		contentLoaded = false;
		loadRetryCount = 0;

		progressOverlay.setVisibility(View.VISIBLE);
		setInputsEnabled(false);
		saveFab.setEnabled(false);

		if (notePassword != null) {
			viewModel.saveNoteWithPassword(title, content, notePassword, noteId);
		} else {
			viewModel.saveNote(title, content, noteId);
		}
		hasChanges = false;

		mainHandler.postDelayed(() -> {
			isSaving = false;
			saveFab.setEnabled(true);
		}, 2000);
	}

	private void deleteNote() {
		if (noteId == null) {
			if (isAdded() && getActivity() != null) {
				requireActivity().getOnBackPressedDispatcher().onBackPressed();
			}
			return;
		}

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.vault_delete_note)
				.setMessage(R.string.vault_delete_confirm_message)
				.setPositiveButton(android.R.string.yes, (dialog, which) -> {
					viewModel.deleteItem(noteId);
					if (isAdded() && getActivity() != null) {
						requireActivity().getOnBackPressedDispatcher().onBackPressed();
					}
				})
				.setNegativeButton(android.R.string.no, null)
				.show();
	}

	private void observeViewModel() {
		viewModel.getIsLoading().observe(getViewLifecycleOwner(), isLoading -> {
			if (!isSaving && !this.isLoading) {
				progressOverlay.setVisibility(isLoading ? View.VISIBLE : View.GONE);
				setInputsEnabled(!isLoading);
			}
		});

		viewModel.getErrorMessage().observe(getViewLifecycleOwner(), error -> {
			if (error != null && !error.isEmpty()) {
				isSaving = false;
				progressOverlay.setVisibility(View.GONE);
				setInputsEnabled(true);
				saveFab.setEnabled(true);

				showSnackbar(error, com.google.android.material.snackbar.Snackbar
						.LENGTH_LONG);
			}
		});

		viewModel.getSuccessMessage().observe(getViewLifecycleOwner(), success -> {
			if (success != null && success.equals("Saved")) {
				if (!isSaving) {
					return;
				}

				isSaving = false;

				mainHandler.postDelayed(() -> {
					if (isAdded() && getActivity() != null) {
						requireActivity().getOnBackPressedDispatcher().onBackPressed();
					}
				}, 300);
			}
		});
	}

	private void setInputsEnabled(boolean enabled) {
		titleInput.setEnabled(enabled);
		contentInput.setEnabled(enabled);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.secure_note_menu, menu);
		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.action_save_note) {
			saveNote();
			return true;
		} else if (item.getItemId() == R.id.action_delete_note) {
			deleteNote();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onPause() {
		super.onPause();
		mainHandler.removeCallbacksAndMessages(null);

		if (!requireActivity().isChangingConfigurations() &&
			requireActivity().isFinishing()) {
			if (titleInput != null && contentInput != null) {
				titleInput.setText("");
				contentInput.setText("");
				notePasswordInput.setText("");
			}
		}
	}

	@Override
	public void onResume() {
		super.onResume();

		if (contentLoaded) {
			return;
		}
		if (isLoading) {
			return;
		}
		if (!isAdded() || !isResumed()) {
			return;
		}

		if (noteId != null) {
			loadNote();
		}
	}

	@Override
	public void onDestroyView() {
		removeTextWatchers();
		mainHandler.removeCallbacksAndMessages(null);
		super.onDestroyView();
	}

	public boolean onBackPressed() {
		if (!isResumed() || isShowingUnsavedDialog) {
			return false;
		}

		if (hasChanges) {
			isShowingUnsavedDialog = true;
			new MaterialAlertDialogBuilder(requireContext())
					.setTitle(R.string.vault_unsaved_changes_title)
					.setMessage(R.string.vault_unsaved_changes_message)
					.setPositiveButton(R.string.vault_button_save, (dialog, which) -> {
						isShowingUnsavedDialog = false;
						saveNote();
					})
					.setNegativeButton(R.string.vault_button_discard, (dialog, which) -> {
						isShowingUnsavedDialog = false;
						if (isAdded() && getActivity() != null) {
							requireActivity().getOnBackPressedDispatcher().onBackPressed();
						}
					})
					.setNeutralButton(R.string.vault_button_cancel, (dialog, which) -> isShowingUnsavedDialog = false)
					.setOnCancelListener(dialog -> isShowingUnsavedDialog = false)
					.show();
			return true;
		}
		return false;
	}

	private void showSnackbar(CharSequence message, int duration) {
		View v = getView();
		if (v != null) {
			new com.professor.zerion.android.util.ZerionSnackbarBuilder()
					.make(v, message, duration)
					.show();
		} else if (getContext() != null) {
			Toast.makeText(requireContext(), message,
					duration == com.google.android.material.snackbar.Snackbar
							.LENGTH_LONG
							? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
		}
	}

	private void showSnackbar(CharSequence message) {
		showSnackbar(message, com.google.android.material.snackbar.Snackbar
				.LENGTH_SHORT);
	}

	@Override
	public String getUniqueTag() {
		return "SecureNoteFragment";
	}

	private static char[] readChars(android.widget.EditText input) {
		android.text.Editable e = input.getText();
		if (e == null) return new char[0];
		char[] out = new char[e.length()];
		e.getChars(0, e.length(), out, 0);
		return out;
	}
}