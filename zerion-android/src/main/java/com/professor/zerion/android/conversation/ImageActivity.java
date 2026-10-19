package com.professor.zerion.android.conversation;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.transition.Fade;
import android.transition.Transition;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.Window;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.google.android.material.appbar.AppBarLayout;

import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.sync.MessageId;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.attachment.SaveCleanCopy;
import com.professor.zerion.android.util.ExternalHandoff;
import com.professor.zerion.android.util.ExternalViewerTypes;
import com.professor.zerion.android.util.ZerionSnackbarBuilder;
import com.professor.zerion.android.view.PullDownLayout;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.view.WindowCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static com.google.android.material.snackbar.Snackbar.LENGTH_LONG;
import static java.util.Objects.requireNonNull;
import static com.professor.zerion.android.util.UiUtils.formatDateAbsolute;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ImageActivity extends ZerionActivity
		implements PullDownLayout.Callback, OnGlobalLayoutListener {

	final static String ATTACHMENTS = "attachments";
	final static String ATTACHMENT_POSITION = "position";
	final static String NAME = "name";
	final static String DATE = "date";
	final static String ITEM_ID = "itemId";

	private WindowInsetsControllerCompat insetsController;

	@Inject
	ViewModelProvider.Factory viewModelFactory;
	@Inject
	@IoExecutor
	Executor ioExecutor;

	private ImageViewModel viewModel;
	private PullDownLayout layout;
	private AppBarLayout appBarLayout;
	private ViewPager2 viewPager;
	private List<AttachmentItem> attachments = Collections.emptyList();
	@Nullable
	private MessageId conversationMessageId;
	@Nullable
	private String token;
	private int announcedPage = -1;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(ImageViewModel.class);
	}

	@Override
	protected boolean forceScreenshotProtection() {
		String shown = getIntent().getStringExtra(MediaViewer.EXTRA_TOKEN);
		return shown != null && MediaViewer.secure(shown);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);

		Intent i = getIntent();
		token = i.getStringExtra(MediaViewer.EXTRA_TOKEN);
		String name;
		long time;
		int position;
		if (token != null) {
			MediaViewer.Shown shown = MediaViewer.get(token);
			if (shown == null || !MediaViewer.watch(token, this::contentGone)) {
				finish();
				return;
			}
			name = shown.title;
			time = shown.time;
			position = 0;
		} else {
			if (state == null) supportPostponeEnterTransition();
			attachments =
					requireNonNull(i.getParcelableArrayListExtra(ATTACHMENTS));
			position = i.getIntExtra(ATTACHMENT_POSITION, -1);
			if (position < 0 || position >= attachments.size())
				throw new IllegalStateException();
			name = i.getStringExtra(NAME);
			time = i.getLongExtra(DATE, 0);
			conversationMessageId = new MessageId(
					requireNonNull(i.getByteArrayExtra(ITEM_ID)));
			viewModel.watchMessage(conversationMessageId);
			viewModel.getMessageGone().observeEvent(this, gone -> {
				if (gone != null && gone) contentGone();
			});
		}
		Window window = getWindow();
		Transition transition = new Fade();
		setSceneTransitionAnimation(transition, null, transition);

		viewModel.expectAttachments(attachments);
		viewModel.getSavableImage().observeEvent(this,
				this::onImageReadForSaving);
		viewModel.getOpenableImage().observeEvent(this,
				this::onImageReadForOpening);

		setContentView(R.layout.activity_image);
		layout = findViewById(R.id.layout);
		layout.setCallback(this);
		layout.getViewTreeObserver().addOnGlobalLayoutListener(this);

		insetsController = WindowCompat.getInsetsController(window,
				window.getDecorView());
		insetsController.setSystemBarsBehavior(
				WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
		insetsController.setAppearanceLightStatusBars(false);
		insetsController.setAppearanceLightNavigationBars(false);

		appBarLayout = findViewById(R.id.appBarLayout);
		Toolbar toolbar = requireNonNull(setUpCustomToolbar(true));
		TextView contactName = toolbar.findViewById(R.id.contactName);
		TextView dateView = toolbar.findViewById(R.id.dateView);

		contactName.setText(name);
		if (time > 0) {
			dateView.setText(formatDateAbsolute(this, time));
		} else {
			dateView.setVisibility(GONE);
		}

		viewPager = findViewById(R.id.viewPager);
		ImagePagerAdapter pagerAdapter = new ImagePagerAdapter();
		viewPager.setAdapter(pagerAdapter);
		viewPager.setCurrentItem(position, false);
		announcedPage = position;
		viewPager.registerOnPageChangeCallback(
				new ViewPager2.OnPageChangeCallback() {
					@Override
					public void onPageSelected(int page) {
						onPageShown(page);
					}
				});

		viewModel.getOnImageClicked().observeEvent(this, this::onImageClicked);
	}

	@Override
	public void onStart() {
		super.onStart();
		if (token != null && MediaViewer.get(token) == null) contentGone();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		MediaViewer.unwatch(token);
		if (isFinishing()) MediaViewer.close(token);
		if (isFinishing() && token == null) Glide.get(this).clearMemory();
	}

	private void contentGone() {
		for (Fragment f : getSupportFragmentManager().getFragments()) {
			if (f instanceof ImageFragment) {
				((ImageFragment) f).forgetPicture();
			}
		}
		if (viewPager != null) viewPager.setVisibility(View.INVISIBLE);
		if (!isFinishing()) finish();
	}

	static String positionLabel(Context ctx, int index, int count) {
		if (count <= 1) return ctx.getString(R.string.image);
		return ctx.getString(R.string.media_viewer_image_position, index + 1,
				count);
	}

	int pageCount() {
		return token != null ? 1 : attachments.size();
	}

	private void onPageShown(int page) {
		invalidateOptionsMenu();
		if (page == announcedPage) return;
		announcedPage = page;
		if (pageCount() > 1) {
			viewPager.announceForAccessibility(
					positionLabel(this, page, pageCount()));
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.image_actions, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		MenuItem save = menu.findItem(R.id.action_save_image);
		if (save != null) save.setVisible(SaveCleanCopy.canSave(visibleType()));
		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		} else if (item.getItemId() == R.id.action_save_image) {
			showSaveImageDialog();
			return true;
		} else if (item.getItemId() == R.id.action_open_elsewhere) {
			openElsewhere();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onGlobalLayout() {
		viewModel.setToolbarPosition(
				appBarLayout.getTop(), appBarLayout.getBottom()
		);
		layout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
	}

	@Override
	public void onPullStart() {
		appBarLayout.animate()
				.alpha(0f)
				.start();
	}

	@Override
	public void onPull(float progress) {
	}

	@Override
	public void onPullCancel() {
		appBarLayout.animate()
				.alpha(1f)
				.start();
	}

	@Override
	public void onPullComplete() {
		showStatusBarBeforeFinishing();
		supportFinishAfterTransition();
	}

	@Override
	public void onBackPressed() {
		showStatusBarBeforeFinishing();
		super.onBackPressed();
	}

	private void onImageClicked(@Nullable Boolean clicked) {
		if (clicked != null && clicked) {
			toggleSystemUi();
		}
	}

	private void toggleSystemUi() {
		View decorView = getWindow().getDecorView();
		if (appBarLayout.getVisibility() == VISIBLE) {
			hideSystemUi(decorView);
		} else {
			showSystemUi(decorView);
		}
	}

	private void hideSystemUi(View decorView) {
		insetsController.hide(WindowInsetsCompat.Type.systemBars());
		appBarLayout.animate()
				.translationYBy(-1 * appBarLayout.getHeight())
				.alpha(0f)
				.withEndAction(() -> appBarLayout.setVisibility(GONE))
				.start();
	}

	private void showSystemUi(View decorView) {
		insetsController.show(WindowInsetsCompat.Type.systemBars());
		appBarLayout.animate()
				.translationYBy(appBarLayout.getHeight())
				.alpha(1f)
				.withStartAction(() -> appBarLayout.setVisibility(VISIBLE))
				.start();
	}

	private void showStatusBarBeforeFinishing() {
		if (appBarLayout.getVisibility() == GONE) {
			insetsController.show(WindowInsetsCompat.Type.systemBars());
		}
	}

	private String visibleType() {
		if (token != null) {
			MediaViewer.Shown shown = MediaViewer.get(token);
			return shown == null ? "" : shown.type;
		}
		return getVisibleAttachment().getMimeType();
	}

	private void showSaveImageDialog() {
		if (token != null) {
			MediaViewer.Shown shown = MediaViewer.get(token);
			if (shown == null) {
				showError(R.string.save_image_error);
				return;
			}
			SaveCleanCopy.confirm(this, shown.type, () -> {
				MediaViewer.Shown still = MediaViewer.get(token);
				if (still == null) showError(R.string.save_image_error);
				else SaveCleanCopy.startCopy(this, still.data.clone(),
						still.type);
			});
			return;
		}
		AttachmentItem attachment = getVisibleAttachment();
		SaveCleanCopy.confirm(this, attachment.getMimeType(),
				() -> viewModel.readForSaving(attachment));
	}

	private void onImageReadForSaving(Boolean ready) {
		ImageViewModel.SavableImage image = viewModel.takeSavableImage();
		if (image == null) return;
		byte[] data = image.getData();
		if (data != null) {
			SaveCleanCopy.startCopy(this, data, image.getMimeType());
			return;
		}
		showError(R.string.save_image_error);
	}

	private void openElsewhere() {
		if (token != null) {
			MediaViewer.Shown shown = MediaViewer.get(token);
			if (shown == null) {
				showError(R.string.media_document_no_app);
				return;
			}
			handOff(shown.data.clone(), shown.type);
			return;
		}
		viewModel.readForOpening(getVisibleAttachment());
	}

	private void onImageReadForOpening(Boolean ready) {
		ImageViewModel.SavableImage image = viewModel.takeOpenableImage();
		if (image == null) return;
		byte[] data = image.getData();
		String type = data == null ? null : ExternalViewerTypes
				.typeForContent(image.getMimeType(), data);
		if (data == null || type == null) {
			if (data != null) Arrays.fill(data, (byte) 0);
			showError(R.string.media_document_open_failed);
			return;
		}
		handOff(data, type);
	}

	private void handOff(byte[] copy, String type) {
		ExternalHandoff.openCopy(this, ioExecutor, copy, type,
				() -> showError(R.string.media_document_no_app));
	}

	private void showError(@StringRes int res) {
		new ZerionSnackbarBuilder()
				.setBackgroundColor(R.color.zerion_error_fill)
				.make(layout, res, LENGTH_LONG)
				.show();
	}

	private AttachmentItem getVisibleAttachment() {
		return attachments.get(viewPager.getCurrentItem());
	}

	private class ImagePagerAdapter extends FragmentStateAdapter {

		private boolean isFirst = true;

		private ImagePagerAdapter() {
			super(ImageActivity.this);
		}

		@NotNull
		@Override
		public Fragment createFragment(int position) {
			Fragment f = token != null
					? ImageFragment.newInstance(token, isFirst)
					: ImageFragment.newInstance(attachments.get(position),
							requireNonNull(conversationMessageId), isFirst,
							position, attachments.size());
			isFirst = false;
			return f;
		}

		@Override
		public int getItemCount() {
			return pageCount();
		}
	}

}
