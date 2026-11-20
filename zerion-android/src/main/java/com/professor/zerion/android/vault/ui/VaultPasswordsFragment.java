package com.professor.zerion.android.vault.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import com.professor.zerion.R;
import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.ui.adapters.VaultPasswordsAdapter;
import com.professor.zerion.android.vault.util.VaultSearch;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class VaultPasswordsFragment extends BaseFragment {

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	@Inject
	@AppModule.ProfilePrefs
	SharedPreferences profilePrefs;

	private VaultViewModel viewModel;
	private RecyclerView passwordsList;
	private LinearLayout emptyState;
	private FloatingActionButton fabAdd;
	private VaultPasswordsAdapter adapter;
	private EditText vaultSearchInput;
	private TextView vaultSortButton;
	private ImageButton vaultMoreButton;
	private ProgressBar importProgress;
	private final List<VaultItem> allItems = new ArrayList<>();
	private String searchQuery = "";
	private int sortMode;
	@Nullable
	private androidx.appcompat.app.AlertDialog passwordDialog;
	@Nullable
	private androidx.appcompat.app.AlertDialog importDialog;
	private boolean tearingDown = false;

	private static final int REQUEST_KEEPASS_FILE = 1101;
	private static final int REQUEST_KEEPASS_KEY_FILE = 1102;

	public static VaultPasswordsFragment newInstance() {
		return new VaultPasswordsFragment();
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_vault_passwords, container, false);

		passwordsList = view.findViewById(R.id.passwords_list);
		emptyState = view.findViewById(R.id.empty_state);
		fabAdd = view.findViewById(R.id.fab_add);
		vaultSearchInput = view.findViewById(R.id.vault_search_input);
		vaultSortButton = view.findViewById(R.id.vault_sort_button);
		vaultMoreButton = view.findViewById(R.id.vault_more_button);
		importProgress = view.findViewById(R.id.import_progress);

		return view;
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(VaultViewModel.class);

		sortMode = profilePrefs.getInt("vault_sort_mode", VaultSearch.SORT_NAME);

		setupPasswordsList();
		setupSearchAndSort();
		setupClickListeners();
		observeViewModel();
	}

	private void setupSearchAndSort() {
		vaultSearchInput.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				searchQuery = s.toString();
				applyFilterAndSort();
			}
		});

		vaultSortButton.setOnClickListener(v -> {
			sortMode = sortMode == VaultSearch.SORT_NAME
					? VaultSearch.SORT_RECENT : VaultSearch.SORT_NAME;
			profilePrefs.edit().putInt("vault_sort_mode", sortMode).apply();
			applyFilterAndSort();
		});
	}

	private void applyFilterAndSort() {
		List<VaultItem> shown = VaultSearch.filterSort(allItems, searchQuery, sortMode);
		boolean isEmpty = shown.isEmpty();
		emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
		passwordsList.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
		if (adapter != null) {
			adapter.setItems(shown);
		}
		vaultSortButton.setText(sortMode == VaultSearch.SORT_RECENT
				? R.string.vault_sort_recent : R.string.vault_sort_name);
	}

	private void setupPasswordsList() {
		passwordsList.setLayoutManager(new LinearLayoutManager(requireContext()));

		adapter = new VaultPasswordsAdapter(new VaultPasswordsAdapter.OnPasswordClickListener() {
			@Override
			public void onPasswordClick(com.professor.zerion.android.vault.model.VaultItem item) {
				showPasswordDetails(item);
			}

			@Override
			public void onPasswordLongClick(com.professor.zerion.android.vault.model.VaultItem item) {
				showPasswordOptions(item);
			}
		});
		passwordsList.setAdapter(adapter);
	}

	private void showPasswordDetails(com.professor.zerion.android.vault.model.VaultItem item) {
		viewModel.getPassword(item.id, new VaultViewModel.PasswordCallback() {
			@Override
			public void onPasswordRetrieved(com.professor.zerion.android.vault.model.PasswordEntry entry) {
				if (!isAdded() || getView() == null) {
					return;
				}
				showPasswordDialog(entry);
			}

			@Override
			public void onError(String error) {
				if (!isAdded() || getContext() == null) {
					return;
				}
				showSnackbar(error);
			}
		});
	}

	private void showPasswordDialog(com.professor.zerion.android.vault.model.PasswordEntry entry) {
		if (entry == null) {
			showSnackbar(getString(R.string.vault_password_load_failed));
			return;
		}

		View dialogView = LayoutInflater.from(getActivity())
				.inflate(R.layout.dialog_view_password, null);

		TextView titleText = dialogView.findViewById(R.id.title_text);
		TextView usernameText = dialogView.findViewById(R.id.username_text);
		TextView passwordText = dialogView.findViewById(R.id.password_text);
		TextView urlText = dialogView.findViewById(R.id.url_text);
		TextView notesText = dialogView.findViewById(R.id.notes_text);

		if (titleText != null) titleText.setText(entry.title != null ? entry.title : "");
		if (usernameText != null) usernameText.setText(entry.username != null ? entry.username : "");
		final String passwordValue = entry.password != null ? entry.password : "";
		final boolean[] passwordRevealed = { false };
		if (passwordText != null) {
			passwordText.setText(maskPassword(passwordValue));
			passwordText.setOnClickListener(v -> {
				passwordRevealed[0] = !passwordRevealed[0];
				passwordText.setText(passwordRevealed[0]
						? passwordValue : maskPassword(passwordValue));
			});
		}
		if (urlText != null) urlText.setText(entry.url != null ? entry.url : "");
		if (notesText != null) notesText.setText(entry.notes != null ? entry.notes : "");

		View copyUsernameBtn = dialogView.findViewById(R.id.copy_username);
		if (copyUsernameBtn != null) {
			copyUsernameBtn.setOnClickListener(v -> {
				if (entry.username != null && !entry.username.isEmpty()) {
					copyToClipboard("Username", entry.username);
					showSnackbar(getString(R.string.vault_password_username_copied));
				}
			});
		}

		View copyPasswordBtn = dialogView.findViewById(R.id.copy_password);
		if (copyPasswordBtn != null) {
			copyPasswordBtn.setOnClickListener(v -> {
				if (entry.password != null && !entry.password.isEmpty()) {
					copyToClipboard("Password", entry.password);
					showSnackbar(getString(R.string.vault_password_copied));
				}
			});
		}

		if (passwordDialog != null && passwordDialog.isShowing()) {
			passwordDialog.dismiss();
		}
		passwordDialog = new SecureAlertDialogBuilder(requireContext())
				.setTitle(entry.title)
				.setView(dialogView)
				.setPositiveButton(R.string.vault_button_close, null)
				.setOnDismissListener(d -> passwordDialog = null)
				.create();
		passwordDialog.getWindow().setFlags(
				android.view.WindowManager.LayoutParams.FLAG_SECURE,
				android.view.WindowManager.LayoutParams.FLAG_SECURE);
		passwordDialog.show();
	}

	private void showPasswordOptions(com.professor.zerion.android.vault.model.VaultItem item) {
		String[] options = {getString(R.string.vault_action_view),
				getString(R.string.delete)};

		new SecureAlertDialogBuilder(requireContext())
				.setTitle(item.name)
				.setItems(options, (dialog, which) -> {
					switch (which) {
						case 0:
							showPasswordDetails(item);
							break;
						case 1:
							confirmDeletePassword(item);
							break;
					}
				})
				.show();
	}

	private void confirmDeletePassword(com.professor.zerion.android.vault.model.VaultItem item) {
		new SecureAlertDialogBuilder(requireContext())
				.setTitle(R.string.vault_password_delete_title)
				.setMessage(R.string.vault_password_delete_message)
				.setPositiveButton(android.R.string.yes, (dialog, which) -> {
					viewModel.deleteItem(item.id);
					showSnackbar(getString(R.string.vault_password_deleted));
				})
				.setNegativeButton(android.R.string.no, null)
				.show();
	}

	private void setupClickListeners() {
		fabAdd.setOnClickListener(v -> showAddPasswordDialog());
		vaultMoreButton.setOnClickListener(this::showMoreMenu);
	}

	private void showMoreMenu(View anchor) {
		PopupMenu popup = new PopupMenu(requireContext(), anchor);
		popup.getMenuInflater().inflate(R.menu.vault_passwords_more,
				popup.getMenu());
		popup.setOnMenuItemClickListener(item -> {
			if (item.getItemId() == R.id.action_import_keepass) {
				pickFile(REQUEST_KEEPASS_FILE);
				return true;
			}
			return false;
		});
		popup.show();
	}

	private void pickFile(int requestCode) {
		android.content.Intent intent = new android.content.Intent(
				android.content.Intent.ACTION_OPEN_DOCUMENT);
		intent.setType("*/*");
		intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
		if (getActivity() instanceof VaultActivity) {
			((VaultActivity) getActivity()).setExpectingChildResult();
		}
		startActivityForResult(intent, requestCode);
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode,
			@Nullable android.content.Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode != REQUEST_KEEPASS_FILE
				&& requestCode != REQUEST_KEEPASS_KEY_FILE) {
			return;
		}
		if (resultCode != android.app.Activity.RESULT_OK || data == null) return;
		android.net.Uri uri = com.professor.zerion.android.util.PickedUris
				.accept(requireContext(), data.getData());
		if (uri == null) return;
		viewModel.readKeePassFile(uri, requestCode == REQUEST_KEEPASS_KEY_FILE);
	}

	private void showImportDialog(String databaseName) {
		if (importDialog != null && importDialog.isShowing()) return;
		View dialogView = LayoutInflater.from(requireContext())
				.inflate(R.layout.dialog_import_keepass, null);
		TextView fileName = dialogView.findViewById(R.id.keepass_file_name);
		TextInputEditText passwordInput =
				dialogView.findViewById(R.id.keepass_password);
		TextView keyFileButton =
				dialogView.findViewById(R.id.keepass_key_file_button);
		fileName.setText(databaseName);
		String keyFileName = viewModel.getKeePassKeyFileName().getValue();
		if (keyFileName != null) keyFileButton.setText(keyFileName);
		keyFileButton.setOnClickListener(v -> pickFile(REQUEST_KEEPASS_KEY_FILE));
		androidx.appcompat.app.AlertDialog dialog =
				new SecureAlertDialogBuilder(requireContext())
						.fitAboveKeyboard()
						.setTitle(R.string.vault_keepass_import_action)
						.setView(dialogView)
						.setPositiveButton(R.string.vault_keepass_import_button,
								null)
						.setNegativeButton(android.R.string.cancel, null)
						.setOnDismissListener(d -> {
							passwordInput.setText("");
							importDialog = null;
							if (!tearingDown) viewModel.cancelKeePassImport();
						})
						.create();
		dialog.setOnShowListener(d -> dialog.getButton(
				androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
				.setOnClickListener(v -> {
					char[] password = readChars(passwordInput);
					if (password.length == 0 && !viewModel.hasKeePassKeyFile()) {
						showSnackbar(getString(R.string.vault_keepass_need_key));
						return;
					}
					passwordInput.setText("");
					viewModel.importKeePass(password);
					dialog.dismiss();
				}));
		dialog.getWindow().setFlags(
				android.view.WindowManager.LayoutParams.FLAG_SECURE,
				android.view.WindowManager.LayoutParams.FLAG_SECURE);
		importDialog = dialog;
		dialog.show();
	}

	private static char[] readChars(EditText input) {
		android.text.Editable e = input.getText();
		if (e == null) return new char[0];
		char[] out = new char[e.length()];
		e.getChars(0, e.length(), out, 0);
		return out;
	}

	private void showAddPasswordDialog() {
		View dialogView = LayoutInflater.from(getActivity())
				.inflate(R.layout.dialog_add_password, null);

		TextInputEditText titleInput = dialogView.findViewById(R.id.title_input);
		TextInputEditText usernameInput = dialogView.findViewById(R.id.username_input);
		TextInputEditText passwordInput = dialogView.findViewById(R.id.password_input);
		TextInputEditText urlInput = dialogView.findViewById(R.id.url_input);
		TextInputEditText notesInput = dialogView.findViewById(R.id.notes_input);

		dialogView.findViewById(R.id.generate_password_button).setOnClickListener(v -> {
			String generated = generateSecurePassword();
			passwordInput.setText(generated);
			showSnackbar(getString(R.string.vault_password_generated));
		});

		new SecureAlertDialogBuilder(requireContext())
				.setTitle(R.string.vault_password_add_action)
				.setView(dialogView)
				.setPositiveButton(R.string.vault_button_save, (dialog, which) -> {
					String title = titleInput.getText() != null ?
							titleInput.getText().toString().replace("\n", " ").trim() : "";
					String username = usernameInput.getText() != null ?
							usernameInput.getText().toString().replace("\n", " ").trim() : "";
					String password = passwordInput.getText() != null ?
							passwordInput.getText().toString() : "";
					String url = urlInput.getText() != null ?
							urlInput.getText().toString().replace("\n", " ").trim() : "";
					String notes = notesInput.getText() != null ?
							notesInput.getText().toString() : "";

					if (validatePasswordEntry(title, password)) {
						savePassword(title, username, password, url, notes);
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private String generateSecurePassword() {
		char[] uppercase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
		char[] lowercase = "abcdefghijklmnopqrstuvwxyz".toCharArray();
		char[] digits = "0123456789".toCharArray();
		char[] symbols = "!@#$%^&*()-_=+[]{}|;:,.<>?".toCharArray();
		char[] allChars = new char[uppercase.length + lowercase.length
				+ digits.length + symbols.length];
		int off = 0;
		System.arraycopy(uppercase, 0, allChars, off, uppercase.length);
		off += uppercase.length;
		System.arraycopy(lowercase, 0, allChars, off, lowercase.length);
		off += lowercase.length;
		System.arraycopy(digits, 0, allChars, off, digits.length);
		off += digits.length;
		System.arraycopy(symbols, 0, allChars, off, symbols.length);

		SecureRandom random = new SecureRandom();
		int passwordLength = 20;
		char[] passwordArray = new char[passwordLength];
		passwordArray[0] = uppercase[random.nextInt(uppercase.length)];
		passwordArray[1] = lowercase[random.nextInt(lowercase.length)];
		passwordArray[2] = digits[random.nextInt(digits.length)];
		passwordArray[3] = symbols[random.nextInt(symbols.length)];
		for (int i = 4; i < passwordLength; i++) {
			passwordArray[i] = allChars[random.nextInt(allChars.length)];
		}
		for (int i = passwordArray.length - 1; i > 0; i--) {
			int j = random.nextInt(i + 1);
			char temp = passwordArray[i];
			passwordArray[i] = passwordArray[j];
			passwordArray[j] = temp;
		}
		String out = new String(passwordArray);
		java.util.Arrays.fill(passwordArray, '\0');
		java.util.Arrays.fill(allChars, '\0');
		return out;
	}

	private void copyToClipboard(String label, String text) {
		boolean clipboardClearEnabled =
				profilePrefs.getBoolean("clipboard_clear_enabled", true);
		long clearAfterMs = clipboardClearEnabled
				? profilePrefs.getInt("clipboard_timeout", 30) * 1000L
				: 0L;
		com.professor.zerion.android.util.SecureClipboard.copySensitive(
				requireContext(), label, text, clearAfterMs);
	}

	private static String maskPassword(String s) {
		if (s == null) return "";
		int n = s.length();
		StringBuilder b = new StringBuilder(n);
		for (int i = 0; i < n; i++) b.append('•');
		return b.toString();
	}

	private boolean validatePasswordEntry(String title, String password) {
		if (title == null || title.trim().isEmpty()) {
			showSnackbar(getString(R.string.vault_password_title_required));
			return false;
		}
		if (password == null || password.isEmpty()) {
			showSnackbar(getString(R.string.vault_password_required));
			return false;
		}
		if (title.length() > 100) {
			showSnackbar(getString(R.string.vault_password_title_too_long));
			return false;
		}
		return true;
	}

	private void savePassword(String title, String username, String password,
			String url, String notes) {
		viewModel.savePassword(title, username, password, url, notes);
		showSnackbar(getString(R.string.vault_password_saved));

		new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
			if (isAdded()) {
				viewModel.loadVaultItems();
			}
		}, 300);
	}

	private void observeViewModel() {
		viewModel.getVaultItems().observe(getViewLifecycleOwner(), items -> {
			if (items != null) {
				showPasswords(items);
			}
		});

		viewModel.getBookmarks().observe(getViewLifecycleOwner(), bookmarks -> {
			List<VaultItem> items = viewModel.getVaultItems().getValue();
			if (items != null) {
				showPasswords(items);
			}
		});

		viewModel.getIsLoading().observe(getViewLifecycleOwner(), loading ->
				importProgress.setVisibility(Boolean.TRUE.equals(loading)
						? View.VISIBLE : View.GONE));
		viewModel.getProgressPercent().observe(getViewLifecycleOwner(), pct -> {
			int value = pct == null ? 0 : pct;
			importProgress.setIndeterminate(value <= 0);
			if (value > 0) importProgress.setProgress(value);
		});
		viewModel.getKeePassDatabaseName().observe(getViewLifecycleOwner(),
				name -> {
					if (name != null) {
						showImportDialog(name);
					} else if (importDialog != null && importDialog.isShowing()) {
						importDialog.dismiss();
					}
				});
		viewModel.getKeePassKeyFileName().observe(getViewLifecycleOwner(),
				name -> {
					if (importDialog == null || !importDialog.isShowing()) return;
					TextView button = importDialog.findViewById(
							R.id.keepass_key_file_button);
					if (button == null) return;
					if (name != null) button.setText(name);
					else button.setText(R.string.vault_keepass_key_file);
				});
		viewModel.getSuccessMessage().observe(getViewLifecycleOwner(), msg -> {
			if (msg != null && !msg.isEmpty()) {
				showSnackbar(msg);
				viewModel.clearMessages();
			}
		});
		viewModel.getErrorMessage().observe(getViewLifecycleOwner(), msg -> {
			if (msg != null && !msg.isEmpty()) {
				showSnackbar(msg);
				viewModel.clearMessages();
			}
		});

		viewModel.loadVaultItems();
	}

	private void showPasswords(List<VaultItem> items) {
		java.util.Map<String, String> bookmarks =
				viewModel.getBookmarks().getValue();
		java.util.Set<String> bookmarkIds = bookmarks == null
				? java.util.Collections.<String>emptySet() : bookmarks.keySet();
		allItems.clear();
		allItems.addAll(com.professor.zerion.android.vault.util.BookmarkRules
				.passwords(items, bookmarkIds));
		applyFilterAndSort();
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		if (passwordDialog != null && passwordDialog.isShowing()) {
			passwordDialog.dismiss();
		}
		passwordDialog = null;
		tearingDown = true;
		if (importDialog != null && importDialog.isShowing()) {
			importDialog.dismiss();
		}
		importDialog = null;
		tearingDown = false;
	}

	private void showSnackbar(CharSequence message) {
		View v = getView();
		if (v != null) {
			new com.professor.zerion.android.util.ZerionSnackbarBuilder()
					.make(v, message,
							com.google.android.material.snackbar.Snackbar
									.LENGTH_SHORT)
					.show();
		} else if (getContext() != null) {
			Toast.makeText(requireContext(), message,
					Toast.LENGTH_SHORT).show();
		}
	}

	@Override
	public String getUniqueTag() {
		return "VaultPasswordsFragment";
	}
}