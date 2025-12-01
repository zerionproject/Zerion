package com.professor.zerion.android.conversation;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.attachment.AttachmentItem;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import static com.professor.zerion.android.conversation.ConversationActivity.CONTACT_ID;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class AllMediaActivity extends ZerionActivity {

	private static final int TAB_ALL = 0;
	private static final int TAB_IMAGES = 1;
	private static final int TAB_DOCUMENTS = 2;

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private ConversationViewModel viewModel;
	private ContactId contactId;
	private RecyclerView mediaGrid;
	private MediaAdapter adapter;
	private TabLayout tabLayout;

	private int currentTab = TAB_ALL;

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

		setContentView(R.layout.activity_all_media);

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}

		tabLayout = findViewById(R.id.tab_layout);
		tabLayout.addTab(tabLayout.newTab().setText(R.string.all));
		tabLayout.addTab(tabLayout.newTab().setText(R.string.images));
		tabLayout.addTab(tabLayout.newTab().setText(R.string.documents));

		tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
			@Override
			public void onTabSelected(TabLayout.Tab tab) {
				currentTab = tab.getPosition();
				loadMedia();
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
		List<MediaItem> mediaItems = new ArrayList<>();
		adapter.setItems(mediaItems);
	}

	@Override
	public boolean onSupportNavigateUp() {
		onBackPressed();
		return true;
	}

	private static class MediaItem {
		final AttachmentItem attachment;
		final boolean isImage;
		final long timestamp;

		MediaItem(AttachmentItem attachment, boolean isImage, long timestamp) {
			this.attachment = attachment;
			this.isImage = isImage;
			this.timestamp = timestamp;
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
			private final ImageView documentIcon;
			private final TextView documentName;

			MediaViewHolder(@NonNull View itemView) {
				super(itemView);
				thumbnail = itemView.findViewById(R.id.thumbnail);
				documentOverlay = itemView.findViewById(R.id.document_overlay);
				documentIcon = itemView.findViewById(R.id.document_icon);
				documentName = itemView.findViewById(R.id.document_name);
			}

			void bind(MediaItem item) {
				if (item.isImage) {
					documentOverlay.setVisibility(View.GONE);
					thumbnail.setVisibility(View.VISIBLE);
					thumbnail.setImageResource(R.drawable.ic_image);
				} else {
					thumbnail.setVisibility(View.GONE);
					documentOverlay.setVisibility(View.VISIBLE);

					String fileName = item.attachment.getHeader().getContentType();
					if (fileName.contains("/")) {
						fileName = fileName.substring(fileName.lastIndexOf("/") + 1);
					}
					documentName.setText(fileName);
				}

				itemView.setOnClickListener(v -> {
					Intent intent = new Intent(AllMediaActivity.this, ImageActivity.class);
					startActivity(intent);
				});
			}
		}
	}
}
