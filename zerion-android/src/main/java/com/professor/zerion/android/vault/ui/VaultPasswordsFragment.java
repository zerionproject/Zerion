package com.professor.zerion.android.vault.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.professor.zerion.android.vault.ui.adapters.VaultPasswordsAdapter;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.security.SecureRandom;

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

		return view;
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(VaultViewModel.class);

		setupPasswordsList();
		setupClickListeners();
		observeViewModel();
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
				Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
			}
		});
	}

	private void showPasswordDialog(com.professor.zerion.android.vault.model.PasswordEntry entry) {
		if (entry == null) {
			Toast.makeText(requireContext(), "Failed to load password entry", Toast.LENGTH_SHORT).show();
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
		if (passwordText != null) passwordText.setText(entry.password != null ? entry.password : "");
		if (urlText != null) urlText.setText(entry.url != null ? entry.url : "");
		if (notesText != null) notesText.setText(entry.notes != null ? entry.notes : "");

		View copyUsernameBtn = dialogView.findViewById(R.id.copy_username);
		if (copyUsernameBtn != null) {
			copyUsernameBtn.setOnClickListener(v -> {
				if (entry.username != null && !entry.username.isEmpty()) {
					copyToClipboard("Username", entry.username);
					Toast.makeText(requireContext(), "Username copied", Toast.LENGTH_SHORT).show();
				}
			});
		}

		View copyPasswordBtn = dialogView.findViewById(R.id.copy_password);
		if (copyPasswordBtn != null) {
			copyPasswordBtn.setOnClickListener(v -> {
				if (entry.password != null && !entry.password.isEmpty()) {
					copyToClipboard("Password", entry.password);
					Toast.makeText(requireContext(), "Password copied", Toast.LENGTH_SHORT).show();
				}
			});
		}

		androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
				.setTitle(entry.title)
				.setView(dialogView)
				.setPositiveButton("Close", null)
				.create();
		dialog.getWindow().setFlags(
				android.view.WindowManager.LayoutParams.FLAG_SECURE,
				android.view.WindowManager.LayoutParams.FLAG_SECURE);
		dialog.show();
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
				.setTitle("Delete Password")
				.setMessage("Are you sure you want to delete this password?")
				.setPositiveButton(android.R.string.yes, (dialog, which) -> {
					viewModel.deleteItem(item.id);
					Toast.makeText(requireContext(), "Password deleted", Toast.LENGTH_SHORT).show();
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
			Toast.makeText(requireContext(), "Secure password generated",
					Toast.LENGTH_SHORT).show();
		});

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle("Add Password")
				.setView(dialogView)
				.setPositiveButton("Save", (dialog, which) -> {
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
				.setNegativeButton("Cancel", null)
				.show();
	}

	private String generateSecurePassword() {
		String uppercase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
		String lowercase = "abcdefghijklmnopqrstuvwxyz";
		String digits = "0123456789";
		String symbols = "!@#$%^&*()-_=+[]{}|;:,.<>?";
		String allChars = uppercase + lowercase + digits + symbols;

		SecureRandom random = new SecureRandom();
		StringBuilder password = new StringBuilder();

		int passwordLength = 20;

		password.append(uppercase.charAt(random.nextInt(uppercase.length())));
		password.append(lowercase.charAt(random.nextInt(lowercase.length())));
		password.append(digits.charAt(random.nextInt(digits.length())));
		password.append(symbols.charAt(random.nextInt(symbols.length())));

		for (int i = 4; i < passwordLength; i++) {
			password.append(allChars.charAt(random.nextInt(allChars.length())));
		}

		char[] passwordArray = password.toString().toCharArray();
		for (int i = passwordArray.length - 1; i > 0; i--) {
			int j = random.nextInt(i + 1);
			char temp = passwordArray[i];
			passwordArray[i] = passwordArray[j];
			passwordArray[j] = temp;
		}

		return new String(passwordArray);
	}

	private void copyToClipboard(String label, String text) {
		ClipboardManager clipboard = (ClipboardManager)
				requireContext().getSystemService(Context.CLIPBOARD_SERVICE);

		if (clipboard == null) {
			return;
		}

		ClipData clip = ClipData.newPlainText(label, text);
		clipboard.setPrimaryClip(clip);

		boolean clipboardClearEnabled = securePrefs.getBoolean("clipboard_clear_enabled", true);
		if (!clipboardClearEnabled) {
			return;
		}

		int clipboardTimeoutSeconds = securePrefs.getInt("clipboard_timeout", 30);
		long clipboardTimeoutMs = clipboardTimeoutSeconds * 1000L;

		new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
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
									"Clipboard cleared for security",
									Toast.LENGTH_SHORT).show();
						}
					}
				}
			} catch (Exception e) {
			}
		}, clipboardTimeoutMs);
	}

	private boolean validatePasswordEntry(String title, String password) {
		if (title == null || title.trim().isEmpty()) {
			Toast.makeText(requireContext(), "Title is required",
					Toast.LENGTH_SHORT).show();
			return false;
		}
		if (password == null || password.isEmpty()) {
			Toast.makeText(requireContext(), "Password is required",
					Toast.LENGTH_SHORT).show();
			return false;
		}
		if (title.length() > 100) {
			Toast.makeText(requireContext(), "Title is too long (max 100 characters)",
					Toast.LENGTH_SHORT).show();
			return false;
		}
		return true;
	}

	private void savePassword(String title, String username, String password,
			String url, String notes) {
		viewModel.savePassword(title, username, password, url, notes);
		Toast.makeText(requireContext(),
				"Password saved securely",
				Toast.LENGTH_SHORT).show();

		new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
			if (isAdded()) {
				viewModel.loadVaultItems();
			}
		}, 300);
	}

	private void observeViewModel() {
		viewModel.getVaultItems().observe(getViewLifecycleOwner(), items -> {
			if (items != null) {
				java.util.List<com.professor.zerion.android.vault.model.VaultItem> passwordItems =
						new java.util.ArrayList<>();
				for (com.professor.zerion.android.vault.model.VaultItem item : items) {
					if (item.type == com.professor.zerion.android.vault.model.VaultItem.ItemType.PASSWORD) {
						passwordItems.add(item);
					}
				}

				boolean isEmpty = passwordItems.isEmpty();
				emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
				passwordsList.setVisibility(isEmpty ? View.GONE : View.VISIBLE);

				if (adapter != null) {
					adapter.setItems(passwordItems);
				}
			}
		});

		viewModel.loadVaultItems();
	}

	@Override
	public String getUniqueTag() {
		return "VaultPasswordsFragment";
	}
}