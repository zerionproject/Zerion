package com.professor.zerion.android.account;

final class CharArraySequence implements CharSequence {

	private final char[] data;
	private final int start;
	private final int end;

	CharArraySequence(char[] data) {
		this(data, 0, data.length);
	}

	private CharArraySequence(char[] data, int start, int end) {
		this.data = data;
		this.start = start;
		this.end = end;
	}

	@Override
	public int length() {
		return end - start;
	}

	@Override
	public char charAt(int index) {
		return data[start + index];
	}

	@Override
	public CharSequence subSequence(int s, int e) {
		return new CharArraySequence(data, start + s, start + e);
	}

	@Override
	public String toString() {
		return new String(data, start, end - start);
	}
}
