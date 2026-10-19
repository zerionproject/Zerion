package com.professor.zerion.android.homelist;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;
import com.google.android.material.imageview.ShapeableImageView;
import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.app.api.attachment.AttachmentHeader;

import java.util.Locale;

import javax.annotation.Nullable;

import androidx.annotation.ColorInt;
import androidx.recyclerview.widget.RecyclerView;

import static com.professor.zerion.android.util.UiUtils.resolveColorAttribute;

@NotNullByDefault
final class HomeRowViewHolder extends RecyclerView.ViewHolder {

	private final TextView avatarText;
	private final ShapeableImageView avatarImage;
	private final View presence;
	private final TextView name;
	private final TextView time;
	private final TextView preview;
	private final ImageView pinned;
	private final ImageView muted;
	private final TextView unread;
	private final HomeAvatarPalette palette;
	@ColorInt
	private final int accentText;
	@ColorInt
	private final int mutedText;
	@ColorInt
	private final int strongText;
	private boolean showsPhoto = false;

	HomeRowViewHolder(View v, HomeAvatarPalette palette) {
		super(v);
		this.palette = palette;
		avatarText = v.findViewById(R.id.homeRowAvatarText);
		avatarImage = v.findViewById(R.id.homeRowAvatarImage);
		presence = v.findViewById(R.id.homeRowPresence);
		name = v.findViewById(R.id.homeRowName);
		time = v.findViewById(R.id.homeRowTime);
		preview = v.findViewById(R.id.homeRowPreview);
		pinned = v.findViewById(R.id.homeRowPinned);
		muted = v.findViewById(R.id.homeRowMuted);
		unread = v.findViewById(R.id.homeRowUnread);
		Context ctx = v.getContext();
		accentText = resolveColorAttribute(ctx, R.attr.zerionColorAccentText);
		mutedText = resolveColorAttribute(ctx,
				R.attr.zerionColorOnSurfaceMuted);
		strongText = resolveColorAttribute(ctx, R.attr.zerionColorOnSurface);
	}

	void bind(HomeRow row, long now) {
		Context ctx = itemView.getContext();
		name.setText(row.getName());
		bindAvatar(row);

		String when = HomeRowTime.format(ctx, row.getTime(), now,
				row.isHourPrecision());
		time.setText(when);
		time.setVisibility(when.isEmpty() ? View.GONE : View.VISIBLE);

		boolean hasUnread = row.getUnread() > 0;
		time.setTextColor(hasUnread ? accentText : mutedText);
		String shown = previewText(ctx, row);
		preview.setText(shown);
		preview.setTextColor(hasUnread ? strongText : mutedText);
		if (hasUnread) {
			unread.setText(unreadLabel(ctx, row.getUnread()));
			unread.setVisibility(View.VISIBLE);
		} else {
			unread.setVisibility(View.GONE);
		}
		muted.setVisibility(row.isMuted() ? View.VISIBLE : View.GONE);
		pinned.setVisibility(row.isPinned() ? View.VISIBLE : View.GONE);

		switch (row.getPresence()) {
			case ONLINE:
				presence.setVisibility(View.VISIBLE);
				presence.setBackgroundResource(R.drawable.bg_presence_online);
				break;
			case OFFLINE:
				presence.setVisibility(View.VISIBLE);
				presence.setBackgroundResource(R.drawable.bg_presence_offline);
				break;
			default:
				presence.setVisibility(View.GONE);
				break;
		}

		itemView.setContentDescription(describe(ctx, row, when, shown));
	}

	static String previewText(Context ctx, HomeRow row) {
		String text = row.getPreview();
		if (text == null || text.isEmpty()) return "";
		String sender = row.getSender();
		if (sender == null || sender.isEmpty()) return text;
		return ctx.getString(R.string.home_row_sender_preview, sender, text);
	}

	static String unreadLabel(Context ctx, int count) {
		if (count > HomeRow.UNREAD_CAP) {
			return ctx.getString(R.string.home_row_unread_more,
					HomeRow.UNREAD_CAP);
		}
		return String.format(Locale.getDefault(), "%d", count);
	}

	static String describe(Context ctx, HomeRow row, String when,
			String shown) {
		String out = row.getName();
		if (row.getUnread() > 0) {
			out = join(ctx, out, ctx.getResources().getQuantityString(
					R.plurals.home_row_unread_description, row.getUnread(),
					row.getUnread()));
		}
		if (!when.isEmpty()) out = join(ctx, out, when);
		if (!shown.isEmpty()) out = join(ctx, out, shown);
		if (row.isPinned()) {
			out = join(ctx, out, ctx.getString(R.string.home_row_pinned));
		}
		if (row.isMuted()) {
			out = join(ctx, out, ctx.getString(R.string.home_row_muted));
		}
		if (row.getPresence() == HomeRow.Presence.ONLINE) {
			out = join(ctx, out, ctx.getString(R.string.online));
		} else if (row.getPresence() == HomeRow.Presence.OFFLINE) {
			out = join(ctx, out, ctx.getString(R.string.offline));
		}
		return out;
	}

	private static String join(Context ctx, String a, String b) {
		return ctx.getString(R.string.home_row_parts, a, b);
	}

	private void bindAvatar(HomeRow row) {
		AttachmentHeader photo = row.getAvatar();
		if (photo != null) {
			showsPhoto = true;
			avatarText.setVisibility(View.GONE);
			avatarImage.setVisibility(View.VISIBLE);
			Glide.with(avatarImage)
					.load(photo)
					.diskCacheStrategy(DiskCacheStrategy.NONE)
					.centerCrop()
					.signature(new ObjectKey(photo.getMessageId().getBytes()))
					.into(avatarImage);
			return;
		}
		if (showsPhoto) {
			Glide.with(avatarImage).clear(avatarImage);
			showsPhoto = false;
		}
		avatarImage.setVisibility(View.GONE);
		avatarText.setVisibility(View.VISIBLE);
		avatarText.setText(initial(row.getName()));
		avatarText.setBackgroundTintList(ColorStateList.valueOf(
				palette.fillFor(row.getKey())));
		avatarText.setTextColor(palette.getLabel());
	}

	static String initial(@Nullable String name) {
		if (name == null) return "?";
		String trimmed = name.trim();
		if (trimmed.isEmpty()) return "?";
		int cp = trimmed.codePointAt(0);
		return new String(Character.toChars(cp)).toUpperCase(Locale.ROOT);
	}
}
