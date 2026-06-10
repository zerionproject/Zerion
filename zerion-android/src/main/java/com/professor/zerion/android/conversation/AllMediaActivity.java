package com.professor.zerion.android.conversation;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.tabs.TabLayout;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.attachment.AttachmentRetriever;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.Attachment;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.briar.api.messaging.PrivateMessageHeader;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import static com.bumptech.glide.load.engine.DiskCacheStrategy.NONE;
import static com.professor.zerion.android.conversation.ConversationActivity.CONTACT_ID;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class AllMediaActivity extends ZerionActivity {

	private static final int TAB_ALL = 0;
	private static final int TAB_IMAGES = 1;
	private static final int TAB_DOCUMENTS = 2;

	@Inject
	ViewModelProvider.Factory viewModelFactory;
	@Inject
	ConversationManager conversationManager;
	@Inject
	@DatabaseExecutor
	Executor dbExecutor;

	private ConversationViewModel viewModel;
	private AttachmentRetriever attachmentRetriever;
	private ContactId contactId;
	private RecyclerView mediaGrid;
	private MediaAdapter adapter;
	private TabLayout tabLayout;
	private ProgressBar loadingIndicator;
	private LinearLayout emptyState;
	private TextView emptyText;

	private int currentTab = TAB_ALL;
	private final List<MediaItem> allMediaItems = new ArrayList<>();

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(ConversationViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);

		Intent i = getIntent();
		int id = i.getIntExtra(CONTACT_ID, -1);
		if (id == -1) throw new IllegalStateException("Contact ID required");
		contactId = new ContactId(id);

		viewModel.setContactId(contactId);
		attachmentRetriever = viewModel.getAttachmentRetriever();

		setContentView(R.layout.activity_all_media);

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}

		loadingIndicator = findViewById(R.id.loading_indicator);
		emptyState = findViewById(R.id.empty_state);
		emptyText = findViewById(R.id.empty_text);

		tabLayout = findViewById(R.id.tab_layout);
		tabLayout.addTab(tabLayout.newTab().setText(R.string.all));
		tabLayout.addTab(tabLayout.newTab().setText(R.string.media));
		tabLayout.addTab(tabLayout.newTab().setText(R.string.documents));

		tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
			@Override
			public void onTabSelected(TabLayout.Tab tab) {
				currentTab = tab.getPosition();
				filterAndDisplayMedia();
			}

			@Override
			public void onTabUnselected(TabLayout.Tab tab) {}

			@Override
			public void onTabReselected(TabLayout.Tab tab) {}
		});

		mediaGrid = findViewById(R.id.media_grid);
		mediaGrid.setLayoutManager(new GridLayoutManager(this, 3));
		adapter = new MediaAdapter();
		mediaGrid.setAdapter(adapter);

		loadMedia();
	}

	private void loadMedia() {
		showLoading(true);

		dbExecutor.execute(() -> {
			try {
				Collection<ConversationMessageHeader> headers =
						conversationManager.getMessageHeaders(contactId);

				List<MediaItem> mediaItems = new ArrayList<>();

				for (ConversationMessageHeader header : headers) {
					if (header instanceof PrivateMessageHeader) {
						PrivateMessageHeader pmh = (PrivateMessageHeader) header;
						List<AttachmentHeader> attachments = pmh.getAttachmentHeaders();

						for (AttachmentHeader ah : attachments) {
							String contentType = ah.getContentType();
							boolean isImage = contentType.startsWith("image/");
							boolean isVideo = contentType.startsWith("video/");
							boolean isDocument = !isImage && !isVideo;

							MediaItem item = new MediaItem(
									ah,
									isImage,
									isVideo,
									isDocument,
									pmh.getTimestamp(),
									pmh.getId()
							);
							mediaItems.add(item);
						}
					}
				}

				Collections.sort(mediaItems, (a, b) ->
						Long.compare(b.timestamp, a.timestamp));

				runOnUiThread(() -> {
					allMediaItems.clear();
					allMediaItems.addAll(mediaItems);
					filterAndDisplayMedia();
					showLoading(false);
				});

			} catch (DbException e) {
				runOnUiThread(() -> {
					showLoading(false);
					showEmpty(true);
				});
			}
		});
	}

	private void filterAndDisplayMedia() {
		List<MediaItem> filtered = new ArrayList<>();

		for (MediaItem item : allMediaItems) {
			switch (currentTab) {
				case TAB_ALL:
					filtered.add(item);
					break;
				case TAB_IMAGES:
					if (item.isImage || item.isVideo) {
						filtered.add(item);
					}
					break;
				case TAB_DOCUMENTS:
					if (item.isDocument) {
						filtered.add(item);
					}
					break;
			}
		}

		adapter.setItems(filtered);
		showEmpty(filtered.isEmpty());
		updateEmptyText();
	}

	private void updateEmptyText() {
		switch (currentTab) {
			case TAB_ALL:
				emptyText.setText(R.string.no_media);
				break;
			case TAB_IMAGES:
				emptyText.setText(R.string.no_media);
				break;
			case TAB_DOCUMENTS:
				emptyText.setText(R.string.no_documents);
				break;
		}
	}

	private void showLoading(boolean show) {
		loadingIndicator.setVisibility(show ? View.VISIBLE : View.GONE);
		if (show) {
			mediaGrid.setVisibility(View.GONE);
			emptyState.setVisibility(View.GONE);
		}
	}

	private void showEmpty(boolean show) {
		emptyState.setVisibility(show ? View.VISIBLE : View.GONE);
		mediaGrid.setVisibility(show ? View.GONE : View.VISIBLE);
	}

	@Override
	public boolean onSupportNavigateUp() {
		onBackPressed();
		return true;
	}

	private static class MediaItem {
		final AttachmentHeader header;
		final boolean isImage;
		final boolean isVideo;
		final boolean isDocument;
		final long timestamp;
		final MessageId messageId;
		AttachmentItem attachmentItem;

		MediaItem(AttachmentHeader header, boolean isImage, boolean isVideo,
				boolean isDocument, long timestamp, MessageId messageId) {
			this.header = header;
			this.isImage = isImage;
			this.isVideo = isVideo;
			this.isDocument = isDocument;
			this.timestamp = timestamp;
			this.messageId = messageId;
		}
	}

	private class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.MediaViewHolder> {

		private List<MediaItem> items = new ArrayList<>();

		void setItems(List<MediaItem> newItems) {
			items = newItems;
			notifyDataSetChanged();
		}

		@NonNull
		@Override
		public MediaViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			View view = LayoutInflater.from(parent.getContext())
					.inflate(R.layout.item_media_thumbnail, parent, false);
			return new MediaViewHolder(view);
		}

		@Override
		public void onBindViewHolder(@NonNull MediaViewHolder holder, int position) {
			MediaItem item = items.get(position);
			holder.bind(item);
		}

		@Override
		public int getItemCount() {
			return items.size();
		}

		class MediaViewHolder extends RecyclerView.ViewHolder {
			private final ImageView thumbnail;
			private final LinearLayout documentOverlay;
			private final TextView documentName;

			MediaViewHolder(@NonNull View itemView) {
				super(itemView);
				thumbnail = itemView.findViewById(R.id.thumbnail);
				documentOverlay = itemView.findViewById(R.id.document_overlay);
				documentName = itemView.findViewById(R.id.document_name);
			}

			void bind(MediaItem item) {
				if (item.isImage || item.isVideo) {
					documentOverlay.setVisibility(View.GONE);
					thumbnail.setVisibility(View.VISIBLE);

					if (item.isVideo) {
						thumbnail.setImageResource(R.drawable.ic_video);
					} else {
						thumbnail.setImageResource(R.drawable.ic_image);
					}

					loadThumbnail(item, thumbnail);

				} else {
					thumbnail.setVisibility(View.GONE);
					documentOverlay.setVisibility(View.VISIBLE);

					String contentType = item.header.getContentType();
					String extension = getExtensionFromMimeType(contentType);
					documentName.setText(extension.toUpperCase());
				}

				itemView.setOnClickListener(v -> openMedia(item));
			}

			private void loadThumbnail(MediaItem item, ImageView imageView) {
				Glide.with(imageView)
						.load(item.header)
						.diskCacheStrategy(NONE)
						.error(R.drawable.ic_image)
						.centerCrop()
						.into(imageView);

				dbExecutor.execute(() -> {
					try {
						attachmentRetriever.cacheAttachmentItemWithSize(
								item.messageId, item.header);

						Attachment att = attachmentRetriever.getMessageAttachment(item.header);
						AttachmentItem ai = attachmentRetriever.createAttachmentItem(att, true);
						item.attachmentItem = ai;
					} catch (DbException ignored) {
					}
				});
			}
		}
	}

	private void openMedia(MediaItem item) {
		if (item.attachmentItem == null) {
			dbExecutor.execute(() -> {
				try {
					Attachment att = attachmentRetriever.getMessageAttachment(item.header);
					AttachmentItem ai = attachmentRetriever.createAttachmentItem(att, true);
					item.attachmentItem = ai;
					runOnUiThread(() -> launchMediaViewer(item));
				} catch (DbException ignored) {
				}
			});
		} else {
			launchMediaViewer(item);
		}
	}

	private void launchMediaViewer(MediaItem item) {
		if (item.attachmentItem == null) return;

		if (item.isImage) {
			ArrayList<AttachmentItem> attachments = new ArrayList<>();
			attachments.add(item.attachmentItem);

			Intent intent = new Intent(this, ImageActivity.class);
			intent.putParcelableArrayListExtra(ImageActivity.ATTACHMENTS, attachments);
			intent.putExtra(ImageActivity.ATTACHMENT_POSITION, 0);
			intent.putExtra(ImageActivity.NAME, "");
			intent.putExtra(ImageActivity.DATE, item.timestamp);
			intent.putExtra(ImageActivity.ITEM_ID, item.messageId.getBytes());
			startActivity(intent);
		} else if (item.isVideo) {
			Intent intent = new Intent(this, VideoPlayerActivity.class);
			intent.putExtra(VideoPlayerActivity.ATTACHMENT, item.attachmentItem);
			intent.putExtra(VideoPlayerActivity.ITEM_ID, item.messageId.getBytes());
			startActivity(intent);
		}
	}

	private String getExtensionFromMimeType(@Nullable String mimeType) {
		if (mimeType == null) return "FILE";
		if (mimeType.contains("/")) {
			String ext = mimeType.substring(mimeType.lastIndexOf("/") + 1);
			if (ext.length() > 4) {
				return ext.substring(0, 4);
			}
			return ext;
		}
		return "FILE";
	}
}
