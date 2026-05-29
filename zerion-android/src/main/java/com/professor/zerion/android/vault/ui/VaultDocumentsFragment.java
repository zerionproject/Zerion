package com.professor.zerion.android.vault.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.ui.adapters.VaultDocumentsAdapter;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class VaultDocumentsFragment extends BaseFragment {

	@Override
	public void onStop() {
		super.onStop();
		try {
			Activity a = getActivity();
			if (a == null) return;
			java.io.File cacheDir =
					new java.io.File(a.getCacheDir(), "zenc_share");
			if (!cacheDir.exists()) return;
			java.io.File[] children = cacheDir.listFiles();
			if (children == null) return;
			for (java.io.File f : children) {
				try {
					f.delete();
				} catch (Exception ignored) {
				}
			}
		} catch (Exception ignored) {
		}
	}

	private static final int REQUEST_FILE_PICK = 1003;

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private VaultViewModel viewModel;
	private RecyclerView documentsList;
	private LinearLayout emptyState;
	private FloatingActionButton fabAdd;
	private VaultDocumentsAdapter adapter;
	private boolean isPickerMode = false;

	public static VaultDocumentsFragment newInstance() {
		return new VaultDocumentsFragment();
	}

	public void setPickerMode(boolean pickerMode) {
		this.isPickerMode = pickerMode;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_vault_documents, container, false);

		documentsList = view.findViewById(R.id.documents_list);
		emptyState = view.findViewById(R.id.empty_state);
		fabAdd = view.findViewById(R.id.fab_add);

		return view;
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(VaultViewModel.class);

		setupDocumentsList();
		setupClickListeners();
		observeViewModel();
	}

	private void setupDocumentsList() {
		documentsList.setLayoutManager(new LinearLayoutManager(requireContext()));

		adapter = new VaultDocumentsAdapter(new VaultDocumentsAdapter.OnDocumentClickListener() {
			@Override
			public void onDocumentClick(VaultItem item) {
				if (isPickerMode) {
					selectItemForPicker(item);
				} else {
					Toast.makeText(requireContext(), "Opening " + item.name + "...", Toast.LENGTH_SHORT).show();
					openDocumentInSecureViewer(item);
				}
			}

			@Override
			public void onDocumentLongClick(VaultItem item) {
				if (!isPickerMode) {
					showDocumentOptions(item);
				}
			}
		});
		documentsList.setAdapter(adapter);
	}

	private void selectItemForPicker(VaultItem item) {
		Activity activity = getActivity();
		if (activity instanceof VaultActivity) {
			((VaultActivity) activity).onItemSelected(item);
		}
	}

	private void openDocumentInSecureViewer(VaultItem item) {
		VaultDocumentViewerFragment viewerFragment = VaultDocumentViewerFragment.newInstance(
				item.id,
				item.name
		);

		if (listener != null) {
			listener.showNextFragment(viewerFragment);
		} else {
			Toast.makeText(requireContext(),
					"Cannot open document: Activity not ready",
					Toast.LENGTH_SHORT).show();
		}
	}

	private void showDocumentDetails(VaultItem item) {
		String message = "Name: " + item.name + "\n" +
				"Size: " + formatFileSize(item.size) + "\n" +
				"Modified: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
						java.util.Locale.getDefault()).format(new java.util.Date(item.modifiedTimestamp));

		new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
				.setTitle("Document Details")
				.setMessage(message)
				.setPositiveButton("OK", null)
				.show();
	}

	private void exportDocumentSecurely(VaultItem item) {
		new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
				.setTitle("⚠️ Security Warning")
				.setMessage("Exporting will save an UNENCRYPTED copy to your Downloads folder.\n\n" +
						"• File will NOT be encrypted\n" +
						"• Other apps can access it\n" +
						"• File persists after export\n\n" +
						"Continue?")
				.setPositiveButton("Export Anyway", (dialog, which) -> {
					performDocumentExport(item);
				})
				.setNegativeButton("Cancel", null)
				.show();
	}

	private void performDocumentExport(VaultItem item) {
		Activity a = getActivity();
		if (a == null) return;
		viewModel.getMediaContent(item.id, new VaultViewModel.MediaContentCallback() {
			@Override
			public void onContentRetrieved(byte[] content) {
				new Thread(() -> {
					try {
						java.io.File exportDir = new java.io.File(
								android.os.Environment.getExternalStoragePublicDirectory(
										android.os.Environment.DIRECTORY_DOWNLOADS),
								"Zerion"
						);
						if (!exportDir.exists()) {
							exportDir.mkdirs();
						}

						java.io.File exportFile = new java.io.File(exportDir, item.name);
						java.io.FileOutputStream fos = new java.io.FileOutputStream(exportFile);
						fos.write(content);
						fos.close();

						java.util.Arrays.fill(content, (byte) 0);

						a.runOnUiThread(() -> {
							if (isAdded()) {
								Toast.makeText(a,
										"Document exported to: " + exportFile.getPath(),
										Toast.LENGTH_LONG).show();
							}
						});
					} catch (Exception e) {
						a.runOnUiThread(() -> {
							if (isAdded()) {
								Toast.makeText(a,
										"Failed to export document",
										Toast.LENGTH_SHORT).show();
							}
						});
					}
				}).start();
			}

			@Override
			public void onError(String error) {
				if (isAdded()) {
					Toast.makeText(a,
							"Failed to load document: " + error,
							Toast.LENGTH_SHORT).show();
				}
			}
		});
	}

	private String formatFileSize(long size) {
		if (size <= 0) return "0 B";
		final String[] units = new String[]{"B", "KB", "MB", "GB"};
		int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
		return new java.text.DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups))
				+ " " + units[digitGroups];
	}

	private void showDocumentOptions(VaultItem item) {
		String[] options = {"Export (Unencrypted)", "Export as .zenc (Encrypted)", "Share .zenc File", "Delete"};

		new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
				.setTitle(item.name)
				.setItems(options, (dialog, which) -> {
					switch (which) {
						case 0:
							exportDocumentSecurely(item);
							break;
						case 1:
							exportAsEncryptedZenc(item);
							break;
						case 2:
							shareAsEncryptedZenc(item);
							break;
						case 3:
							confirmDeleteDocument(item);
							break;
					}
				})
				.show();
	}

	private void exportAsEncryptedZenc(VaultItem item) {
		DocumentPasswordDialog dialog = DocumentPasswordDialog.newPasswordDialog(
				"Set Export Password",
				"Set a password to encrypt the .zenc file. This file can be shared securely."
		);

		dialog.setCallback(new DocumentPasswordDialog.PasswordCallback() {
			@Override
			public void onPasswordEntered(@Nullable char[] password) {
				if (password != null && password.length > 0) {
					performEncryptedExport(item, password, false);
				} else {
					Toast.makeText(requireContext(),
							"Password is required for .zenc export",
							Toast.LENGTH_SHORT).show();
				}
			}

			@Override
			public void onPasswordCancelled() {
			}
		});

		dialog.show(getParentFragmentManager(), "export_password");
	}

	private void shareAsEncryptedZenc(VaultItem item) {
		DocumentPasswordDialog dialog = DocumentPasswordDialog.newPasswordDialog(
				"Set Export Password",
				"Set a password to encrypt the .zenc file. You'll need to share this password separately."
		);

		dialog.setCallback(new DocumentPasswordDialog.PasswordCallback() {
			@Override
			public void onPasswordEntered(@Nullable char[] password) {
				if (password != null && password.length > 0) {
					performEncryptedExport(item, password, true);
				} else {
					Toast.makeText(requireContext(),
							"Password is required for .zenc export",
							Toast.LENGTH_SHORT).show();
				}
			}

			@Override
			public void onPasswordCancelled() {
			}
		});

		dialog.show(getParentFragmentManager(), "share_password");
	}

	private void performEncryptedExport(VaultItem item, char[] password, boolean share) {
		Activity a = getActivity();
		if (a == null) return;
		viewModel.getMediaContent(item.id, new VaultViewModel.MediaContentCallback() {
			@Override
			public void onContentRetrieved(byte[] content) {
				new Thread(() -> {
					try {
						byte[] zencData = com.professor.zerion.android.vault.utils.EncryptedFileExporter
								.exportEncrypted(item.name, content, password);

						java.util.Arrays.fill(content, (byte) 0);

						String exportFilename = item.name;
						if (!exportFilename.toLowerCase().endsWith(".zenc")) {
							exportFilename = exportFilename + ".zenc";
						}

						if (share) {
							shareZencFile(a, exportFilename, zencData);
						} else {
							saveZencToDownloads(a, exportFilename, zencData);
						}

					} catch (Exception e) {
						a.runOnUiThread(() -> {
							if (isAdded()) {
								Toast.makeText(a,
										"Failed to export",
										Toast.LENGTH_SHORT).show();
							}
						});
					}
				}).start();
			}

			@Override
			public void onError(String error) {
				if (isAdded()) {
					Toast.makeText(a,
							"Failed to load document: " + error,
							Toast.LENGTH_SHORT).show();
				}
			}
		});
	}

	private void shareZencFile(Activity a, String filename, byte[] zencData) {
		try {
			java.io.File cacheDir = new java.io.File(a.getCacheDir(), "zenc_share");
			if (!cacheDir.exists()) {
				cacheDir.mkdirs();
			}
			java.io.File[] stale = cacheDir.listFiles();
			if (stale != null) {
				for (java.io.File f : stale) {
					try {
						f.delete();
					} catch (Exception ignored) {
					}
				}
			}

			java.io.File zencFile = new java.io.File(cacheDir, filename);
			zencFile.deleteOnExit();
			java.io.FileOutputStream fos = new java.io.FileOutputStream(zencFile);
			fos.write(zencData);
			fos.close();

			java.util.Arrays.fill(zencData, (byte) 0);

			android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
					a,
					a.getPackageName() + ".fileprovider",
					zencFile
			);

			android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
			shareIntent.setType("application/octet-stream");
			shareIntent.putExtra(android.content.Intent.EXTRA_STREAM, uri);
			shareIntent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);

			a.runOnUiThread(() -> {
				if (isAdded()) {
					expectChildResult();
					startActivity(android.content.Intent.createChooser(shareIntent, "Share Encrypted File"));
					Toast.makeText(a,
							"Sharing encrypted file. Remember to share the password separately!",
							Toast.LENGTH_LONG).show();
				}
			});

		} catch (Exception e) {
			a.runOnUiThread(() -> {
				if (isAdded()) {
					Toast.makeText(a,
							"Failed to share",
							Toast.LENGTH_SHORT).show();
				}
			});
		}
	}

	private void saveZencToDownloads(Activity a, String filename, byte[] zencData) {
		try {
			java.io.File exportDir = new java.io.File(
					android.os.Environment.getExternalStoragePublicDirectory(
							android.os.Environment.DIRECTORY_DOWNLOADS),
					"Zerion"
			);
			if (!exportDir.exists()) {
				exportDir.mkdirs();
			}

			java.io.File exportFile = new java.io.File(exportDir, filename);
			java.io.FileOutputStream fos = new java.io.FileOutputStream(exportFile);
			fos.write(zencData);
			fos.close();

			java.util.Arrays.fill(zencData, (byte) 0);

			a.runOnUiThread(() -> {
				if (isAdded()) {
					Toast.makeText(a,
							"Encrypted file saved to: " + exportFile.getPath(),
							Toast.LENGTH_LONG).show();
				}
			});

		} catch (Exception e) {
			a.runOnUiThread(() -> {
				if (isAdded()) {
					Toast.makeText(a,
							"Failed to save",
							Toast.LENGTH_SHORT).show();
				}
			});
		}
	}

	private void confirmDeleteDocument(VaultItem item) {
		new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
				.setTitle("Delete Document")
				.setMessage("Are you sure you want to delete this document?")
				.setPositiveButton(android.R.string.yes, (dialog, which) -> {
					viewModel.deleteItem(item.id);
					Toast.makeText(requireContext(), "Document deleted", Toast.LENGTH_SHORT).show();
				})
				.setNegativeButton(android.R.string.no, null)
				.show();
	}

	private void setupClickListeners() {
		if (isPickerMode) {
			fabAdd.setVisibility(View.GONE);
		} else {
			fabAdd.setOnClickListener(v -> showAddDocumentOptions());
		}
	}

	private void showAddDocumentOptions() {
		String[] options = {"Import Document", "New Text Document"};

		new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
				.setTitle("Add Document")
				.setItems(options, (dialog, which) -> {
					switch (which) {
						case 0:
							pickDocument();
							break;
						case 1:
							createNewTextDocument();
							break;
					}
				})
				.show();
	}

	private void createNewTextDocument() {
		TextEditorFragment fragment = TextEditorFragment.newInstance();
		if (listener != null) {
			listener.showNextFragment(fragment);
		} else {
			Toast.makeText(requireContext(),
					"Cannot open editor: Activity not ready",
					Toast.LENGTH_SHORT).show();
		}
	}

	private void pickDocument() {
		Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
		intent.setType("*/*");
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
				"application/pdf",
				"application/msword",
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
				"application/vnd.ms-excel",
				"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
				"text/plain",
				"application/zip"
		});

		expectChildResult();
		startActivityForResult(
				Intent.createChooser(intent, "Select Document"),
				REQUEST_FILE_PICK
		);
	}

	private void expectChildResult() {
		if (getActivity() instanceof VaultActivity) {
			((VaultActivity) getActivity()).setExpectingChildResult();
		}
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		if (resultCode == Activity.RESULT_OK && data != null) {
			if (requestCode == REQUEST_FILE_PICK) {
				Uri fileUri = data.getData();
				if (fileUri != null) {
					String fileName = getFileName(fileUri);

					try {
						java.io.InputStream inputStream = requireContext().getContentResolver()
								.openInputStream(fileUri);
						if (inputStream != null) {
							java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
							byte[] buffer = new byte[4096];
							int bytesRead;
							while ((bytesRead = inputStream.read(buffer)) != -1) {
								outputStream.write(buffer, 0, bytesRead);
							}
							inputStream.close();

							byte[] content = outputStream.toByteArray();
							outputStream.close();

							showPasswordDialogForImport(fileName, content);
						}
					} catch (Exception e) {
						Toast.makeText(requireContext(),
								"Failed to read document",
								Toast.LENGTH_SHORT).show();
					}
				}
			}
		}
	}

	private void showPasswordDialogForImport(String fileName, byte[] content) {
		DocumentPasswordDialog dialog = DocumentPasswordDialog.newPasswordDialog(
				"Protect Document",
				"Optionally set a password for this document. This adds an extra layer of encryption."
		);

		dialog.setCallback(new DocumentPasswordDialog.PasswordCallback() {
			@Override
			public void onPasswordEntered(@Nullable char[] password) {
				viewModel.addDocumentWithPassword(fileName, content, password);
				Toast.makeText(requireContext(),
						password != null
								? "Document '" + fileName + "' saved with password protection"
								: "Document '" + fileName + "' saved securely",
						Toast.LENGTH_SHORT).show();

				java.util.Arrays.fill(content, (byte) 0);
			}

			@Override
			public void onPasswordCancelled() {
				Toast.makeText(requireContext(), "Import cancelled", Toast.LENGTH_SHORT).show();

				java.util.Arrays.fill(content, (byte) 0);
			}
		});

		dialog.show(getParentFragmentManager(), "password_dialog");
	}

	private String getFileName(Uri uri) {
		String result = null;
		if (uri.getScheme().equals("content")) {
			android.database.Cursor cursor = requireContext().getContentResolver()
					.query(uri, null, null, null, null);
			try {
				if (cursor != null && cursor.moveToFirst()) {
					int index = cursor.getColumnIndex(
							android.provider.OpenableColumns.DISPLAY_NAME);
					if (index >= 0) {
						result = cursor.getString(index);
					}
				}
			} finally {
				if (cursor != null) cursor.close();
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

	private void observeViewModel() {
		viewModel.getVaultItems().observe(getViewLifecycleOwner(), items -> {
			if (items != null) {
				java.util.List<VaultItem> docItems = new java.util.ArrayList<>();
				for (VaultItem item : items) {
					if (item.type == VaultItem.ItemType.DOCUMENT) {
						docItems.add(item);
					}
				}

				updateDocumentsList(docItems);

				boolean isEmpty = docItems.isEmpty();
				emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
				documentsList.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
			}
		});
	}

	private void updateDocumentsList(java.util.List<VaultItem> docItems) {
		if (adapter != null) {
			adapter.setItems(docItems);
		}
	}

	@Override
	public String getUniqueTag() {
		return "VaultDocumentsFragment";
	}
}