package com.professor.zerion.android.vault.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

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
	@AppModule.SecurePrefs
	SharedPreferences securePrefs;

	private VaultViewModel viewModel;
	private RecyclerView passwordsList;
	private LinearLayout emptyState;
	private FloatingActionButton fabAdd;
	private VaultPasswordsAdapter adapter;
	private EditText vaultSearchInput;
	private TextView vaultSortButton;
	private final List<VaultItem> allItems = new ArrayList<>();
	private String searchQuery = "";
	private int sortMode;
	@Nullable
	private androidx.appcompat.app.AlertDialog passwordDialog;
	private final android.os.Handler clipboardClearHandler =
			new android.os.Handler(android.os.Looper.getMainLooper());
	@Nullable
	private Runnable pendingClipboardClear;

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

		return view;
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(VaultViewModel.class);

		sortMode = securePrefs.getInt("vault_sort_mode", VaultSearch.SORT_NAME);

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
			securePrefs.edit().putInt("vault_sort_mode", sortMode).apply();
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
		passwordDialog = new MaterialAlertDialogBuilder(requireContext())
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
		String[] options = {"View", "Delete"};

		new MaterialAlertDialogBuilder(requireContext())
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
		new MaterialAlertDialogBuilder(requireContext())
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

		new MaterialAlertDialogBuilder(requireContext())
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
		ClipboardManager clipboard = (ClipboardManager)
				requireContext().getSystemService(Context.CLIPBOARD_SERVICE);

		if (clipboard == null) {
			return;
		}

		ClipData clip = ClipData.newPlainText(label, text);
		if (android.os.Build.VERSION.SDK_INT
				>= android.os.Build.VERSION_CODES.TIRAMISU) {
			android.os.PersistableBundle extras = new android.os.PersistableBundle();
			extras.putBoolean(
					android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
			clip.getDescription().setExtras(extras);
		}
		clipboard.setPrimaryClip(clip);

		boolean clipboardClearEnabled = securePrefs.getBoolean("clipboard_clear_enabled", true);
		if (!clipboardClearEnabled) {
			return;
		}

		int clipboardTimeoutSeconds = securePrefs.getInt("clipboard_timeout", 30);
		long clipboardTimeoutMs = clipboardTimeoutSeconds * 1000L;

		if (pendingClipboardClear != null) {
			clipboardClearHandler.removeCallbacks(pendingClipboardClear);
		}
		pendingClipboardClear = () -> {
			pendingClipboardClear = null;
			try {
				if (!isAdded() || getContext() == null) {
					return;
				}

				if (clipboard.hasPrimaryClip()) {
					ClipData currentClip = clipboard.getPrimaryClip();
					if (currentClip != null && currentClip.getItemCount() > 0) {
						CharSequence clipText = currentClip.getItemAt(0).getText();
						if (clipText != null && clipText.toString().equals(text)) {
							ClipData emptyClip = ClipData.newPlainText("", "\u200B");
							clipboard.setPrimaryClip(emptyClip);

							Toast.makeText(requireContext(),
									getString(R.string.vault_clipboard_cleared),
									Toast.LENGTH_SHORT).show();
						}
					}
				}
			} catch (Exception e) {
			}
		};
		clipboardClearHandler.postDelayed(pendingClipboardClear,
				clipboardTimeoutMs);
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
				List<VaultItem> passwordItems = new ArrayList<>();
				for (VaultItem item : items) {
					if (item.type == VaultItem.ItemType.PASSWORD) {
						passwordItems.add(item);
					}
				}

				allItems.clear();
				allItems.addAll(passwordItems);
				applyFilterAndSort();
			}
		});

		viewModel.loadVaultItems();
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		if (passwordDialog != null && passwordDialog.isShowing()) {
			passwordDialog.dismiss();
		}
		passwordDialog = null;
		if (pendingClipboardClear != null) {
			clipboardClearHandler.removeCallbacks(pendingClipboardClear);
			pendingClipboardClear = null;
		}
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