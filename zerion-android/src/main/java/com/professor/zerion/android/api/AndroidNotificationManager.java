package com.professor.zerion.android.api;

import android.app.Notification;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.sync.GroupId;

public interface AndroidNotificationManager {
    String ONGOING_CHANNEL_ID = "ONGOING_CHANNEL_ID";
    String ONGOING_CHANNEL_OLD_ID = "ONGOING_CHANNEL";
    int ONGOING_NOTIFICATION_ID = 1;
    String ACTION_DISMISS_REMINDER = "com.professor.zerion.android.DISMISS_REMINDER";
    String CONTACT_CHANNEL_ID = "CONTACT_CHANNEL_ID";
    String GROUP_CHANNEL_ID = "GROUP_CHANNEL_ID";
    String REMINDER_CHANNEL_ID = "REMINDER_CHANNEL_ID";
    String ROTATION_CHANNEL_ID = "ROTATION_CHANNEL_ID";

    String PREF_NOTIFY_GROUP = "pref_key_notify_group";
    String PREF_NOTIFY_PRIVATE = "pref_key_notify_private";
    String PREF_NOTIFY_CHANNEL = "pref_key_notify_channel";
    String PREF_NOTIFY_VOICE_CALLS = "pref_key_notify_voice_calls";
    String PREF_NOTIFY_RINGTONE_NAME = "pref_key_notify_ringtone_name";
    String PREF_NOTIFY_RINGTONE_URI = "pref_key_notify_ringtone_uri";
    String PREF_NOTIFY_SOUND = "pref_key_notify_sound";
    String PREF_NOTIFY_VIBRATION = "pref_key_notify_vibration";

    void showContactAddedNotification(ContactId c);
    void showPrivateMessageNotification(ContactId c);
    void showGroupMessageNotification(GroupId g);
    void showGroupTrPostNotification(byte[] groupTrId);
    void showChannelPostNotification(byte[] channelId);
    void showChannelCommentNotification(byte[] channelId);
    void clearContactNotification(ContactId c);
    void clearGroupMessageNotification(GroupId g);
    void clearGroupTrPostNotification(byte[] groupTrId);
    void clearChannelNotification(byte[] channelId);
    void clearAllContactNotifications();
    void clearAllGroupMessageNotifications();
    void clearAllContactAddedNotifications();
    void blockNotification(GroupId g);
    void blockGroupTrNotification(byte[] groupTrId);
    void blockChannelNotification(byte[] channelId);
    void blockContactNotification(ContactId c);
    void unblockNotification(GroupId g);
    void unblockGroupTrNotification(byte[] groupTrId);
    void unblockChannelNotification(byte[] channelId);
    void unblockContactNotification(ContactId c);
    void blockAllGroupMessageNotifications();
    void unblockAllGroupMessageNotifications();
    void clearContactAddedNotification();
    void showSignInNotification();
    void clearSignInNotification();
    void blockSignInNotification();
    void unblockSignInNotification();
    Notification getForegroundNotification();
    void updateForegroundNotification(boolean locked);

}