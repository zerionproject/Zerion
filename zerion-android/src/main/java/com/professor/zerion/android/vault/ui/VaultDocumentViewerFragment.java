package com.professor.zerion.android.vault.ui;

import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.vault.utils.MimeUtils;
import com.professor.zerion.android.vault.utils.SecureMemory;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class VaultDocumentViewerFragment extends BaseFragment {

	private static final String ARG_ITEM_ID = "item_id";
	private static final String ARG_ITEM_NAME = "item_name";

	private static final long MAX_DOCUMENT_SIZE = 15 * 1024 * 1024;

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private VaultViewModel viewModel;

	private String itemId;
	private String itemName;

	private MaterialToolbar toolbar;
	private FrameLayout loadingOverlay;

	private NestedScrollView pdfScrollView;
	private ImageView pdfPageView;
	private LinearLayout pdfControls;
	private Button pdfPrevButton;
	private Button pdfNextButton;
	private TextView pdfPageIndicator;

	private NestedScrollView textScrollView;
	private TextView textContentView;

	private WebView markdownWebView;

	private LinearLayout unsupportedLayout;
	private TextView unsupportedTitle;
	private TextView unsupportedMessage;

	private PdfRenderer pdfRenderer;
	private ParcelFileDescriptor pdfFileDescriptor;
	private byte[] pdfBytes;
	private java.io.File pdfTempFile;
	private int currentPdfPage = 0;
	private Bitmap currentPageBitmap;

	private byte[] documentBytes;
	private boolean documentLoadStarted = false;

	public static VaultDocumentViewerFragment newInstance(String itemId, String itemName) {
		VaultDocumentViewerFragment fragment = new VaultDocumentViewerFragment();
		Bundle args = new Bundle();
		args.putString(ARG_ITEM_ID, itemId);
		args.putString(ARG_ITEM_NAME, itemName);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (getArguments() != null) {
			itemId = getArguments().getString(ARG_ITEM_ID);
			itemName = getArguments().getString(ARG_ITEM_NAME);
		}
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_vault_document_viewer, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(VaultViewModel.class);

		initializeViews(view);
		setupToolbar();
		loadDocument();
	}

	private void initializeViews(View view) {
		toolbar = view.findViewById(R.id.toolbar);
		loadingOverlay = view.findViewById(R.id.loading_overlay);

		pdfScrollView = view.findViewById(R.id.pdf_scroll_view);
		pdfPageView = view.findViewById(R.id.pdf_page_view);
		pdfControls = view.findViewById(R.id.pdf_controls);
		pdfPrevButton = view.findViewById(R.id.pdf_prev_button);
		pdfNextButton = view.findViewById(R.id.pdf_next_button);
		pdfPageIndicator = view.findViewById(R.id.pdf_page_indicator);

		textScrollView = view.findViewById(R.id.text_scroll_view);
		textContentView = view.findViewById(R.id.text_content);

		markdownWebView = view.findViewById(R.id.markdown_webview);

		unsupportedLayout = view.findViewById(R.id.unsupported_layout);
		unsupportedTitle = view.findViewById(R.id.unsupported_title);
		unsupportedMessage = view.findViewById(R.id.unsupported_message);

		pdfPrevButton.setOnClickListener(v -> navigatePdfPage(-1));
		pdfNextButton.setOnClickListener(v -> navigatePdfPage(1));
	}

	private void setupToolbar() {
		toolbar.setTitle(itemName != null ? itemName
				: getString(R.string.vault_doc_viewer_title));
		toolbar.setNavigationOnClickListener(v -> {
			if (getActivity() != null) {
				getActivity().getOnBackPressedDispatcher().onBackPressed();
			}
		});

		toolbar.inflateMenu(R.menu.document_viewer_menu);
		toolbar.setOnMenuItemClickListener(item -> {
			if (item.getItemId() == R.id.action_edit) {
				editDocument();
				return true;
			}
			return false;
		});
	}

	private void editDocument() {
		if (documentBytes == null) {
			showSnackbar(getString(R.string.vault_doc_cannot_edit));
			return;
		}

		MimeUtils.MimeType detectedType = MimeUtils.detectMimeType(documentBytes, itemName);
		if (detectedType != MimeUtils.MimeType.TEXT && detectedType != MimeUtils.MimeType.MARKDOWN) {
			showSnackbar(getString(R.string.vault_doc_only_text_editable));
			return;
		}

		String content = new String(documentBytes, StandardCharsets.UTF_8);

		if (listener != null) {
			TextEditorFragment editorFragment = TextEditorFragment.newInstanceForEdit(itemId, itemName, content);
			listener.showNextFragment(editorFragment);
		}
	}

	private void loadDocument() {
		showLoading(true);

		checkPasswordProtection();
	}

	private void checkPasswordProtection() {
		viewModel.getVaultItems().observe(getViewLifecycleOwner(), items -> {
			if (items != null) {
				if (documentLoadStarted) return;
				for (com.professor.zerion.android.vault.model.VaultItem item : items) {
					if (item.id.equals(itemId)) {
						documentLoadStarted = true;
						if (item.hasExtraPassword) {
							showPasswordDialog();
						} else {
							loadDocumentWithoutPassword();
						}
						return;
					}
				}
				showLoading(false);
				showError(getString(R.string.vault_doc_not_found_title),
						getString(R.string.vault_doc_not_found_message));
			}
		});
	}

	private void showPasswordDialog() {
		if (!isAdded() || isStateSaved()) return;
		showLoading(false);

		DocumentPasswordDialog dialog = DocumentPasswordDialog.newUnlockDialog(
				getString(R.string.vault_password_required_title),
				getString(R.string.vault_doc_password_message)
		);

		dialog.setCallback(new DocumentPasswordDialog.PasswordCallback() {
			@Override
			public void onPasswordEntered(@Nullable char[] password) {
				if (password != null) {
					loadDocumentWithPassword(password);
				} else {
					showError(getString(R.string.vault_password_required_title),
							getString(R.string.vault_doc_password_needed_message));
				}
			}

			@Override
			public void onPasswordCancelled() {
				if (getActivity() != null) {
					getActivity().getOnBackPressedDispatcher().onBackPressed();
				}
			}
		});

		dialog.show(getParentFragmentManager(), "password_unlock");
	}

	private void loadDocumentWithPassword(char[] password) {
		showLoading(true);

		viewModel.loadDocumentWithPassword(itemId, password, new VaultViewModel.DocumentCallback() {
			@Override
			public void onLoaded(byte[] content, String mimeType) {
				if (!canUpdateUi()) {
					SecureMemory.shred(content);
					return;
				}
				documentBytes = content;

				MimeUtils.MimeType detectedType = MimeUtils.detectMimeType(content, itemName);

				showLoading(false);

				if (content.length > MAX_DOCUMENT_SIZE) {
					showError(getString(R.string.vault_doc_too_large_title),
							getString(R.string.vault_doc_too_large_message,
									formatSize(content.length),
									formatSize(MAX_DOCUMENT_SIZE)));
					return;
				}

				if (detectedType.canViewSecurely) {
					renderDocument(content, detectedType);
				} else {
					showUnsupportedFormat(detectedType);
				}
			}

			@Override
			public void onError(String error) {
				if (!canUpdateUi()) return;
				showLoading(false);
				if (error.contains("Incorrect password")) {
					showSnackbar(getString(R.string.vault_incorrect_password));
					showPasswordDialog();
				} else {
					showError(getString(R.string.vault_doc_load_failed_title),
							error);
				}
			}
		});
	}

	private void loadDocumentWithoutPassword() {
		viewModel.loadDocumentSecure(itemId, new VaultViewModel.DocumentCallback() {
			@Override
			public void onLoaded(byte[] content, String mimeType) {
				if (!canUpdateUi()) {
					SecureMemory.shred(content);
					return;
				}
				documentBytes = content;

				MimeUtils.MimeType detectedType = MimeUtils.detectMimeType(content, itemName);

				showLoading(false);

				if (content.length > MAX_DOCUMENT_SIZE) {
					showError(getString(R.string.vault_doc_too_large_title),
							getString(R.string.vault_doc_too_large_message,
									formatSize(content.length),
									formatSize(MAX_DOCUMENT_SIZE)));
					return;
				}

				if (detectedType.canViewSecurely) {
					renderDocument(content, detectedType);
				} else {
					showUnsupportedFormat(detectedType);
				}
			}

			@Override
			public void onError(String error) {
				if (!canUpdateUi()) return;
				showLoading(false);
				showError(getString(R.string.vault_doc_load_failed_title), error);
			}
		});
	}

	private void renderDocument(byte[] content, MimeUtils.MimeType mimeType) {
		switch (mimeType) {
			case PDF:
				renderPdf(content);
				break;

			case TEXT:
				renderText(content);
				break;

			case MARKDOWN:
				renderMarkdown(content);
				break;

			case IMAGE_PNG:
			case IMAGE_JPEG:
			case IMAGE_GIF:
			case IMAGE_WEBP:
				showSnackbar(getString(R.string.vault_doc_image_in_gallery));
				if (getActivity() != null) {
					getActivity().getOnBackPressedDispatcher().onBackPressed();
				}
				break;

			default:
				showUnsupportedFormat(mimeType);
				break;
		}
	}

	private void renderPdf(byte[] pdfContent) {
		try {
			pdfBytes = pdfContent;

			java.io.File cacheDir = requireContext().getCacheDir();
			pdfTempFile = new java.io.File(cacheDir,
					"vault_pdf_" + System.nanoTime() + ".pdf");
			try (java.io.FileOutputStream fos =
					new java.io.FileOutputStream(pdfTempFile)) {
				fos.write(pdfContent);
				fos.getFD().sync();
			}

			pdfFileDescriptor = ParcelFileDescriptor.open(pdfTempFile,
					ParcelFileDescriptor.MODE_READ_ONLY);
			pdfRenderer = new PdfRenderer(pdfFileDescriptor);

			pdfScrollView.setVisibility(View.VISIBLE);
			textScrollView.setVisibility(View.GONE);
			markdownWebView.setVisibility(View.GONE);
			unsupportedLayout.setVisibility(View.GONE);

			currentPdfPage = 0;
			renderPdfPage(currentPdfPage);

		} catch (Exception e) {
			showError(getString(R.string.vault_doc_pdf_failed_title),
					getString(R.string.vault_doc_pdf_failed_message));
		}
	}

	private void renderPdfPage(int pageIndex) {
		if (pdfRenderer == null || pageIndex < 0 || pageIndex >= pdfRenderer.getPageCount()) {
			return;
		}

		PdfRenderer.Page page = null;
		try {
			if (currentPageBitmap != null && !currentPageBitmap.isRecycled()) {
				currentPageBitmap.recycle();
				currentPageBitmap = null;
			}

			page = pdfRenderer.openPage(pageIndex);

			int width = page.getWidth() * 2;
			int height = page.getHeight() * 2;
			int maxDim = 2048;
			if (width > maxDim || height > maxDim) {
				float scale = Math.min((float) maxDim / width,
						(float) maxDim / height);
				width = Math.max(1, Math.round(width * scale));
				height = Math.max(1, Math.round(height * scale));
			}

			currentPageBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

			page.render(currentPageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

			pdfPageView.setImageBitmap(currentPageBitmap);

			pdfPageIndicator.setText(getString(R.string.vault_doc_page_indicator,
					pageIndex + 1, pdfRenderer.getPageCount()));
			pdfPrevButton.setEnabled(pageIndex > 0);
			pdfNextButton.setEnabled(pageIndex < pdfRenderer.getPageCount() - 1);

			currentPdfPage = pageIndex;

		} catch (Throwable e) {
			if (isAdded()) {
				showSnackbar(getString(R.string.vault_doc_page_render_failed));
			}
		} finally {
			if (page != null) {
				try {
					page.close();
				} catch (Exception ignored) {
				}
			}
		}
	}

	private void navigatePdfPage(int delta) {
		int newPage = currentPdfPage + delta;
		if (pdfRenderer != null && newPage >= 0 && newPage < pdfRenderer.getPageCount()) {
			renderPdfPage(newPage);
		}
	}

	private void renderText(byte[] textContent) {
		try {
			String text = new String(textContent, StandardCharsets.UTF_8);

			textScrollView.setVisibility(View.VISIBLE);
			pdfScrollView.setVisibility(View.GONE);
			markdownWebView.setVisibility(View.GONE);
			unsupportedLayout.setVisibility(View.GONE);

			textContentView.setText(text);

		} catch (Exception e) {
			showError(getString(R.string.vault_doc_text_failed_title),
					getString(R.string.vault_doc_text_failed_message,
							e.getMessage()));
		}
	}

	private void renderMarkdown(byte[] markdownContent) {
		try {
			String markdown = new String(markdownContent, StandardCharsets.UTF_8);

			String html = convertMarkdownToHtml(markdown);

			markdownWebView.setVisibility(View.VISIBLE);
			pdfScrollView.setVisibility(View.GONE);
			textScrollView.setVisibility(View.GONE);
			unsupportedLayout.setVisibility(View.GONE);

			markdownWebView.getSettings().setJavaScriptEnabled(false);
			markdownWebView.getSettings().setAllowFileAccess(false);
			markdownWebView.getSettings().setAllowContentAccess(false);

			markdownWebView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);

		} catch (Exception e) {
			showError(getString(R.string.vault_doc_markdown_failed_title),
					getString(R.string.vault_doc_markdown_failed_message,
							e.getMessage()));
		}
	}

	private String convertMarkdownToHtml(String markdown) {
		String html = markdown
				.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;");

		html = html.replaceAll("(?m)^# (.+)$", "<h1>$1</h1>");
		html = html.replaceAll("(?m)^## (.+)$", "<h2>$1</h2>");
		html = html.replaceAll("(?m)^### (.+)$", "<h3>$1</h3>");
		html = html.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
		html = html.replaceAll("\\*(.+?)\\*", "<em>$1</em>");
		html = html.replaceAll("```([\\s\\S]*?)```", "<pre><code>$1</code></pre>");
		html = html.replaceAll("`(.+?)`", "<code>$1</code>");
		html = html.replace("\n\n", "</p><p>");
		html = html.replace("\n", "<br>");

		return "<!DOCTYPE html>" +
				"<html>" +
				"<head>" +
				"<meta charset=\"utf-8\">" +
				"<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
				"<style>" +
				"body { font-family: sans-serif; padding: 16px; line-height: 1.6; }" +
				"h1, h2, h3 { color: #333; }" +
				"code { background: #f4f4f4; padding: 2px 6px; border-radius: 3px; }" +
				"pre { background: #f4f4f4; padding: 12px; border-radius: 6px; overflow-x: auto; }" +
				"</style>" +
				"</head>" +
				"<body>" +
				"<p>" + html + "</p>" +
				"</body>" +
				"</html>";
	}

	private void showUnsupportedFormat(MimeUtils.MimeType mimeType) {
		pdfScrollView.setVisibility(View.GONE);
		textScrollView.setVisibility(View.GONE);
		markdownWebView.setVisibility(View.GONE);

		unsupportedLayout.setVisibility(View.VISIBLE);

		String message;
		if (mimeType == MimeUtils.MimeType.OFFICE_DOCX ||
				mimeType == MimeUtils.MimeType.OFFICE_DOC ||
				mimeType == MimeUtils.MimeType.OFFICE_XLSX ||
				mimeType == MimeUtils.MimeType.OFFICE_XLS ||
				mimeType == MimeUtils.MimeType.OFFICE_PPTX ||
				mimeType == MimeUtils.MimeType.OFFICE_PPT) {
			message = getString(R.string.vault_doc_unsupported_office);
		} else if (mimeType == MimeUtils.MimeType.ARCHIVE_ZIP ||
				mimeType == MimeUtils.MimeType.ARCHIVE_RAR ||
				mimeType == MimeUtils.MimeType.ARCHIVE_7Z) {
			message = getString(R.string.vault_doc_unsupported_archive);
		} else {
			message = getString(R.string.vault_doc_unsupported_generic,
					mimeType.displayName);
		}

		unsupportedMessage.setText(message);
	}

	private void showError(String title, String message) {
		unsupportedLayout.setVisibility(View.VISIBLE);
		pdfScrollView.setVisibility(View.GONE);
		textScrollView.setVisibility(View.GONE);
		markdownWebView.setVisibility(View.GONE);

		unsupportedTitle.setText(title);
		unsupportedMessage.setText(message);
	}

	private void showLoading(boolean show) {
		loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
	}

	private boolean canUpdateUi() {
		return isAdded() && getView() != null;
	}

	private String formatSize(long bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
		return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();

		cleanupSecurely();
	}

	private void cleanupSecurely() {
		if (documentBytes != null) {
			SecureMemory.shred(documentBytes);
			documentBytes = null;
		}

		if (pdfBytes != null) {
			SecureMemory.shred(pdfBytes);
			pdfBytes = null;
		}

		if (currentPageBitmap != null && !currentPageBitmap.isRecycled()) {
			currentPageBitmap.recycle();
			currentPageBitmap = null;
		}

		if (pdfRenderer != null) {
			try {
				pdfRenderer.close();
			} catch (Exception ignored) {
			}
			pdfRenderer = null;
		}

		if (pdfFileDescriptor != null) {
			try {
				pdfFileDescriptor.close();
			} catch (Exception ignored) {
			}
			pdfFileDescriptor = null;
		}

		if (pdfTempFile != null) {
			SecureMemory.secureDeleteFile(pdfTempFile, 0L, false);
			pdfTempFile = null;
		}

		if (textContentView != null) {
			textContentView.setText("");
		}

		if (markdownWebView != null) {
			markdownWebView.loadUrl("about:blank");
			markdownWebView.clearHistory();
			markdownWebView.clearCache(true);
		}

		System.gc();
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
		return "VaultDocumentViewerFragment_" + itemId;
	}
}
