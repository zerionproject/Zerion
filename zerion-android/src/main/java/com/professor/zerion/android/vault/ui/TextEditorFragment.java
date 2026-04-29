package com.professor.zerion.android.vault.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputLayout;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class TextEditorFragment extends BaseFragment {

	private static final String ARG_INITIAL_NAME = "initial_name";
	private static final String ARG_INITIAL_CONTENT = "initial_content";
	private static final String ARG_IS_EDIT_MODE = "is_edit_mode";
	private static final String ARG_ITEM_ID = "item_id";

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private VaultViewModel viewModel;
	private MaterialToolbar toolbar;
	private TextInputLayout documentNameLayout;
	private EditText documentNameInput;
	private EditText documentContentInput;
	private TextView wordCount;
	private MaterialButton toggleMonospace;
	private com.google.android.material.floatingactionbutton.FloatingActionButton fabSave;

	private String initialName;
	private String initialContent;
	private boolean isEditMode = false;
	private String itemId;
	private boolean hasUnsavedChanges = false;
	private boolean isMonospace = true;

	public static TextEditorFragment newInstance() {
		return new TextEditorFragment();
	}

	public static TextEditorFragment newInstance(String name, String content) {
		TextEditorFragment fragment = new TextEditorFragment();
		Bundle args = new Bundle();
		args.putString(ARG_INITIAL_NAME, name);
		args.putString(ARG_INITIAL_CONTENT, content);
		fragment.setArguments(args);
		return fragment;
	}

	public static TextEditorFragment newInstanceForEdit(String itemId, String name, String content) {
		TextEditorFragment fragment = new TextEditorFragment();
		Bundle args = new Bundle();
		args.putString(ARG_ITEM_ID, itemId);
		args.putString(ARG_INITIAL_NAME, name);
		args.putString(ARG_INITIAL_CONTENT, content);
		args.putBoolean(ARG_IS_EDIT_MODE, true);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setHasOptionsMenu(true);

		if (getArguments() != null) {
			initialName = getArguments().getString(ARG_INITIAL_NAME);
			initialContent = getArguments().getString(ARG_INITIAL_CONTENT);
			isEditMode = getArguments().getBoolean(ARG_IS_EDIT_MODE, false);
			itemId = getArguments().getString(ARG_ITEM_ID);
		}

		requireActivity().getOnBackPressedDispatcher().addCallback(this,
				new OnBackPressedCallback(true) {
					@Override
					public void handleOnBackPressed() {
						if (hasUnsavedChanges) {
							showUnsavedChangesDialog();
						} else {
							setEnabled(false);
							requireActivity().getOnBackPressedDispatcher().onBackPressed();
						}
					}
				});
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_text_editor, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(VaultViewModel.class);

		toolbar = view.findViewById(R.id.toolbar);
		documentNameLayout = view.findViewById(R.id.document_name_layout);
		documentNameInput = view.findViewById(R.id.document_name_input);
		documentContentInput = view.findViewById(R.id.document_content_input);
		wordCount = view.findViewById(R.id.word_count);
		toggleMonospace = view.findViewById(R.id.toggle_monospace);
		fabSave = view.findViewById(R.id.fab_save);

		setupToolbar();
		setupEditorFeatures();
		loadInitialContent();
	}

	private void setupToolbar() {
		((AppCompatActivity) requireActivity()).setSupportActionBar(toolbar);
		ActionBar actionBar = ((AppCompatActivity) requireActivity()).getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(true);
			actionBar.setTitle(isEditMode ? "Edit Document" : "New Document");
		}

		toolbar.setNavigationOnClickListener(v -> {
			if (hasUnsavedChanges) {
				showUnsavedChangesDialog();
			} else if (getActivity() != null) {
				getActivity().getOnBackPressedDispatcher().onBackPressed();
			}
		});
	}

	private void setupEditorFeatures() {
		TextWatcher textWatcher = new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				hasUnsavedChanges = true;
				updateWordCount();
			}

			@Override
			public void afterTextChanged(Editable s) {}
		};

		documentNameInput.addTextChangedListener(textWatcher);
		documentContentInput.addTextChangedListener(textWatcher);

		updateMonospaceState();
		toggleMonospace.setOnClickListener(v -> {
			isMonospace = !isMonospace;
			updateMonospaceState();
			v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
		});

		fabSave.setOnClickListener(v -> {
			saveDocument();
			v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
		});

		updateWordCount();
	}

	private void updateMonospaceState() {
		if (isMonospace) {
			documentContentInput.setTypeface(android.graphics.Typeface.MONOSPACE);
			toggleMonospace.setTextColor(getResources().getColor(R.color.zerion_cyan, null));
		} else {
			documentContentInput.setTypeface(android.graphics.Typeface.DEFAULT);
			toggleMonospace.setTextColor(getResources().getColor(R.color.zerion_text_secondary, null));
		}
	}

	private void updateWordCount() {
		String content = documentContentInput.getText().toString().trim();
		int count = content.isEmpty() ? 0 : content.split("\\s+").length;
		wordCount.setText(getResources().getQuantityString(
				R.plurals.text_editor_word_count, count, count));
	}

	private void loadInitialContent() {
		if (initialName != null) {
			documentNameInput.setText(initialName);
		}
		if (initialContent != null) {
			documentContentInput.setText(initialContent);
		}
		hasUnsavedChanges = false;
	}

	@Override
	public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
		super.onCreateOptionsMenu(menu, inflater);
		inflater.inflate(R.menu.text_editor_menu, menu);
	}

	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item) {
		if (item.getItemId() == R.id.action_save) {
			saveDocument();
			return true;
		} else if (item.getItemId() == R.id.action_save_with_password) {
			saveDocumentWithPassword();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void saveDocument() {
		String name = documentNameInput.getText().toString().trim();
		String content = documentContentInput.getText().toString();

		if (name.isEmpty()) {
			documentNameLayout.setError("Document name is required");
			documentNameInput.requestFocus();
			return;
		}

		documentNameLayout.setError(null);

		if (!name.toLowerCase().endsWith(".txt")) {
			name = name + ".txt";
		}

		byte[] contentBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);

		if (isEditMode && itemId != null) {
			viewModel.updateDocument(itemId, name, contentBytes);
			Toast.makeText(requireContext(), "Document updated", Toast.LENGTH_SHORT).show();
		} else {
			viewModel.addDocumentWithPassword(name, contentBytes, null);
			Toast.makeText(requireContext(), "Document saved", Toast.LENGTH_SHORT).show();
		}

		hasUnsavedChanges = false;

		if (getActivity() != null) {
			getActivity().getOnBackPressedDispatcher().onBackPressed();
		}
	}

	private void saveDocumentWithPassword() {
		String name = documentNameInput.getText().toString().trim();
		String content = documentContentInput.getText().toString();

		if (name.isEmpty()) {
			documentNameLayout.setError("Document name is required");
			documentNameInput.requestFocus();
			return;
		}

		documentNameLayout.setError(null);

		if (!name.toLowerCase().endsWith(".txt")) {
			name = name + ".txt";
		}

		String finalName = name;
		DocumentPasswordDialog dialog = DocumentPasswordDialog.newPasswordDialog(
				"Protect Document",
				"Set a password for this document. This adds an extra layer of encryption."
		);

		dialog.setCallback(new DocumentPasswordDialog.PasswordCallback() {
			@Override
			public void onPasswordEntered(@Nullable char[] password) {
				byte[] contentBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
				viewModel.addDocumentWithPassword(finalName, contentBytes, password);

				Toast.makeText(requireContext(),
						"Document saved with password protection",
						Toast.LENGTH_SHORT).show();
				hasUnsavedChanges = false;

				if (getActivity() != null) {
					getActivity().getOnBackPressedDispatcher().onBackPressed();
				}
			}

			@Override
			public void onPasswordCancelled() {
			}
		});

		dialog.show(getParentFragmentManager(), "password_dialog");
	}

	private void showUnsavedChangesDialog() {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle("Unsaved Changes")
				.setMessage("You have unsaved changes. Do you want to save before leaving?")
				.setPositiveButton("Save", (dialog, which) -> saveDocument())
				.setNegativeButton("Discard", (dialog, which) -> {
					hasUnsavedChanges = false;
					if (getActivity() != null) {
						getActivity().getOnBackPressedDispatcher().onBackPressed();
					}
				})
				.setNeutralButton("Cancel", null)
				.show();
	}

	@Override
	public String getUniqueTag() {
		return "TextEditorFragment";
	}
}
