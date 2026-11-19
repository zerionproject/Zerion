package com.professor.zerion.android.vault.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.professor.zerion.R;
import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.professor.zerion.android.util.BrowserGuard;
import com.professor.zerion.android.util.DisplayNames;
import com.professor.zerion.android.util.SecureClipboard;
import com.professor.zerion.android.util.ZerionSnackbarBuilder;
import com.professor.zerion.android.vault.model.PasswordEntry;
import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.ui.adapters.VaultBookmarksAdapter;
import com.professor.zerion.android.vault.util.BookmarkRules;
import com.professor.zerion.android.vault.util.VaultSearch;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class VaultBookmarksFragment extends BaseFragment {

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	@Inject
	@AppModule.ProfilePrefs
	SharedPreferences profilePrefs;

	private VaultViewModel viewModel;
	private RecyclerView bookmarksList;
	private LinearLayout emptyState;
	private FloatingActionButton fabAdd;
	private VaultBookmarksAdapter adapter;
	private EditText vaultSearchInput;
	private TextView vaultSortButton;
	private final List<VaultItem> allItems = new ArrayList<>();
	private final Map<String, String> urls = new HashMap<>();
	private String searchQuery = "";
	private int sortMode;

	public static VaultBookmarksFragment newInstance() {
		return new VaultBookmarksFragment();
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_vault_bookmarks,
				container, false);
		bookmarksList = view.findViewById(R.id.bookmarks_list);
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
		sortMode = profilePrefs.getInt("vault_sort_mode", VaultSearch.SORT_NAME);
		setupList();
		setupSearchAndSort();
		fabAdd.setOnClickListener(v -> showEditDialog(null, null));
		observeViewModel();
	}

	private void setupList() {
		bookmarksList.setLayoutManager(new LinearLayoutManager(requireContext()));
		adapter = new VaultBookmarksAdapter(
				new VaultBookmarksAdapter.OnBookmarkClickListener() {
					@Override
					public void onBookmarkClick(VaultItem item, String url) {
						openBookmark(url);
					}

					@Override
					public void onBookmarkOptions(VaultItem item, String url) {
						showOptions(item, url);
					}
				});
		bookmarksList.setAdapter(adapter);
	}

	private void setupSearchAndSort() {
		vaultSearchInput.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count,
					int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before,
					int count) {
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
		List<VaultItem> shown = BookmarkRules.filterSort(allItems, urls,
				searchQuery, sortMode);
		boolean isEmpty = shown.isEmpty();
		emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
		bookmarksList.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
		if (adapter != null) adapter.setItems(shown, urls);
		vaultSortButton.setText(sortMode == VaultSearch.SORT_RECENT
				? R.string.vault_sort_recent : R.string.vault_sort_name);
	}

	private void openBookmark(String url) {
		if (BookmarkRules.host(url) == null) {
			showSnackbar(getString(R.string.vault_bookmark_url_invalid));
			return;
		}
		BrowserGuard.openUrl(requireContext(), url);
	}

	private void showOptions(VaultItem item, String url) {
		String[] options = {getString(R.string.vault_bookmark_action_open),
				getString(R.string.vault_bookmark_action_edit),
				getString(R.string.vault_bookmark_action_copy),
				getString(R.string.delete)};
		new SecureAlertDialogBuilder(requireContext())
				.setTitle(DisplayNames.isolate(
						BookmarkRules.cleanTitle(item.name)))
				.setItems(options, (dialog, which) -> {
					switch (which) {
						case 0:
							openBookmark(url);
							break;
						case 1:
							loadForEdit(item);
							break;
						case 2:
							copyLink(url);
							break;
						case 3:
							confirmDelete(item);
							break;
					}
				})
				.show();
	}

	private void copyLink(String url) {
		SecureClipboard.copy(requireContext(), "link", url);
		showSnackbar(getString(R.string.browser_warning_link_copied));
	}

	private void loadForEdit(VaultItem item) {
		viewModel.getPassword(item.id, new VaultViewModel.PasswordCallback() {
			@Override
			public void onPasswordRetrieved(PasswordEntry entry) {
				if (!isAdded() || getView() == null) return;
				showEditDialog(item, entry);
			}

			@Override
			public void onError(String error) {
				if (!isAdded() || getContext() == null) return;
				showSnackbar(getString(R.string.vault_bookmark_load_failed));
			}
		});
	}

	private void confirmDelete(VaultItem item) {
		new SecureAlertDialogBuilder(requireContext())
				.setTitle(R.string.vault_bookmark_delete_title)
				.setMessage(R.string.vault_bookmark_delete_message)
				.setPositiveButton(R.string.delete, (dialog, which) -> {
					viewModel.deleteItem(item.id);
					showSnackbar(getString(R.string.vault_bookmark_deleted));
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void showEditDialog(@Nullable VaultItem existing,
			@Nullable PasswordEntry entry) {
		View dialogView = LayoutInflater.from(requireContext())
				.inflate(R.layout.dialog_add_bookmark, null);
		TextInputEditText titleInput = dialogView.findViewById(R.id.title_input);
		TextInputEditText urlInput = dialogView.findViewById(R.id.url_input);
		TextInputEditText notesInput = dialogView.findViewById(R.id.notes_input);
		if (entry != null) {
			titleInput.setText(entry.title);
			urlInput.setText(entry.url);
			notesInput.setText(entry.notes);
		}
		androidx.appcompat.app.AlertDialog dialog =
				new SecureAlertDialogBuilder(requireContext())
						.setTitle(existing == null
								? R.string.vault_bookmark_add_action
								: R.string.vault_bookmark_edit_action)
						.setView(dialogView)
						.setPositiveButton(R.string.vault_button_save, null)
						.setNegativeButton(android.R.string.cancel, null)
						.create();
		dialog.setOnShowListener(d -> dialog.getButton(
				androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
				.setOnClickListener(v -> {
					String title = BookmarkRules.cleanTitle(
							titleInput.getText() == null ? ""
									: titleInput.getText().toString());
					String url = urlInput.getText() == null ? ""
							: urlInput.getText().toString().trim();
					String notes = notesInput.getText() == null ? ""
							: notesInput.getText().toString();
					if (!BookmarkRules.validTitle(title)) {
						showSnackbar(getString(title.isEmpty()
								? R.string.vault_bookmark_title_required
								: R.string.vault_password_title_too_long));
						return;
					}
					if (BookmarkRules.host(url) == null) {
						showSnackbar(getString(
								R.string.vault_bookmark_url_invalid));
						return;
					}
					viewModel.saveBookmark(title, url, notes,
							existing == null ? null : existing.id);
					dialog.dismiss();
				}));
		dialog.show();
	}

	private void observeViewModel() {
		viewModel.getVaultItems().observe(getViewLifecycleOwner(), items -> {
			if (items == null) return;
			Map<String, String> known = viewModel.getBookmarks().getValue();
			if (known != null) {
				urls.clear();
				urls.putAll(known);
			}
			allItems.clear();
			allItems.addAll(BookmarkRules.bookmarks(items, urls.keySet()));
			applyFilterAndSort();
		});
		viewModel.getBookmarks().observe(getViewLifecycleOwner(), known -> {
			if (known == null) return;
			urls.clear();
			urls.putAll(known);
			List<VaultItem> items = viewModel.getVaultItems().getValue();
			if (items == null) return;
			allItems.clear();
			allItems.addAll(BookmarkRules.bookmarks(items, urls.keySet()));
			applyFilterAndSort();
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

	private void showSnackbar(CharSequence message) {
		View v = getView();
		if (v != null) {
			new ZerionSnackbarBuilder().make(v, message,
					com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
					.show();
		} else if (getContext() != null) {
			Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT)
					.show();
		}
	}

	@Override
	public String getUniqueTag() {
		return "VaultBookmarksFragment";
	}
}
