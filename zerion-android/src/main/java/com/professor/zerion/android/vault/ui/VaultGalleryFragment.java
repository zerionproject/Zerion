package com.professor.zerion.android.vault.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.ui.adapters.VaultGalleryAdapter;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class VaultGalleryFragment extends BaseFragment {

	private static final int REQUEST_IMAGE_PICK = 1001;
	private static final int REQUEST_IMAGE_CAPTURE = 1002;
	private static final int REQUEST_CAMERA_PERMISSION = 2001;

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private VaultViewModel viewModel;
	private RecyclerView galleryGrid;
	private TextView emptyText;
	private FloatingActionButton fabAdd;
	private VaultGalleryAdapter adapter;
	private boolean isPickerMode = false;

	public static VaultGalleryFragment newInstance() {
		return new VaultGalleryFragment();
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
		View view = inflater.inflate(R.layout.fragment_vault_gallery, container, false);

		galleryGrid = view.findViewById(R.id.gallery_grid);
		emptyText = view.findViewById(R.id.gallery_empty_text);
		fabAdd = view.findViewById(R.id.fab_add);

		return view;
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(VaultViewModel.class);

		setupGalleryGrid();
		setupClickListeners();
		observeViewModel();
	}

	private void setupGalleryGrid() {
		galleryGrid.setLayoutManager(new GridLayoutManager(requireContext(), 3));

		adapter = new VaultGalleryAdapter(viewModel, new VaultGalleryAdapter.OnImageClickListener() {
			@Override
			public void onImageClick(VaultItem item) {
				if (isPickerMode) {
					selectItemForPicker(item);
				} else {
					openMediaViewer(item);
				}
			}

			@Override
			public void onImageLongClick(VaultItem item) {
				if (!isPickerMode) {
					showItemOptions(item);
				}
			}
		});
		galleryGrid.setAdapter(adapter);
	}

	private void setupClickListeners() {
		if (isPickerMode) {
			fabAdd.setVisibility(View.GONE);
		} else {
			fabAdd.setOnClickListener(v -> showAddImageDialog());
		}
	}

	private void selectItemForPicker(VaultItem item) {
		Activity activity = getActivity();
		if (activity instanceof VaultActivity) {
			((VaultActivity) activity).onItemSelected(item);
		}
	}

	private void openMediaViewer(VaultItem item) {
		if (item == null || item.id == null) {
			Toast.makeText(requireContext(),
					"Failed to load media: Invalid item",
					Toast.LENGTH_SHORT).show();
			return;
		}

		android.app.Dialog dialog = new android.app.Dialog(requireContext(),
				android.R.style.Theme_Black_NoTitleBar_Fullscreen);

		View dialogView = LayoutInflater.from(requireContext())
				.inflate(R.layout.dialog_media_viewer, null);

		ImageView imageView = dialogView.findViewById(R.id.media_image);
		ImageView closeButton = dialogView.findViewById(R.id.close_button);
		TextView titleText = dialogView.findViewById(R.id.media_title);

		if (titleText != null) {
			titleText.setText(item.name != null ? item.name : "Media");
		}

		if (imageView != null) {
			imageView.setImageResource(R.drawable.ic_photo);
		}

		if (closeButton != null) {
			closeButton.setOnClickListener(v -> dialog.dismiss());
		}

		dialog.setContentView(dialogView);
		dialog.show();

		final android.graphics.Bitmap[] bitmapHolder = new android.graphics.Bitmap[1];
		dialog.setOnDismissListener(d -> {
			if (bitmapHolder[0] != null && !bitmapHolder[0].isRecycled()) {
				bitmapHolder[0].recycle();
				bitmapHolder[0] = null;
			}
		});

		viewModel.getMediaContent(item.id, new VaultViewModel.MediaContentCallback() {
			@Override
			public void onContentRetrieved(byte[] content) {
				new Thread(() -> {
					android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
					options.inJustDecodeBounds = false;
					android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(
							content, 0, content.length, options);

					if (bitmap == null) {
						requireActivity().runOnUiThread(() -> {
							Toast.makeText(requireContext(),
									"Failed to load media: Could not decode image",
									Toast.LENGTH_SHORT).show();
							dialog.dismiss();
						});
						java.util.Arrays.fill(content, (byte) 0);
						return;
					}

					bitmapHolder[0] = bitmap;
					requireActivity().runOnUiThread(() -> {
						if (imageView != null) {
							imageView.setImageBitmap(bitmap);
						}
					});

					java.util.Arrays.fill(content, (byte) 0);
				}).start();
			}

			@Override
			public void onError(String error) {
				Toast.makeText(requireContext(),
						"Failed to load media: " + error,
						Toast.LENGTH_SHORT).show();
				dialog.dismiss();
			}
		});
	}

	private void showItemOptions(VaultItem item) {
		String[] options = {"View", "Delete"};

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(item.name)
				.setItems(options, (dialog, which) -> {
					switch (which) {
						case 0:
							openMediaViewer(item);
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
					Toast.makeText(requireContext(), "Deleted " + item.name, Toast.LENGTH_SHORT).show();
				})
				.setNegativeButton(android.R.string.no, null)
				.show();
	}

	private void showAddImageDialog() {
		String[] options = {"Take Photo", "Choose from Gallery"};

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle("Add Image")
				.setItems(options, (dialog, which) -> {
					if (which == 0) {
						checkCameraPermissionAndCapture();
					} else {
						pickImageFromGallery();
					}
				})
				.show();
	}

	private void checkCameraPermissionAndCapture() {
		if (ContextCompat.checkSelfPermission(requireContext(),
				Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
			captureImage();
		} else {
			ActivityCompat.requestPermissions(requireActivity(),
					new String[]{Manifest.permission.CAMERA},
					REQUEST_CAMERA_PERMISSION);
		}
	}

	private void captureImage() {
		Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
		if (intent.resolveActivity(requireActivity().getPackageManager()) != null) {
			startActivityForResult(intent, REQUEST_IMAGE_CAPTURE);
		}
	}

	private void pickImageFromGallery() {
		Intent intent = new Intent(Intent.ACTION_PICK,
				MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
		startActivityForResult(intent, REQUEST_IMAGE_PICK);
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		if (resultCode == Activity.RESULT_OK && data != null) {
			if (requestCode == REQUEST_IMAGE_CAPTURE) {
				android.os.Bundle extras = data.getExtras();
				if (extras != null) {
					android.graphics.Bitmap imageBitmap = (android.graphics.Bitmap) extras.get("data");
					if (imageBitmap != null) {
						java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
						try {
							imageBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, outputStream);
							byte[] content = outputStream.toByteArray();

							String fileName = "photo_" + System.currentTimeMillis() + ".jpg";

							viewModel.addMediaToVault(VaultItem.ItemType.IMAGE, fileName, content, "image/jpeg");
							Toast.makeText(requireContext(),
									"Photo saved securely",
									Toast.LENGTH_SHORT).show();
						} finally {
							imageBitmap.recycle();
							try {
								outputStream.close();
							} catch (Exception e) {
							}
						}
					}
				}
			} else if (requestCode == REQUEST_IMAGE_PICK) {
				Uri imageUri = data.getData();
				if (imageUri != null) {
					java.io.InputStream inputStream = null;
					try {
						inputStream = requireContext().getContentResolver()
								.openInputStream(imageUri);
						if (inputStream != null) {
							java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
							byte[] buffer = new byte[4096];
							int bytesRead;
							long totalRead = 0;
							while ((bytesRead = inputStream.read(buffer)) != -1) {
								totalRead += bytesRead;
								if (totalRead > 20 * 1024 * 1024) {
									throw new java.io.IOException("Image too large");
								}
								outputStream.write(buffer, 0, bytesRead);
							}

							byte[] content = outputStream.toByteArray();
							outputStream.close();

							String fileName = getFileName(imageUri);
							if (fileName == null) {
								fileName = "image_" + System.currentTimeMillis() + ".jpg";
							}

							viewModel.addMediaToVault(VaultItem.ItemType.IMAGE, fileName, content, "image/jpeg");
							Toast.makeText(requireContext(),
									"Image saved securely",
									Toast.LENGTH_SHORT).show();
						}
					} catch (Exception e) {
						Toast.makeText(requireContext(),
								"Failed to save image",
								Toast.LENGTH_SHORT).show();
					} finally {
						if (inputStream != null) {
							try {
								inputStream.close();
							} catch (Exception e) {
							}
						}
					}
				}
			}
		}
	}

	private String getFileName(Uri uri) {
		String result = null;
		if (uri.getScheme() != null && uri.getScheme().equals("content")) {
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

	@Override
	public void onRequestPermissionsResult(int requestCode,
			String[] permissions,
			int[] grantResults) {
		if (requestCode == REQUEST_CAMERA_PERMISSION) {
			if (grantResults.length > 0 &&
					grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				captureImage();
			} else {
				Toast.makeText(requireContext(),
						"Camera permission required to take photos",
						Toast.LENGTH_SHORT).show();
			}
		}
	}

	private void observeViewModel() {
		viewModel.getVaultItems().observe(getViewLifecycleOwner(), items -> {
			if (items != null) {
				adapter.setItems(items);

				boolean hasMedia = items.stream()
						.anyMatch(item -> item.type == VaultItem.ItemType.IMAGE ||
								item.type == VaultItem.ItemType.VIDEO);

				emptyText.setVisibility(hasMedia ? View.GONE : View.VISIBLE);
				galleryGrid.setVisibility(hasMedia ? View.VISIBLE : View.GONE);
			}
		});

		viewModel.loadVaultItems();
	}

	@Override
	public String getUniqueTag() {
		return "VaultGalleryFragment";
	}
}