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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.professor.zerion.R;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.util.UiUtils;
import com.professor.zerion.android.vault.crypto.ProtectedNoteCodec;
import com.professor.zerion.android.vault.model.VaultItem;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;


import javax.annotation.Nullable;

import androidx.lifecycle.ViewModelProvider;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class SecureNoteFragment extends BaseFragment {

	private static final String ARG_NOTE_ID = "note_id";
	private static final String STATE_SAVE_REQUEST = "save_request";
	private static final String STATE_LEAVING_AFTER_SAVE =
			"leaving_after_save";
	private static final String STATE_OPENED_WITH_PASSWORD =
			"opened_with_password";
	private static final long NO_SAVE = 0L;

	private VaultViewModel viewModel;
	private String noteId;
	private VaultItem currentNote;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final Runnable leave = this::leaveAfterSave;

	private TextInputEditText titleInput;
	private EditText contentInput;
	private TextInputLayout titleLayout;
	private TextInputLayout notePasswordLayout;
	private TextInputEditText notePasswordInput;
	private TextInputLayout notePasswordConfirmLayout;
	private TextInputEditText notePasswordConfirmInput;
	private boolean openedWithPassword = false;
	private SwitchMaterial lockNoteSwitch;
	private ExtendedFloatingActionButton saveFab;
	private FrameLayout progressOverlay;

	private boolean hasChanges = false;
	private long saveRequest = NO_SAVE;
	private boolean leavingAfterSave = false;
	private boolean isLoading = false;
	private boolean contentLoaded = false;
	private boolean contentReady = false;
	private boolean textLostToRebuild = false;
	@Nullable
	private MenuItem saveMenuItem;
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
		if (savedInstanceState != null) {
			saveRequest = savedInstanceState.getLong(STATE_SAVE_REQUEST,
					NO_SAVE);
			leavingAfterSave = savedInstanceState.getBoolean(
					STATE_LEAVING_AFTER_SAVE, false);
			openedWithPassword = savedInstanceState.getBoolean(
					STATE_OPENED_WITH_PASSWORD, false);
		}
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putLong(STATE_SAVE_REQUEST, saveRequest);
		outState.putBoolean(STATE_LEAVING_AFTER_SAVE, leavingAfterSave);
		outState.putBoolean(STATE_OPENED_WITH_PASSWORD, openedWithPassword);
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
		notePasswordConfirmLayout =
				view.findViewById(R.id.note_password_confirm_layout);
		notePasswordConfirmInput =
				view.findViewById(R.id.note_password_confirm_input);
		lockNoteSwitch = view.findViewById(R.id.lock_note_switch);
		saveFab = view.findViewById(R.id.save_note_fab);
		progressOverlay = view.findViewById(R.id.progress_overlay);

		IncognitoInputHelper.configureForVault(titleInput);
		IncognitoInputHelper.configureForVault(contentInput);
		IncognitoInputHelper.configurePasswordField(notePasswordInput);
		IncognitoInputHelper.configurePasswordField(notePasswordConfirmInput);

		return view;
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		requireActivity().getOnBackPressedDispatcher().addCallback(
				getViewLifecycleOwner(),
				new androidx.activity.OnBackPressedCallback(true) {
					@Override
					public void handleOnBackPressed() {
						if (!onBackPressed()) {
							setEnabled(false);
							requireActivity().getOnBackPressedDispatcher()
									.onBackPressed();
							setEnabled(true);
						}
					}
				});

		viewModel = new ViewModelProvider(requireActivity())
				.get(VaultViewModel.class);

		if (noteId == null) {
			viewModel.clearMessages();
		}

		setupTextWatchers();
		setupListeners();

		if (saveRequest != NO_SAVE
				&& !viewModel.isNoteSavePending(saveRequest)) {
			saveRequest = NO_SAVE;
		}
		contentReady = noteId == null;
		textLostToRebuild = saveRequest != NO_SAVE;
		isLoading = false;
		contentLoaded = isHeldForSave();
		if (isHeldForSave()) {
			holdForSave();
		}
		observeViewModel();

		if (noteId != null && !isHeldForSave()) {
			loadNote();
		}
		showSaveAvailability();
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
			showPasswordConfirmation();
			if (!isChecked) {
				notePasswordInput.setText("");
				notePasswordConfirmInput.setText("");
			}
		});

		notePasswordConfirmInput.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				notePasswordConfirmLayout.setError(null);
			}

			@Override
			public void afterTextChanged(Editable s) {}
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
		showSaveAvailability();

		UiUtils.observeOnce(viewModel.getVaultItems(), getViewLifecycleOwner(), items -> {
			if (items != null && !items.isEmpty()) {
				for (VaultItem item : items) {
					if (item.id.equals(noteId)) {
						currentNote = item;
						String displayTitle = item.name.startsWith(
								ProtectedNoteCodec.TITLE_PREFIX)
								? item.name.substring(
										ProtectedNoteCodec.TITLE_PREFIX.length())
								: item.name;

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
			if (!isHeldForSave()) {
				progressOverlay.setVisibility(View.GONE);
				setInputsEnabled(true);
			}

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
					contentReady = true;
					loadRetryCount = 0;
				}
			} else {
				showSnackbar(getString(R.string.secure_note_load_failed));
				if (isAdded() && getActivity() != null) {
					requireActivity().getOnBackPressedDispatcher().onBackPressed();
				}
			}
			showSaveAvailability();
		});
	}

	private void promptForPassword() {
		android.widget.EditText passwordInput = new android.widget.EditText(requireContext());
		passwordInput.setHint(getString(R.string.vault_onboarding_password_hint));
		IncognitoInputHelper.configurePasswordField(passwordInput);

		new SecureAlertDialogBuilder(requireContext())
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
										openedWithPassword = true;
										lockNoteSwitch.setChecked(true);
										showPasswordConfirmation();
										contentReady = true;
										showSaveAvailability();
									}
								});
					} else {
						promptForPassword();
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
		if (!canSave()) {
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

		char[] password = null;
		if (lockNoteSwitch.isChecked()) {
			password = readChars(notePasswordInput);
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
			if (needsPasswordConfirmation()) {
				char[] confirm = readChars(notePasswordConfirmInput);
				boolean same = java.util.Arrays.equals(password, confirm);
				java.util.Arrays.fill(confirm, '\0');
				if (!same) {
					java.util.Arrays.fill(password, '\0');
					notePasswordConfirmLayout.setError(getString(
							R.string.vault_password_mismatch));
					return;
				}
			}
		}

		loadRetryCount = 0;
		saveRequest = viewModel.newNoteSaveRequest();
		holdForSave();

		if (password != null) {
			viewModel.saveNoteWithPassword(saveRequest, title, content,
					password, noteId);
		} else {
			viewModel.saveNote(saveRequest, title, content, noteId);
		}
	}

	private boolean isHeldForSave() {
		return saveRequest != NO_SAVE || leavingAfterSave;
	}

	private boolean canSave() {
		return !isHeldForSave() && !isLoading && contentReady;
	}

	private void showSaveAvailability() {
		boolean available = canSave();
		if (saveFab != null) {
			saveFab.setEnabled(available);
		}
		if (saveMenuItem != null) {
			saveMenuItem.setEnabled(available);
		}
	}

	private void holdForSave() {
		progressOverlay.setVisibility(View.VISIBLE);
		setEditingEnabled(false);
	}

	private void collectNoteSaveResult() {
		if (saveRequest == NO_SAVE) {
			return;
		}
		NoteSaveResult result = viewModel.takeNoteSaveResult(saveRequest,
				this::putBackUnsavedNote);
		if (result == null) {
			return;
		}
		saveRequest = NO_SAVE;

		if (result.isStored()) {
			hasChanges = false;
			if (result.outcome
					== NoteSaveResult.Outcome.SAVED_EARLIER_VERSION_KEPT) {
				viewModel.clearMessages();
				Toast.makeText(requireContext(), result.message,
						Toast.LENGTH_LONG).show();
			}
			closeAfterSave();
			return;
		}

		viewModel.clearMessages();
		showSnackbar(result.message, com.google.android.material.snackbar
				.Snackbar.LENGTH_LONG);
		if (result.outcome == NoteSaveResult.Outcome.WRONG_PASSWORD) {
			notePasswordLayout.setError(result.message);
		}
		boolean nothingToShow = textLostToRebuild && noteId != null;
		textLostToRebuild = false;
		progressOverlay.setVisibility(View.GONE);
		setEditingEnabled(true);
		if (nothingToShow) {
			hasChanges = false;
			contentLoaded = false;
			loadRetryCount = 0;
			loadNote();
			return;
		}
		hasChanges = true;
	}

	private void putBackUnsavedNote(char[] title, char[] content,
			boolean passwordProtected) {
		if (!textLostToRebuild) {
			return;
		}
		textLostToRebuild = false;
		removeTextWatchers();
		titleInput.setText(title, 0, title.length);
		contentInput.setText(content, 0, content.length);
		addTextWatchers();
		lockNoteSwitch.setChecked(passwordProtected);
		contentReady = true;
	}

	private void deleteNote() {
		if (isHeldForSave()) {
			return;
		}
		if (noteId == null) {
			if (isAdded() && getActivity() != null) {
				requireActivity().getOnBackPressedDispatcher().onBackPressed();
			}
			return;
		}

		new SecureAlertDialogBuilder(requireContext())
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
			if (!isHeldForSave() && !this.isLoading) {
				progressOverlay.setVisibility(isLoading ? View.VISIBLE : View.GONE);
				setInputsEnabled(!isLoading);
			}
		});

		viewModel.getErrorMessage().observe(getViewLifecycleOwner(), error -> {
			if (error != null && !error.isEmpty() && !isHeldForSave()) {
				progressOverlay.setVisibility(View.GONE);
				setInputsEnabled(true);
				showSaveAvailability();

				showSnackbar(error, com.google.android.material.snackbar.Snackbar
						.LENGTH_LONG);
			}
		});

		viewModel.getNoteSaveResult().observe(getViewLifecycleOwner(),
				finished -> collectNoteSaveResult());
	}

	private void closeAfterSave() {
		leavingAfterSave = true;
		mainHandler.removeCallbacks(leave);
		mainHandler.postDelayed(leave, 300);
	}

	private void leaveAfterSave() {
		if (isAdded() && getActivity() != null) {
			requireActivity().getOnBackPressedDispatcher().onBackPressed();
		}
	}

	private void setEditingEnabled(boolean enabled) {
		setInputsEnabled(enabled);
		lockNoteSwitch.setEnabled(enabled);
		notePasswordInput.setEnabled(enabled);
		notePasswordConfirmInput.setEnabled(enabled);
		showSaveAvailability();
	}

	private void setInputsEnabled(boolean enabled) {
		titleInput.setEnabled(enabled);
		contentInput.setEnabled(enabled);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.secure_note_menu, menu);
		super.onCreateOptionsMenu(menu, inflater);
		saveMenuItem = menu.findItem(R.id.action_save_note);
		showSaveAvailability();
	}

	@Override
	public void onPrepareOptionsMenu(Menu menu) {
		super.onPrepareOptionsMenu(menu);
		saveMenuItem = menu.findItem(R.id.action_save_note);
		showSaveAvailability();
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
				notePasswordConfirmInput.setText("");
			}
		}
	}

	private boolean needsPasswordConfirmation() {
		return lockNoteSwitch.isChecked() && !openedWithPassword;
	}

	private void showPasswordConfirmation() {
		notePasswordConfirmLayout.setVisibility(needsPasswordConfirmation()
				? View.VISIBLE : View.GONE);
	}

	@Override
	public void onResume() {
		super.onResume();

		if (leavingAfterSave) {
			mainHandler.removeCallbacks(leave);
			mainHandler.post(leave);
			return;
		}
		collectNoteSaveResult();
		if (contentLoaded || isHeldForSave()) {
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
		if (saveRequest != NO_SAVE) {
			return true;
		}

		if (hasChanges) {
			isShowingUnsavedDialog = true;
			new SecureAlertDialogBuilder(requireContext())
					.setTitle(R.string.vault_unsaved_changes_title)
					.setMessage(R.string.vault_unsaved_changes_message)
					.setPositiveButton(R.string.vault_button_save, (dialog, which) -> {
						isShowingUnsavedDialog = false;
						saveNote();
					})
					.setNegativeButton(R.string.vault_button_discard, (dialog, which) -> {
						isShowingUnsavedDialog = false;
						hasChanges = false;
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