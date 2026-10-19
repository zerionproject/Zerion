package com.professor.zerion.android.selection;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.EnumSet;

import androidx.annotation.IdRes;
import androidx.annotation.Nullable;

@NotNullByDefault
public enum SelectionAction {

	REPLY(R.id.action_reply, true, false),
	REACT(R.id.action_react, true, false),
	COPY(R.id.action_copy, true, true),
	DELETE(R.id.action_delete, true, true),
	EDIT(R.id.action_edit, false, false),
	SAVE(R.id.action_save, false, false),
	FORWARD(R.id.action_forward, false, true),
	PIN(R.id.action_pin, false, false),
	UNPIN(R.id.action_unpin, false, false),
	COMMENTS(R.id.action_comments, false, false),
	COMMENTS_OFF(R.id.action_comments_off, false, false),
	COMMENTS_ON(R.id.action_comments_on, false, false),
	MEMBER(R.id.action_member, false, false),
	INFO(R.id.action_info, false, false);

	@IdRes
	private final int id;
	private final boolean primary;
	private final boolean forSeveral;

	SelectionAction(@IdRes int id, boolean primary, boolean forSeveral) {
		this.id = id;
		this.primary = primary;
		this.forSeveral = forSeveral;
	}

	@IdRes
	public int getId() {
		return id;
	}

	public boolean isPrimary() {
		return primary;
	}

	public boolean worksForSeveral() {
		return forSeveral;
	}

	public static EnumSet<SelectionAction> shown(
			Collection<SelectionAction> offered, int count) {
		EnumSet<SelectionAction> out = EnumSet.noneOf(SelectionAction.class);
		if (count <= 0) return out;
		for (SelectionAction a : offered) {
			if (count == 1 || a.forSeveral) out.add(a);
		}
		return out;
	}

	@Nullable
	public static SelectionAction byId(@IdRes int id) {
		for (SelectionAction a : values()) {
			if (a.id == id) return a;
		}
		return null;
	}
}
