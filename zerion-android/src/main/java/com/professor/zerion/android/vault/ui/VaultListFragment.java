package com.professor.zerion.android.vault.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import com.professor.zerion.R;
import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.util.VaultSearch;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class VaultListFragment extends BaseFragment {

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	@Inject
	@AppModule.SecurePrefs
	SharedPreferences securePrefs;

	private VaultViewModel viewModel;
	private RecyclerView recyclerView;
	private VaultAdapter adapter;
	private View emptyView;
	private FloatingActionButton fab;
	private EditText vaultSearchInput;
	private TextView vaultSortButton;
	private final List<VaultItem> allItems = new ArrayList<>();
	private String searchQuery = "";
	private int sortMode;

	private long lastClickTime = 0;
	private static final long CLICK_DEBOUNCE_TIME = 500;

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_vault_list, container, false);

		recyclerView = view.findViewById(R.id.vault_list);
		emptyView = view.findViewById(R.id.vault_empty_text);
		fab = view.findViewById(R.id.vault_fab);
		vaultSearchInput = view.findViewById(R.id.vault_search_input);
		vaultSortButton = view.findViewById(R.id.vault_sort_button);

		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.addItemDecoration(new DividerItemDecoration(
				requireContext(), DividerItemDecoration.VERTICAL));

		adapter = new VaultAdapter();
		recyclerView.setAdapter(adapter);

		return view;
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(VaultViewModel.class);

		sortMode = securePrefs.getInt("vault_sort_mode", VaultSearch.SORT_NAME);

		setupFab();
		setupAdapter();
		setupSearchAndSort();
		observeViewModel();

		viewModel.loadVaultItems();
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
		recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
		emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
		adapter.setItems(shown);
		vaultSortButton.setText(sortMode == VaultSearch.SORT_RECENT
				? R.string.vault_sort_recent : R.string.vault_sort_name);
	}

	private void setupFab() {
		fab.setOnClickListener(v -> {
			showNextFragment(SecureNoteFragment.newInstance(null));
		});
	}

	private void setupAdapter() {
		adapter.setOnItemClickListener((item, position) -> {
			long currentTime = System.currentTimeMillis();
			if (currentTime - lastClickTime < CLICK_DEBOUNCE_TIME) {
				return;
			}
			lastClickTime = currentTime;

			if (item.type == VaultItem.ItemType.NOTE) {
				adapter.setClickable(false);

				showNextFragment(SecureNoteFragment.newInstance(item.id));

				recyclerView.postDelayed(() -> {
					if (adapter != null) {
						adapter.setClickable(true);
					}
				}, 1000);
			}
		});

		adapter.setOnItemLongClickListener((item, position) -> {
			showItemOptionsDialog(item);
			return true;
		});
	}

	private void showItemOptionsDialog(VaultItem item) {
		String[] options = {"Open", "Delete"};

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(item.name)
				.setItems(options, (dialog, which) -> {
					switch (which) {
						case 0:
							if (item.type == VaultItem.ItemType.NOTE) {
								showNextFragment(SecureNoteFragment.newInstance(item.id));
							}
							break;
						case 1:
							confirmDelete(item);
							break;
					}
				})
				.show();
	}

	private void confirmDelete(VaultItem item) {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.vault_delete_confirm)
				.setMessage(R.string.vault_delete_confirm_message)
				.setPositiveButton(android.R.string.yes, (dialog, which) -> {
					viewModel.deleteItem(item.id);
				})
				.setNegativeButton(android.R.string.no, null)
				.show();
	}

	private void observeViewModel() {
		viewModel.getVaultItems().observe(getViewLifecycleOwner(), items -> {
			List<VaultItem> noteItems = new ArrayList<>();
			if (items != null) {
				for (VaultItem item : items) {
					if (item.type == VaultItem.ItemType.NOTE) {
						noteItems.add(item);
					}
				}
			}

			allItems.clear();
			allItems.addAll(noteItems);
			applyFilterAndSort();
		});
	}

	private static final int REQUEST_IMAGE_PICK = 1001;
	private static final int REQUEST_DOCUMENT_PICK = 1002;

	private void expectChildResult() {
		if (getActivity() instanceof VaultActivity) {
			((VaultActivity) getActivity()).setExpectingChildResult();
		}
	}

	private void openImagePicker() {
		android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_PICK);
		intent.setType("image/*");
		intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
		expectChildResult();
		startActivityForResult(intent, REQUEST_IMAGE_PICK);
	}

	private void openDocumentPicker() {
		android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
		intent.setType("*/*");
		intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
		expectChildResult();
		startActivityForResult(intent, REQUEST_DOCUMENT_PICK);
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (resultCode == android.app.Activity.RESULT_OK && data != null) {
			android.net.Uri uri = data.getData();
			if (uri != null) {
				if (requestCode == REQUEST_IMAGE_PICK) {
					saveImageToVault(uri);
				} else if (requestCode == REQUEST_DOCUMENT_PICK) {
					saveDocumentToVault(uri);
				}
			}
		}
	}

	private void saveImageToVault(android.net.Uri uri) {
		try {
			android.content.ContentResolver resolver = requireContext().getContentResolver();
			java.io.InputStream inputStream = resolver.openInputStream(uri);
			if (inputStream != null) {
				byte[] imageData = readInputStream(inputStream);
				inputStream.close();

				String fileName = getFileName(uri);
				String mimeType = resolver.getType(uri);

				viewModel.addMediaToVault(VaultItem.ItemType.IMAGE, fileName, imageData, mimeType);

				showToast(getString(R.string.vault_image_added));
			}
		} catch (Exception e) {
			showToast(getString(R.string.vault_image_add_failed));
		}
	}

	private void saveDocumentToVault(android.net.Uri uri) {
		try {
			android.content.ContentResolver resolver = requireContext().getContentResolver();
			java.io.InputStream inputStream = resolver.openInputStream(uri);
			if (inputStream != null) {
				byte[] documentData = readInputStream(inputStream);
				inputStream.close();

				String fileName = getFileName(uri);
				String mimeType = resolver.getType(uri);

				viewModel.addMediaToVault(VaultItem.ItemType.DOCUMENT, fileName, documentData, mimeType);

				showToast(getString(R.string.vault_document_added));
			}
		} catch (Exception e) {
			showToast(getString(R.string.vault_document_add_failed));
		}
	}

	private byte[] readInputStream(java.io.InputStream inputStream) throws java.io.IOException {
		java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
		int nRead;
		byte[] data = new byte[16384];
		while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
			buffer.write(data, 0, nRead);
		}
		buffer.flush();
		return buffer.toByteArray();
	}

	private String getFileName(android.net.Uri uri) {
		String result = null;
		if (uri.getScheme().equals("content")) {
			try (android.database.Cursor cursor = requireContext().getContentResolver().query(
					uri, null, null, null, null)) {
				if (cursor != null && cursor.moveToFirst()) {
					int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
					if (index >= 0) {
						result = cursor.getString(index);
					}
				}
			}
		}
		if (result == null) {
			result = uri.getPath();
			int cut = result.lastIndexOf('/');
			if (cut != -1) {
				result = result.substring(cut + 1);
			}
		}
		return result;
	}

	private void showToast(String message) {
		View v = getView();
		if (v != null) {
			new com.professor.zerion.android.util.ZerionSnackbarBuilder()
					.make(v, message,
							com.google.android.material.snackbar.Snackbar
									.LENGTH_SHORT)
					.show();
		} else if (getContext() != null) {
			android.widget.Toast.makeText(requireContext(), message,
					android.widget.Toast.LENGTH_SHORT).show();
		}
	}

	@Override
	public String getUniqueTag() {
		return "VaultListFragment";
	}

	private static class VaultAdapter extends RecyclerView.Adapter<VaultAdapter.ViewHolder> {

		private final List<VaultItem> items = new ArrayList<>();
		private final SimpleDateFormat dateFormat = new SimpleDateFormat(
				"MMM dd, yyyy", Locale.getDefault());

		private OnItemClickListener clickListener;
		private OnItemLongClickListener longClickListener;
		private boolean clickable = true;

		public interface OnItemClickListener {
			void onItemClick(VaultItem item, int position);
		}

		public interface OnItemLongClickListener {
			boolean onItemLongClick(VaultItem item, int position);
		}

		public void setItems(List<VaultItem> newItems) {
			items.clear();
			items.addAll(newItems);
			notifyDataSetChanged();
		}

		public void setOnItemClickListener(OnItemClickListener listener) {
			this.clickListener = listener;
		}

		public void setOnItemLongClickListener(OnItemLongClickListener listener) {
			this.longClickListener = listener;
		}

		public void setClickable(boolean clickable) {
			this.clickable = clickable;
		}

		@Override
		public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
			View view = LayoutInflater.from(parent.getContext())
					.inflate(R.layout.item_vault_entry, parent, false);
			return new ViewHolder(view);
		}

		@Override
		public void onBindViewHolder(ViewHolder holder, int position) {
			VaultItem item = items.get(position);
			holder.bind(item);

			holder.itemView.setOnClickListener(v -> {
				if (clickable && clickListener != null) {
					clickListener.onItemClick(item, position);
				}
			});

			holder.itemView.setOnLongClickListener(v -> {
				if (clickable && longClickListener != null) {
					return longClickListener.onItemLongClick(item, position);
				}
				return false;
			});
		}

		@Override
		public int getItemCount() {
			return items.size();
		}

		class ViewHolder extends RecyclerView.ViewHolder {
			private final TextView titleText;
			private final TextView subtitleText;
			private final TextView dateText;

			ViewHolder(View itemView) {
				super(itemView);
				titleText = itemView.findViewById(R.id.item_title);
				subtitleText = itemView.findViewById(R.id.item_subtitle);
				dateText = itemView.findViewById(R.id.item_date);
			}

			void bind(VaultItem item) {
				titleText.setText(item.name);
				subtitleText.setText(getTypeString(item.type));
				dateText.setText(dateFormat.format(new Date(item.modifiedTimestamp)));
			}

			private String getTypeString(VaultItem.ItemType type) {
				switch (type) {
					case NOTE:
						return itemView.getContext().getString(
								R.string.vault_type_note);
					case IMAGE:
						return itemView.getContext().getString(
								R.string.vault_type_image);
					case VIDEO:
						return itemView.getContext().getString(
								R.string.vault_type_video);
					case DOCUMENT:
						return itemView.getContext().getString(
								R.string.vault_type_document);
					case AUDIO:
						return itemView.getContext().getString(
								R.string.vault_type_audio);
					default:
						return itemView.getContext().getString(
								R.string.vault_type_file);
				}
			}
		}
	}
}