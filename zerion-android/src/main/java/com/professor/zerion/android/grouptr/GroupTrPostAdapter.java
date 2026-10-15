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
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.professor.zerion.R;
import com.professor.zerion.android.util.SafeImageDecoder;

import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrPost;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.zerionproject.core.util.StringUtils.toHexString;

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

		void onPostLongClick(GroupTrPost p);

		boolean onPostClick(GroupTrPost p);

		boolean isSelected(GroupTrPost p);

		String quoteAuthor(GroupTrPost p);

		void onQuoteClick(byte[] postId);
	}

	private static void showLinkDialog(android.view.View v, String url) {
		android.content.Context c = v.getContext();
		if (c instanceof androidx.fragment.app.FragmentActivity) {
			com.professor.zerion.android.widget.LinkDialogFragment f =
					com.professor.zerion.android.widget.LinkDialogFragment
							.newInstance(url);
			f.show(((androidx.fragment.app.FragmentActivity) c)
					.getSupportFragmentManager(), f.getUniqueTag());
		}
	}

	static String excerpt(android.content.Context ctx, GroupTrPost p) {
		GroupTrBody.Parsed parsed = GroupTrBody.parse(p.getBody());
		switch (parsed.kind) {
			case VOICE:
				return ctx.getString(R.string.voice_message);
			case IMAGE:
				return ctx.getString(R.string.image);
			case VIDEO:
				return ctx.getString(R.string.video);
			default:
				String t = parsed.text == null ? "" : parsed.text;
				int nl = t.indexOf('\n');
				return nl >= 0 ? t.substring(0, nl) : t;
		}
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

	private static final java.util.concurrent.Executor DECODE_EXECUTOR =
			java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
				Thread t = new Thread(r, "GroupTrDecode");
				t.setDaemon(true);
				return t;
			});

	GroupTrPostAdapter(Callback cb) {
		this.cb = cb;
	}

	void setPosts(List<GroupTrPost> newPosts) {
		List<GroupTrPost> old = new ArrayList<>(posts);
		DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
			@Override
			public int getOldListSize() {
				return old.size();
			}

			@Override
			public int getNewListSize() {
				return newPosts.size();
			}

			@Override
			public boolean areItemsTheSame(int oldPos, int newPos) {
				return samePost(old.get(oldPos), newPosts.get(newPos));
			}

			@Override
			public boolean areContentsTheSame(int oldPos, int newPos) {
				return true;
			}
		});
		posts.clear();
		posts.addAll(newPosts);
		diff.dispatchUpdatesTo(this);
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
		GroupTrMediaCache.evictPost(p);
		for (int i = 0; i < posts.size(); i++) {
			if (samePost(posts.get(i), p)) {
				posts.remove(i);
				notifyItemRemoved(i);
				return;
			}
		}
	}

	static String key(GroupTrPost p) {
		byte[] id = p.getPostId();
		if (id != null) return toHexString(id);
		return p.getEpoch() + ":" + p.getTimestamp() + ":"
				+ toHexString(p.getSenderPubKey());
	}

	@Nullable
	GroupTrPost find(String key) {
		for (GroupTrPost p : posts) {
			if (key(p).equals(key)) return p;
		}
		return null;
	}

	int positionOf(String key) {
		for (int i = 0; i < posts.size(); i++) {
			if (key(posts.get(i)).equals(key)) return i;
		}
		return -1;
	}

	List<GroupTrPost> currentPosts() {
		return new ArrayList<>(posts);
	}

	void refresh(String key) {
		int i = positionOf(key);
		if (i >= 0) notifyItemChanged(i);
	}

	void removePosts(Collection<byte[]> postIds) {
		Set<String> gone = new HashSet<>();
		for (byte[] id : postIds) gone.add(toHexString(id));
		for (int i = posts.size() - 1; i >= 0; i--) {
			if (gone.contains(key(posts.get(i)))) {
				posts.remove(i);
				notifyItemRemoved(i);
			}
		}
		for (int i = 0; i < posts.size(); i++) {
			byte[] quoted = GroupTrBody.replyTarget(posts.get(i).getBody());
			if (quoted != null && gone.contains(toHexString(quoted))) {
				notifyItemChanged(i);
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
		@Nullable
		private Object imageToken;

		PostHolder(View v, int viewType) {
			super(v);
			this.viewType = viewType;
			v.setBackgroundResource(R.drawable.list_item_background_selectable);
		}

		void bind(GroupTrPost p) {
			View sender = itemView.findViewById(R.id.senderName);
			if (sender instanceof TextView) {
				cb.bindSender((TextView) sender, p);
			}
			itemView.setActivated(cb.isSelected(p));
			selectable(itemView, p);
			selectable(itemView.findViewById(R.id.bubble), p);
			selectable(itemView.findViewById(R.id.mediaBubble), p);
			selectable(itemView.findViewById(R.id.postText), p);
			selectable(itemView.findViewById(R.id.imageView), p);
			selectable(itemView.findViewById(R.id.voicePlayButton), p);
			selectable(itemView.findViewById(R.id.quoteBlock), p);
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

		private void selectable(@Nullable View v, GroupTrPost p) {
			if (v == null) return;
			v.setOnLongClickListener(x -> {
				cb.onPostLongClick(p);
				return true;
			});
			if (v == itemView || v.getId() == R.id.bubble) {
				v.setOnClickListener(x -> cb.onPostClick(p));
			}
		}

		private void bindQuote(GroupTrPost p, GroupTrBody.Parsed parsed) {
			View block = itemView.findViewById(R.id.quoteBlock);
			if (block == null) return;
			if (parsed.replyTo == null) {
				block.setVisibility(View.GONE);
				return;
			}
			byte[] target = parsed.replyTo;
			TextView author = itemView.findViewById(R.id.quoteAuthor);
			TextView excerpt = itemView.findViewById(R.id.quoteText);
			GroupTrPost quoted = find(toHexString(target));
			android.content.Context ctx = itemView.getContext();
			if (quoted == null) {
				author.setVisibility(View.GONE);
				excerpt.setText(R.string.reply_original_unavailable);
				block.setContentDescription(
						ctx.getString(R.string.reply_original_unavailable));
			} else {
				String name = cb.quoteAuthor(quoted);
				String line = excerpt(ctx, quoted);
				author.setVisibility(View.VISIBLE);
				author.setText(name);
				excerpt.setText(line);
				block.setContentDescription(name + ", " + line);
			}
			block.setVisibility(View.VISIBLE);
			block.setOnClickListener(v -> {
				if (cb.onPostClick(p)) return;
				cb.onQuoteClick(target);
			});
		}

		private void bindText(GroupTrPost p, GroupTrBody.Parsed parsed) {
			bindQuote(p, parsed);
			TextView body = itemView.findViewById(R.id.postText);
			TextView time = itemView.findViewById(R.id.postTime);
			if (!com.professor.zerion.android.channel.ChannelInviteSpanUtil
					.apply(body, parsed.text)) {
				body.setText(parsed.text);
				if (parsed.text != null && (parsed.text.indexOf('.') >= 0
						|| parsed.text.indexOf(':') >= 0)) {
					android.text.util.Linkify.addLinks(body,
							android.text.util.Linkify.WEB_URLS);
					com.professor.zerion.android.util.UiUtils
							.makeLinksClickable(body,
									url -> showLinkDialog(body, url));
				}
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
			playBtn.setOnClickListener(v -> {
				if (cb.onPostClick(p)) return;
				cb.onVoiceClick(playBtn, audio);
			});
		}

		private void bindImage(GroupTrPost p, GroupTrBody.Parsed parsed) {
			ImageView img = itemView.findViewById(R.id.imageView);
			TextView time = itemView.findViewById(R.id.imageTime);
			mediaImage = img;
			time.setText(cb.formatTime(p.getTimestamp()));
			byte[] bytes = parsed.payload;
			String mime = parsed.mime;
			img.setOnClickListener(v -> {
				if (cb.onPostClick(p)) return;
				cb.onImageClick(bytes, mime);
			});
			String cacheKey = GroupTrMediaCache.key(p,
					GroupTrMediaCache.IMAGE);
			Bitmap cached = GroupTrMediaCache.get(cacheKey);
			if (cached != null && !cached.isRecycled()) {
				img.setImageBitmap(cached);
				return;
			}
			img.setImageDrawable(null);
			Object token = new Object();
			imageToken = token;
			DECODE_EXECUTOR.execute(() -> {
				Bitmap bmp = SafeImageDecoder.decode(bytes, 1024);
				if (bmp == null) return;
				GroupTrMediaCache.put(cacheKey, bmp);
				img.post(() -> {
					if (imageToken == token && mediaImage == img) {
						img.setImageBitmap(bmp);
					}
				});
			});
		}

		private void bindVideo(GroupTrPost p, GroupTrBody.Parsed parsed) {
			ImageView thumb = itemView.findViewById(R.id.videoThumb);
			TextView duration = itemView.findViewById(R.id.videoDuration);
			TextView time = itemView.findViewById(R.id.videoTime);
			mediaImage = thumb;
			duration.setText(cb.formatDuration(parsed.durationMs));
			time.setText(cb.formatTime(p.getTimestamp()));
			byte[] bytes = parsed.payload;
			String mime = parsed.mime;
			String cacheKey = GroupTrMediaCache.key(p,
					GroupTrMediaCache.VIDEO);
			Bitmap cachedThumb = GroupTrMediaCache.get(cacheKey);
			if (cachedThumb != null && !cachedThumb.isRecycled()) {
				thumb.setImageBitmap(cachedThumb);
			} else {
				thumb.setImageDrawable(null);
				Object token = new Object();
				imageToken = token;
				DECODE_EXECUTOR.execute(() -> {
					Bitmap thumbBmp = cb.videoThumb(bytes);
					if (thumbBmp == null) return;
					GroupTrMediaCache.put(cacheKey, thumbBmp);
					thumb.post(() -> {
						if (imageToken == token && mediaImage == thumb) {
							thumb.setImageBitmap(thumbBmp);
						}
					});
				});
			}
			View bubble = itemView.findViewById(R.id.mediaBubble);
			bubble.setOnClickListener(v -> {
				if (cb.onPostClick(p)) return;
				cb.onVideoClick(bytes, mime);
			});
		}

		void recycle() {
			itemView.setActivated(false);
			imageToken = null;
			if (mediaImage != null) {
				mediaImage.setImageDrawable(null);
				mediaImage = null;
			}
		}
	}
}
