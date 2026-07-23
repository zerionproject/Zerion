package com.professor.zerion.android.grouptr;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.recyclerview.widget.RecyclerView;

import com.professor.zerion.R;
import com.professor.zerion.android.util.SafeImageDecoder;

import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrPost;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class GroupTrPostAdapter
		extends RecyclerView.Adapter<GroupTrPostAdapter.PostHolder> {

	interface Callback {
		boolean isMine(GroupTrPost p);

		void bindSender(TextView tv, GroupTrPost p);

		void onImageClick(byte[] bytes, @Nullable String mime);

		void onVideoClick(byte[] bytes, @Nullable String mime);

		void onVoiceClick(AppCompatImageButton btn, byte[] audio);

		@Nullable
		Bitmap videoThumb(byte[] videoBytes);

		String formatTime(long ts);

		String formatDuration(long ms);
	}

	private static final int TEXT_IN = 0;
	private static final int TEXT_OUT = 1;
	private static final int VOICE_IN = 2;
	private static final int VOICE_OUT = 3;
	private static final int IMAGE_IN = 4;
	private static final int IMAGE_OUT = 5;
	private static final int VIDEO_IN = 6;
	private static final int VIDEO_OUT = 7;

	private final Callback cb;
	private final List<GroupTrPost> posts = new ArrayList<>();

	GroupTrPostAdapter(Callback cb) {
		this.cb = cb;
	}

	void setPosts(List<GroupTrPost> newPosts) {
		posts.clear();
		posts.addAll(newPosts);
		notifyDataSetChanged();
	}

	void addPost(GroupTrPost p) {
		posts.add(p);
		notifyItemInserted(posts.size() - 1);
	}

	boolean contains(GroupTrPost p) {
		for (GroupTrPost e : posts) {
			if (samePost(e, p)) return true;
		}
		return false;
	}

	void removePost(GroupTrPost p) {
		for (int i = 0; i < posts.size(); i++) {
			if (samePost(posts.get(i), p)) {
				posts.remove(i);
				notifyItemRemoved(i);
				return;
			}
		}
	}

	static boolean samePost(GroupTrPost a, GroupTrPost b) {
		return a.getEpoch() == b.getEpoch()
				&& a.getTimestamp() == b.getTimestamp()
				&& Arrays.equals(a.getSenderPubKey(), b.getSenderPubKey())
				&& Arrays.equals(a.getBody(), b.getBody());
	}

	@Override
	public int getItemViewType(int position) {
		GroupTrPost p = posts.get(position);
		boolean mine = cb.isMine(p);
		switch (GroupTrBody.kindOf(p.getBody())) {
			case VOICE:
				return mine ? VOICE_OUT : VOICE_IN;
			case IMAGE:
				return mine ? IMAGE_OUT : IMAGE_IN;
			case VIDEO:
				return mine ? VIDEO_OUT : VIDEO_IN;
			default:
				return mine ? TEXT_OUT : TEXT_IN;
		}
	}

	@Override
	public int getItemCount() {
		return posts.size();
	}

	@NonNull
	@Override
	public PostHolder onCreateViewHolder(@NonNull ViewGroup parent,
			int viewType) {
		LayoutInflater inf = LayoutInflater.from(parent.getContext());
		int layout;
		switch (viewType) {
			case TEXT_OUT:
				layout = R.layout.list_item_grouptr_post_out;
				break;
			case TEXT_IN:
				layout = R.layout.list_item_grouptr_post_in;
				break;
			case VOICE_OUT:
				layout = R.layout.list_item_grouptr_voice_out;
				break;
			case VOICE_IN:
				layout = R.layout.list_item_grouptr_voice_in;
				break;
			case IMAGE_OUT:
				layout = R.layout.list_item_grouptr_image_out;
				break;
			case IMAGE_IN:
				layout = R.layout.list_item_grouptr_image_in;
				break;
			case VIDEO_OUT:
				layout = R.layout.list_item_grouptr_video_out;
				break;
			default:
				layout = R.layout.list_item_grouptr_video_in;
				break;
		}
		View v = inf.inflate(layout, parent, false);
		return new PostHolder(v, viewType);
	}

	@Override
	public void onBindViewHolder(@NonNull PostHolder h, int position) {
		h.bind(posts.get(position));
	}

	@Override
	public void onViewRecycled(@NonNull PostHolder h) {
		h.recycle();
	}

	class PostHolder extends RecyclerView.ViewHolder {
		private final int viewType;
		@Nullable
		private ImageView mediaImage;

		PostHolder(View v, int viewType) {
			super(v);
			this.viewType = viewType;
		}

		void bind(GroupTrPost p) {
			View sender = itemView.findViewById(R.id.senderName);
			if (sender instanceof TextView) {
				cb.bindSender((TextView) sender, p);
			}
			GroupTrBody.Parsed parsed = GroupTrBody.parse(p.getBody());
			switch (viewType) {
				case VOICE_IN:
				case VOICE_OUT:
					bindVoice(p, parsed);
					break;
				case IMAGE_IN:
				case IMAGE_OUT:
					bindImage(p, parsed);
					break;
				case VIDEO_IN:
				case VIDEO_OUT:
					bindVideo(p, parsed);
					break;
				default:
					bindText(p, parsed);
					break;
			}
		}

		private void bindText(GroupTrPost p, GroupTrBody.Parsed parsed) {
			TextView body = itemView.findViewById(R.id.postText);
			TextView time = itemView.findViewById(R.id.postTime);
			if (!com.professor.zerion.android.channel.ChannelInviteSpanUtil
					.apply(body, parsed.text)) {
				body.setText(parsed.text);
			}
			time.setText(cb.formatTime(p.getTimestamp()));
		}

		private void bindVoice(GroupTrPost p, GroupTrBody.Parsed parsed) {
			TextView dur = itemView.findViewById(R.id.voiceDuration);
			TextView time = itemView.findViewById(R.id.voiceTime);
			AppCompatImageButton playBtn =
					itemView.findViewById(R.id.voicePlayButton);
			dur.setText(cb.formatDuration(parsed.durationMs));
			time.setText(cb.formatTime(p.getTimestamp()));
			byte[] audio = parsed.payload;
			playBtn.setImageResource(R.drawable.ic_play_arrow_24dp);
			playBtn.setOnClickListener(v -> cb.onVoiceClick(playBtn, audio));
		}

		private void bindImage(GroupTrPost p, GroupTrBody.Parsed parsed) {
			ImageView img = itemView.findViewById(R.id.imageView);
			TextView time = itemView.findViewById(R.id.imageTime);
			mediaImage = img;
			Bitmap bmp = SafeImageDecoder.decode(parsed.payload, 1024);
			if (bmp != null) img.setImageBitmap(bmp);
			time.setText(cb.formatTime(p.getTimestamp()));
			byte[] bytes = parsed.payload;
			String mime = parsed.mime;
			img.setOnClickListener(v -> cb.onImageClick(bytes, mime));
		}

		private void bindVideo(GroupTrPost p, GroupTrBody.Parsed parsed) {
			ImageView thumb = itemView.findViewById(R.id.videoThumb);
			TextView duration = itemView.findViewById(R.id.videoDuration);
			TextView time = itemView.findViewById(R.id.videoTime);
			mediaImage = thumb;
			duration.setText(cb.formatDuration(parsed.durationMs));
			time.setText(cb.formatTime(p.getTimestamp()));
			Bitmap thumbBmp = cb.videoThumb(parsed.payload);
			if (thumbBmp != null) thumb.setImageBitmap(thumbBmp);
			byte[] bytes = parsed.payload;
			String mime = parsed.mime;
			View bubble = itemView.findViewById(R.id.mediaBubble);
			bubble.setOnClickListener(v -> cb.onVideoClick(bytes, mime));
		}

		void recycle() {
			if (mediaImage != null) {
				mediaImage.setImageDrawable(null);
				mediaImage = null;
			}
		}
	}
}
