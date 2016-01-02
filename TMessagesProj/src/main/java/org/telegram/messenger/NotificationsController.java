/*
 * This is the source code of Telegram for Android v. 3.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2015.
 */

package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.media.AudioManager;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.app.RemoteInput;
import android.util.SparseArray;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PopupNotificationActivity;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;

public class NotificationsController {

    public static final String EXTRA_VOICE_REPLY = "extra_voice_reply";

    private DispatchQueue notificationsQueue = new DispatchQueue("notificationsQueue");
    private ArrayList<MessageObject> pushMessages = new ArrayList<>();
    private ArrayList<MessageObject> delayedPushMessages = new ArrayList<>();
    private HashMap<Long, MessageObject> pushMessagesDict = new HashMap<>();
    private HashMap<Long, Point> smartNotificationsDialogs = new HashMap<>();
    private NotificationManagerCompat notificationManager = null;
    private HashMap<Long, Integer> pushDialogs = new HashMap<>();
    private HashMap<Long, Integer> wearNotificationsIds = new HashMap<>();
    private HashMap<Long, Integer> autoNotificationsIds = new HashMap<>();
    private HashMap<Long, Integer> pushDialogsOverrideMention = new HashMap<>();
    private int wearNotificationId = 10000;
    private int autoNotificationId = 20000;
    public ArrayList<MessageObject> popupMessages = new ArrayList<>();
    private long openned_dialog_id = 0;
    private int total_unread_count = 0;
    private int personal_count = 0;
    private boolean notifyCheck = false;
    private int lastOnlineFromOtherDevice = 0;
    private boolean inChatSoundEnabled = true;
    private int lastBadgeCount;
    private String launcherClassName;

    private Runnable notificationDelayRunnable;
    private PowerManager.WakeLock notificationDelayWakelock;

    private long lastSoundPlay;
    private long lastSoundOutPlay;
    private SoundPool soundPool;
    private int soundIn;
    private int soundOut;
    private boolean soundInLoaded;
    private boolean soundOutLoaded;
    protected AudioManager audioManager;
    private AlarmManager alarmManager;

    private static volatile NotificationsController Instance = null;
    public static NotificationsController getInstance() {
        NotificationsController localInstance = Instance;
        if (localInstance == null) {
            synchronized (MessagesController.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new NotificationsController();
                }
            }
        }
        return localInstance;
    }

    public NotificationsController() {
        notificationManager = NotificationManagerCompat.from(ApplicationLoader.applicationContext);
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("Notifications", Context.MODE_PRIVATE);
        inChatSoundEnabled = preferences.getBoolean("EnableInChatSound", true);

        try {
            audioManager = (AudioManager) ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE);
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        try {
            alarmManager = (AlarmManager) ApplicationLoader.applicationContext.getSystemService(Context.ALARM_SERVICE);
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }

        try {
            PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            notificationDelayWakelock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lock");
            notificationDelayWakelock.setReferenceCounted(false);
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }

        notificationDelayRunnable = new Runnable() {
            @Override
            public void run() {
                FileLog.e("tmessages", "delay reached");
                if (!delayedPushMessages.isEmpty()) {
                    showOrUpdateNotification(true);
                    delayedPushMessages.clear();
                }
                try {
                    if (notificationDelayWakelock.isHeld()) {
                        notificationDelayWakelock.release();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        };
    }

    public void cleanup() {
        popupMessages.clear();
        notificationsQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                openned_dialog_id = 0;
                total_unread_count = 0;
                personal_count = 0;
                pushMessages.clear();
                pushMessagesDict.clear();
                pushDialogs.clear();
                wearNotificationsIds.clear();
                autoNotificationsIds.clear();
                delayedPushMessages.clear();
                notifyCheck = false;
                lastBadgeCount = 0;
                try {
                    if (notificationDelayWakelock.isHeld()) {
                        notificationDelayWakelock.release();
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
                setBadge(0);
                SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("Notifications", Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = preferences.edit();
                editor.clear();
                editor.commit();
            }
        });
    }

    public void setInChatSoundEnabled(boolean value) {
        inChatSoundEnabled = value;
    }

    public void setOpennedDialogId(final long dialog_id) {
        notificationsQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                openned_dialog_id = dialog_id;
            }
        });
    }

    public void setLastOnlineFromOtherDevice(final int time) {
        notificationsQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                FileLog.e("tmessages", "set last online from other device = " + time);
                lastOnlineFromOtherDevice = time;
            }
        });
    }

    public void removeNotificationsForDialog(long did) {
        NotificationsController.getInstance().processReadMessages(null, did, 0, Integer.MAX_VALUE, false);
        HashMap<Long, Integer> dialogsToUpdate = new HashMap<>();
        dialogsToUpdate.put(did, 0);
        NotificationsController.getInstance().processDialogsUpdateRead(dialogsToUpdate);
    }

    public void removeDeletedMessagesFromNotifications(final SparseArray<ArrayList<Integer>> deletedMessages) {
        final ArrayList<MessageObject> popupArray = popupMessages.isEmpty() ? null : new ArrayList<>(popupMessages);
        notificationsQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                int old_unread_count = total_unread_count;
                SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("Notifications", Context.MODE_PRIVATE);
                for (int a = 0; a < deletedMessages.size(); a++) {
                    int key = deletedMessages.keyAt(a);
                    long dialog_id = -key;
                    ArrayList<Integer> mids = deletedMessages.get(key);
                    Integer currentCount = pushDialogs.get(dialog_id);
                    if (currentCount == null) {
                        currentCount = 0;
                    }
                    Integer newCount = currentCount;
                    for (int b = 0; b < mids.size(); b++) {
                        long mid = mids.get(b);
                        mid |= ((long) key) << 32;
                        MessageObject messageObject = pushMessagesDict.get(mid);
                        if (messageObject != null) {
                            pushMessagesDict.remove(mid);
                            delayedPushMessages.remove(messageObject);
                            pushMessages.remove(messageObject);
                            if (isPersonalMessage(messageObject)) {
                                personal_count--;
                            }
                            if (popupArray != null) {
                                popupArray.remove(messageObject);
                            }
                            newCount--;
                        }
                    }
                    if (newCount <= 0) {
                        newCount = 0;
                        smartNotificationsDialogs.remove(dialog_id);
                    }
                    if (!newCount.equals(currentCount)) {
                        total_unread_count -= currentCount;
                        total_unread_count += newCount;
                        pushDialogs.put(dialog_id, newCount);
                    }
                    if (newCount == 0) {
                        pushDialogs.remove(dialog_id);
                        pushDialogsOverrideMention.remove(dialog_id);
                        if (popupArray != null && pushMessages.isEmpty() && !popupArray.isEmpty()) {
                            popupArray.clear();
                        }
                    }
                }
                if (popupArray != null) {
                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            popupMessages = popupArray;
                        }
                    });
                }
                if (old_unread_count != total_unread_count) {
                    if (!notifyCheck) {
                        delayedPushMessages.clear();
                        showOrUpdateNotification(notifyCheck);
                    } else {
                        scheduleNotificationDelay(lastOnlineFromOtherDevice > ConnectionsManager.getInstance().getCurrentTime());
                    }
                }
                notifyCheck = false;
                if (preferences.getBoolean("badgeNumber", true)) {
                    setBadge(total_unread_count);
                }
            }
        });
    }

    public void processReadMessages(final SparseArray<Long> inbox, final long dialog_id, final int max_date, final int max_id, final boolean isPopup) {
        final ArrayList<MessageObject> popupArray = popupMessages.isEmpty() ? null : new ArrayList<>(popupMessages);
        notificationsQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                int oldCount = popupArray != null ? popupArray.size() : 0;
                if (inbox != null) {
                    for (int b = 0; b < inbox.size(); b++) {
                        int key = inbox.keyAt(b);
                        long messageId = inbox.get(key);
                        for (int a = 0; a < pushMessages.size(); a++) {
                            MessageObject messageObject = pushMessages.get(a);
                            if (messageObject.getDialogId() == key && messageObject.getId() <= (int) messageId) {
                                if (isPersonalMessage(messageObject)) {
                                    personal_count--;
                                }
                                if (popupArray != null) {
                                    popupArray.remove(messageObject);
                                }
                                long mid = messageObject.messageOwner.id;
                                if (messageObject.messageOwner.to_id.channel_id != 0) {
                                    mid |= ((long) messageObject.messageOwner.to_id.channel_id) << 32;
                                }
                                pushMessagesDict.remove(mid);
                                delayedPushMessages.remove(messageObject);
                                pushMessages.remove(a);
                                a--;
                            }
                        }
                    }
                    if (popupArray != null && pushMessages.isEmpty() && !popupArray.isEmpty()) {
                        popupArray.clear();
                    }
                }
                if (dialog_id != 0 && (max_id != 0 || max_date != 0)) {
                    for (int a = 0; a < pushMessages.size(); a++) {
                        MessageObject messageObject = pushMessages.get(a);
                        if (messageObject.getDialogId() == dialog_id) {
                            boolean remove = false;
                            if (max_date != 0) {
                                if (messageObject.messageOwner.date <= max_date) {
                                    remove = true;
                                }
                            } else {
                                if (!isPopup) {
                                    if (messageObject.getId() <= max_id || max_id < 0) {
                                        remove = true;
                                    }
                                } else {
                                    if (messageObject.getId() == max_id || max_id < 0) {
                                        remove = true;
                                    }
                                }
                            }
                            if (remove) {
                                if (isPersonalMessage(messageObject)) {
                                    personal_count--;
                                }
                                pushMessages.remove(a);
                                delayedPushMessages.remove(messageObject);
                                if (popupArray != null) {
                                    popupArray.remove(messageObject);
                                }
                                long mid = messageObject.messageOwner.id;
                                if (messageObject.messageOwner.to_id.channel_id != 0) {
                                    mid |= ((long) messageObject.messageOwner.to_id.channel_id) << 32;
                                }
                                pushMessagesDict.remove(mid);
                                a--;
                            }
                        }
                    }
                    if (popupArray != null && pushMessages.isEmpty() && !popupArray.isEmpty()) {
                        popupArray.clear();
                    }
                }
                if (popupArray != null && oldCount != popupArray.size()) {
                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            popupMessages = popupArray;
                            NotificationCenter.getInstance().postNotificationName(NotificationCenter.pushMessagesUpdated);
                        }
                    });
                }
            }
        });
    }

    public void processNewMessages(final ArrayList<MessageObject> messageObjects, final boolean isLast) {
        if (messageObjects.isEmpty()) {
            return;
        }
        final ArrayList<MessageObject> popupArray = new ArrayList<>(popupMessages);
        notificationsQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                boolean added = false;

                int oldCount = popupArray.size();
                HashMap<Long, Boolean> settingsCache = new HashMap<>();
                SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("Notifications", Context.MODE_PRIVATE);
                int popup = 0;

                for (int a = 0; a < messageObjects.size(); a++) {
                    MessageObject messageObject = messageObjects.get(a);
                    long mid = messageObject.messageOwner.id;
                    if (messageObject.messageOwner.to_id.channel_id != 0) {
                        mid |= ((long) messageObject.messageOwner.to_id.channel_id) << 32;
                    }
                    if (pushMessagesDict.containsKey(mid)) {
                        continue;
                    }
                    long dialog_id = messageObject.getDialogId();
                    long original_dialog_id = dialog_id;
                    if (dialog_id == openned_dialog_id && ApplicationLoader.isScreenOn) {
                        playInChatSound();
                        continue;
                    }
                    if (messageObject.messageOwner.mentioned) {
                        dialog_id = messageObject.messageOwner.from_id;
                    }
                    if (isPersonalMessage(messageObject)) {
                        personal_count++;
                    }
                    added = true;

                    Boolean value = settingsCache.get(dialog_id);
                    boolean isChat = (int)dialog_id < 0;
                    popup = (int)dialog_id == 0 ? 0 : preferences.getInt(isChat ? "popupGroup" : "popupAll", 0);
                    if (value == null) {
                        int notifyOverride = getNotifyOverride(preferences, dialog_id);
                        value = !(notifyOverride == 2 || (!preferences.getBoolean("EnableAll", true) || isChat && !preferences.getBoolean("EnableGroup", true)) && notifyOverride == 0);
                        settingsCache.put(dialog_id, value);
                    }
                    if (value) {
                        if (popup != 0) {
                            popupArray.add(0, messageObject);
                        }
                        delayedPushMessages.add(messageObject);
                        pushMessages.add(0, messageObject);
                        pushMessagesDict.put(mid, messageObject);
                        if (original_dialog_id != dialog_id) {
                            pushDialogsOverrideMention.put(original_dialog_id, 1);
                        }
                    }
                }

                if (added) {
                    notifyCheck = isLast;
                }

                if (!popupArray.isEmpty() && oldCount != popupArray.size() && !AndroidUtilities.needShowPasscode(false)) {
                    final int popupFinal = popup;
                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            popupMessages = popupArray;
                            if (ApplicationLoader.mainInterfacePaused || !ApplicationLoader.isScreenOn && !UserConfig.isWaitingForPasscodeEnter) {
                                MessageObject messageObject = messageObjects.get(0);
                                if (popupFinal == 3 || popupFinal == 1 && ApplicationLoader.isScreenOn || popupFinal == 2 && !ApplicationLoader.isScreenOn) {
                                    Intent popupIntent = new Intent(ApplicationLoader.applicationContext, PopupNotificationActivity.class);
                                    popupIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_NO_USER_ACTION | Intent.FLAG_FROM_BACKGROUND);
                                    ApplicationLoader.applicationContext.startActivity(popupIntent);
                                }
                            }
                        }
                    });
                }
            }
        });
    }

    public void processDialogsUpdateRead(final HashMap<Long, Integer> dialogsToUpdate) {
        final ArrayList<MessageObject> popupArray = popupMessages.isEmpty() ? null : new ArrayList<>(popupMessages);
        notificationsQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                int old_unread_count = total_unread_count;
                SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("Notifications", Context.MODE_PRIVATE);
                for (HashMap.Entry<Long, Integer> entry : dialogsToUpdate.entrySet()) {
                    long dialog_id = entry.getKey();

                    int notifyOverride = getNotifyOverride(preferences, dialog_id);
                    if (notifyCheck) {
                        Integer override = pushDialogsOverrideMention.get(dialog_id);
                        if (override != null && override == 1) {
                            pushDialogsOverrideMention.put(dialog_id, 0);
                            notifyOverride = 1;
                        }
                    }
                    boolean canAddValue = !(notifyOverride == 2 || (!preferences.getBoolean("EnableAll", true) || ((int) dialog_id < 0) && !preferences.getBoolean("EnableGroup", true)) && notifyOverride == 0);

                    Integer currentCount = pushDialogs.get(dialog_id);
                    Integer newCount = entry.getValue();
                    if (newCount == 0) {
                        smartNotificationsDialogs.remove(dialog_id);
                    }

                    if (newCount < 0) {
                        if (currentCount == null) {
                            continue;
                        }
                        newCount = currentCount + newCount;
                    }
                    if (canAddValue || newCount == 0) {
                        if (currentCount != null) {
                            total_unread_count -= currentCount;
                        }
                    }
                    if (newCount == 0) {
                        pushDialogs.remove(dialog_id);
                        pushDialogsOverrideMention.remove(dialog_id);
                        for (int a = 0; a < pushMessages.size(); a++) {
                            MessageObject messageObject = pushMessages.get(a);
                            if (messageObject.getDialogId() == dialog_id) {
                                if (isPersonalMessage(messageObject)) {
                                    personal_count--;
                                }
                                pushMessages.remove(a);
                                a--;
                                delayedPushMessages.remove(messageObject);
                                long mid = messageObject.messageOwner.id;
                                if (messageObject.messageOwner.to_id.channel_id != 0) {
                                    mid |= ((long) messageObject.messageOwner.to_id.channel_id) << 32;
                                }
                                pushMessagesDict.remove(mid);
                                if (popupArray != null) {
                                    popupArray.remove(messageObject);
                                }
                            }
                        }
                        if (popupArray != null && pushMessages.isEmpty() && !popupArray.isEmpty()) {
                            popupArray.clear();
                        }
                    } else if (canAddValue) {
                        total_unread_count += newCount;
                        pushDialogs.put(dialog_id, newCount);
                    }
                }
                if (popupArray != null) {
                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            popupMessages = popupArray;
                        }
                    });
                }
                if (old_unread_count != total_unread_count) {
                    if (!notifyCheck) {
                        delayedPushMessages.clear();
                        showOrUpdateNotification(notifyCheck);
                    } else {
                        scheduleNotificationDelay(lastOnlineFromOtherDevice > ConnectionsManager.getInstance().getCurrentTime());
                    }
                }
                notifyCheck = false;
                if (preferences.getBoolean("badgeNumber", true)) {
                    setBadge(total_unread_count);
                }
            }
        });
    }

    public void processLoadedUnreadMessages(final HashMap<Long, Integer> dialogs, final ArrayList<TLRPC.Message> messages, final ArrayList<TLRPC.User> users, final ArrayList<TLRPC.Chat> chats, final ArrayList<TLRPC.EncryptedChat> encryptedChats) {
        MessagesController.getInstance().putUsers(users, true);
        MessagesController.getInstance().putChats(chats, true);
        MessagesController.getInstance().putEncryptedChats(encryptedChats, true);

        notificationsQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                pushDialogs.clear();
                pushMessages.clear();
                pushMessagesDict.clear();
                total_unread_count = 0;
                personal_count = 0;
                SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("Notifications", Context.MODE_PRIVATE);
                HashMap<Long, Boolean> settingsCache = new HashMap<>();

                if (messages != null) {
                    for (TLRPC.Message message : messages) {
                        long mid = message.id;
                        if (message.to_id.channel_id != 0) {
                            mid |= ((long) message.to_id.channel_id) << 32;
                        }
                        if (pushMessagesDict.containsKey(mid)) {
                            continue;
                        }
                        MessageObject messageObject = new MessageObject(message, null, false);
                        if (isPersonalMessage(messageObject)) {
                            personal_count++;
                        }
                        long dialog_id = messageObject.getDialogId();
                        long original_dialog_id = dialog_id;
                        if (messageObject.messageOwner.mentioned) {
                            dialog_id = messageObject.messageOwner.from_id;
                        }
                        Boolean value = settingsCache.get(dialog_id);
                        if (value == null) {
                            int notifyOverride = getNotifyOverride(preferences, dialog_id);
                            value = !(notifyOverride == 2 || (!preferences.getBoolean("EnableAll", true) || ((int) dialog_id < 0) && !preferences.getBoolean("EnableGroup", true)) && notifyOverride == 0);
                            settingsCache.put(dialog_id, value);
                        }
                        if (!value || dialog_id == openned_dialog_id && ApplicationLoader.isScreenOn) {
                            continue;
                        }
                        pushMessagesDict.put(mid, messageObject);
                        pushMessages.add(0, messageObject);
                        if (original_dialog_id != dialog_id) {
                            pushDialogsOverrideMention.put(original_dialog_id, 1);
                        }
                    }
                }
                for (HashMap.Entry<Long, Integer> entry : dialogs.entrySet()) {
                    long dialog_id = entry.getKey();
                    Boolean value = settingsCache.get(dialog_id);
                    if (value == null) {
                        int notifyOverride = getNotifyOverride(preferences, dialog_id);
                        Integer override = pushDialogsOverrideMention.get(dialog_id);
                        if (override != null && override == 1) {
                            pushDialogsOverrideMention.put(dialog_id, 0);
                            notifyOverride = 1;
                        }
                        value = !(notifyOverride == 2 || (!preferences.getBoolean("EnableAll", true) || ((int) dialog_id < 0) && !preferences.getBoolean("EnableGroup", true)) && notifyOverride == 0);
                        settingsCache.put(dialog_id, value);
                    }
                    if (!value) {
                        continue;
                    }
                    int count = entry.getValue();
                    pushDialogs.put(dialog_id, count);
                    total_unread_count += count;
                }
                if (total_unread_count == 0) {
                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            popupMessages.clear();
                            NotificationCenter.getInstance().postNotificationName(NotificationCenter.pushMessagesUpdated);
                        }
                    });
                }
                showOrUpdateNotification(SystemClock.uptimeMillis() / 1000 < 60);

                if (preferences.getBoolean("badgeNumber", true)) {
                    setBadge(total_unread_count);
                }
            }
        });
    }

    public void setBadgeEnabled(boolean enabled) {
        setBadge(enabled ? total_unread_count : 0);
    }

    private void setBadge(final int count) {
        notificationsQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                if (lastBadgeCount == count) {
                    return;
                }
                lastBadgeCount = count;
                try {
                    ContentValues cv = new ContentValues();
                    cv.put("tag", "org.telegram.messenger/org.telegram.ui.LaunchActivity");
                    cv.put("count", count);
                    ApplicationLoader.applicationContext.getContentResolver().insert(Uri.parse("content://com.teslacoilsw.notifier/unread_count"), cv);
                } catch (Throwable e) {
                    //ignore
                }
                try {
                    if (launcherClassName == null) {
                        launcherClassName = getLauncherClassName(ApplicationLoader.applicationContext);
                    }
                    if (launcherClassName == null) {
                        return;
                    }
                    AndroidUtilities.runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Intent intent = new Intent("android.intent.action.BADGE_COUNT_UPDATE");
                                intent.putExtra("badge_count", count);
                                intent.putExtra("badge_count_package_name", ApplicationLoader.applicationContext.getPackageName());
                                intent.putExtra("badge_count_class_name", launcherClassName);
                                ApplicationLoader.applicationContext.sendBroadcast(intent);
                            } catch (Exception e) {
                                FileLog.e("tmessages", e);
                            }
                        }
                    });
                } catch (Throwable e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    private String getStringForMessage(MessageObject messageObject, boolean shortMessage) {
        long dialog_id = messageObject.messageOwner.dialog_id;
        int chat_id = messageObject.messageOwner.to_id.chat_id != 0 ? messageObject.messageOwner.to_id.chat_id : messageObject.messageOwner.to_id.channel_id;
        int from_id = messageObject.messageOwner.to_id.user_id;
        if (from_id == 0) {
            from_id = messageObject.messageOwner.from_id;
        } else if (from_id == UserConfig.getClientUserId()) {
            from_id = messageObject.messageOwner.from_id;
        }

        if (dialog_id == 0) {
            if (chat_id != 0) {
                dialog_id = -chat_id;
            } else if (from_id != 0) {
                dialog_id = from_id;
            }
        }

        String name = null;
        if (from_id > 0) {
            TLRPC.User user = MessagesController.getInstance().getUser(from_id);
            if (user != null) {
                name = UserObject.getUserName(user);
            }
        } else {
            TLRPC.Chat chat = MessagesController.getInstance().getChat(-from_id);
            if (chat != null) {
                name = chat.title;
            }
        }

        if (name == null) {
            return null;
        }
        TLRPC.Chat chat = null;
        if (chat_id != 0) {
            chat = MessagesController.getInstance().getChat(chat_id);
            if (chat == null) {
                return null;
            }
        }

        String msg = null;
        if ((int)dialog_id == 0 || AndroidUtilities.needShowPasscode(false) || UserConfig.isWaitingForPasscodeEnter) {
            msg = LocaleController.getString("YouHaveNewMessage", R.string.YouHaveNewMessage);
        } else {
            if (chat_id == 0 && from_id != 0) {
                SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("Notifications", Context.MODE_PRIVATE);
                if (preferences.getBoolean("EnablePreviewAll", true)) {
                    if (messageObject.messageOwner instanceof TLRPC.TL_messageService) {
                        if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionUserJoined) {
                            msg = LocaleController.formatString("NotificationContactJoined", R.string.NotificationContactJoined, name);
                        } else if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionUserUpdatedPhoto) {
                            msg = LocaleController.formatString("NotificationContactNewPhoto", R.string.NotificationContactNewPhoto, name);
                        } else if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionLoginUnknownLocation) {
                            String date = LocaleController.formatString("formatDateAtTime", R.string.formatDateAtTime, LocaleController.getInstance().formatterYear.format(((long) messageObject.messageOwner.date) * 1000), LocaleController.getInstance().formatterDay.format(((long) messageObject.messageOwner.date) * 1000));
                            msg = LocaleController.formatString("NotificationUnrecognizedDevice", R.string.NotificationUnrecognizedDevice, UserConfig.getCurrentUser().first_name, date, messageObject.messageOwner.action.title, messageObject.messageOwner.action.address);
                        }
                    } else {
                        if (messageObject.isMediaEmpty()) {
                            if (!shortMessage) {
                                if (messageObject.messageOwner.message != null && messageObject.messageOwner.message.length() != 0) {
                                    msg = LocaleController.formatString("NotificationMessageText", R.string.NotificationMessageText, name, messageObject.messageOwner.message);
                                } else {
                                    msg = LocaleController.formatString("NotificationMessageNoText", R.string.NotificationMessageNoText, name);
                                }
                            } else {
                                msg = LocaleController.formatString("NotificationMessageNoText", R.string.NotificationMessageNoText, name);
                            }
                        } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaPhoto) {
                            msg = LocaleController.formatString("NotificationMessagePhoto", R.string.NotificationMessagePhoto, name);
                        } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaVideo) {
                            msg = LocaleController.formatString("NotificationMessageVideo", R.string.NotificationMessageVideo, name);
                        } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaContact) {
                            msg = LocaleController.formatString("NotificationMessageContact", R.string.NotificationMessageContact, name);
                        } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaGeo || messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaVenue) {
                            msg = LocaleController.formatString("NotificationMessageMap", R.string.NotificationMessageMap, name);
                        } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaDocument) {
                            if (messageObject.isSticker()) {
                                msg = LocaleController.formatString("NotificationMessageSticker", R.string.NotificationMessageSticker, name);
                            } else {
                                msg = LocaleController.formatString("NotificationMessageDocument", R.string.NotificationMessageDocument, name);
                            }
                        } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaAudio) {
                            msg = LocaleController.formatString("NotificationMessageAudio", R.string.NotificationMessageAudio, name);
                        }
                    }
                } else {
                    msg = LocaleController.formatString("NotificationMessageNoText", R.string.NotificationMessageNoText, name);
                }
            } else if (chat_id != 0) {
                SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("Notifications", Context.MODE_PRIVATE);
                if (preferences.getBoolean("EnablePreviewGroup", true)) {
                    if (messageObject.messageOwner instanceof TLRPC.TL_messageService) {
                        if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionChatAddUser) {
                            int singleUserId = messageObject.messageOwner.action.user_id;
                            if (singleUserId == 0 && messageObject.messageOwner.action.users.size() == 1) {
                                singleUserId = messageObject.messageOwner.action.users.get(0);
                            }
                            if (singleUserId != 0) {
                                if (messageObject.messageOwner.to_id.channel_id != 0 && !messageObject.isMegagroup()) {
                                    TLRPC.User user = MessagesController.getInstance().getUser(singleUserId);
                                    if (user != null) {
                                        name = UserObject.getUserName(user);
                                    } else {
                                        name = "";
                                    }
                                    msg = LocaleController.formatString("ChannelAddedByNotification", R.string.ChannelAddedByNotification, name, chat.title);
                                } else {
                                    if (singleUserId == UserConfig.getClientUserId()) {
                                        msg = LocaleController.formatString("NotificationInvitedToGroup", R.string.NotificationInvitedToGroup, name, chat.title);
                                    } else {
                                        TLRPC.User u2 = MessagesController.getInstance().getUser(singleUserId);
                                        if (u2 == null) {
                                            return null;
                                        }
                                        if (from_id == u2.id) {
                                            msg = LocaleController.formatString("NotificationGroupAddSelf", R.string.NotificationGroupAddSelf, name, chat.title);
                                        } else {
                                            msg = LocaleController.formatString("NotificationGroupAddMember", R.string.NotificationGroupAddMember, name, chat.title, UserObject.getUserName(u2));
                                        }
                                    }
                                }
                            } else {
                                StringBuilder names = new StringBuilder("");
                                for (int a = 0; a < messageObject.messageOwner.action.users.size(); a++) {
                                    TLRPC.User user = MessagesController.getInstance().getUser(messageObject.messageOwner.action.users.get(a));
                                    if (user != null) {
                                        String name2 = UserObject.getUserName(user);
                                        if (names.length() != 0) {
                                            names.append(", ");
                                        }
                                        names.append(name2);
                                    }
                                }
                                msg = LocaleController.formatString("NotificationGroupAddMember", R.string.NotificationGroupAddMember, name, chat.title, names.toString());
                            }
                        } else if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionChatJoinedByLink) {
                            msg = LocaleController.formatString("NotificationInvitedToGroupByLink", R.string.NotificationInvitedToGroupByLink, name, chat.title);
                        } else if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionChatEditTitle) {
                            msg = LocaleController.formatString("NotificationEditedGroupName", R.string.NotificationEditedGroupName, name, messageObject.messageOwner.action.title);
                        } else if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionChatEditPhoto || messageObject.messageOwner.action instanceof TLRPC.TL_messageActionChatDeletePhoto) {
                            if (messageObject.messageOwner.to_id.channel_id != 0 && !messageObject.isMegagroup()) {
                                msg = LocaleController.formatString("ChannelPhotoEditNotification", R.string.ChannelPhotoEditNotification, chat.title);
                            } else {
                                msg = LocaleController.formatString("NotificationEditedGroupPhoto", R.string.NotificationEditedGroupPhoto, name, chat.title);
                            }
                        } else if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionChatDeleteUser) {
                            if (messageObject.messageOwner.action.user_id == UserConfig.getClientUserId()) {
                                msg = LocaleController.formatString("NotificationGroupKickYou", R.string.NotificationGroupKickYou, name, chat.title);
                            } else if (messageObject.messageOwner.action.user_id == from_id) {
                                msg = LocaleController.formatString("NotificationGroupLeftMember", R.string.NotificationGroupLeftMember, name, chat.title);
                            } else {
                                TLRPC.User u2 = MessagesController.getInstance().getUser(messageObject.messageOwner.action.user_id);
                                if (u2 == null) {
                                    return null;
                                }
                                msg = LocaleController.formatString("NotificationGroupKickMember", R.string.NotificationGroupKickMember, name, chat.title, UserObject.getUserName(u2));
                            }
                        } else if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionChatCreate) {
                            msg = messageObject.messageText.toString();
                        } else if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionChannelCreate) {
                            msg = messageObject.messageText.toString();
                        } else if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionChatMigrateTo) {
                            msg = LocaleController.formatString("ActionMigrateFromGroupNotify", R.string.ActionMigrateFromGroupNotify, chat.title);
                        } else if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionChannelMigrateFrom) {
                            msg = LocaleController.formatString("ActionMigrateFromGroupNotify", R.string.ActionMigrateFromGroupNotify, messageObject.messageOwner.action.title);
                        }
                    } else {
                        if (ChatObject.isChannel(chat) && !chat.megagroup) {
                            if (from_id < 0) {
                                if (messageObject.isMediaEmpty()) {
                                    if (!shortMessage && messageObject.messageOwner.message != null && messageObject.messageOwner.message.length() != 0) {
                                        msg = LocaleController.formatString("NotificationMessageGroupText", R.string.NotificationMessageGroupText, name, chat.title, messageObject.messageOwner.message);
                                    } else {
                                        msg = LocaleController.formatString("ChannelMessageNoText", R.string.ChannelMessageNoText, name, chat.title);
                                    }
                                } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaPhoto) {
                                    msg = LocaleController.formatString("ChannelMessagePhoto", R.string.ChannelMessagePhoto, name, chat.title);
                                } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaVideo) {
                                    msg = LocaleController.formatString("ChannelMessageVideo", R.string.ChannelMessageVideo, name, chat.title);
                                } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaContact) {
                                    msg = LocaleController.formatString("ChannelMessageContact", R.string.ChannelMessageContact, name, chat.title);
                                } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaGeo || messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaVenue) {
                                    msg = LocaleController.formatString("ChannelMessageMap", R.string.ChannelMessageMap, name, chat.title);
                                } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaDocument) {
                                    if (messageObject.isSticker()) {
                                        msg = LocaleController.formatString("ChannelMessageSticker", R.string.ChannelMessageSticker, name, chat.title);
                                    } else {
                                        msg = LocaleController.formatString("ChannelMessageDocument", R.string.ChannelMessageDocument, name, chat.title);
                                    }
                                } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaAudio) {
                                    msg = LocaleController.formatString("ChannelMessageAudio", R.string.ChannelMessageAudio, name, chat.title);
                                }
                            } else {
                                if (messageObject.isMediaEmpty()) {
                                    if (!shortMessage && messageObject.messageOwner.message != null && messageObject.messageOwner.message.length() != 0) {
                                        msg = LocaleController.formatString("NotificationMessageGroupText", R.string.NotificationMessageGroupText, name, chat.title, messageObject.messageOwner.message);
                                    } else {
                                        msg = LocaleController.formatString("ChannelMessageGroupNoText", R.string.ChannelMessageGroupNoText, name, chat.title);
                                    }
                                } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaPhoto) {
                                    msg = LocaleController.formatString("ChannelMessageGroupPhoto", R.string.ChannelMessageGroupPhoto, name, chat.title);
                                } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaVideo) {
                                    msg = LocaleController.formatString("ChannelMessageGroupVideo", R.string.ChannelMessageGroupVideo, name, chat.title);
                                } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaContact) {
                                    msg = LocaleController.formatString("ChannelMessageGroupContact", R.string.ChannelMessageGroupContact, name, chat.title);
                                } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaGeo || messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaVenue) {
                                    msg = LocaleController.formatString("ChannelMessageGroupMap", R.string.ChannelMessageGroupMap, name, chat.title);
                                } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaDocument) {
                                    if (messageObject.isSticker()) {
                                        msg = LocaleController.formatString("ChannelMessageGroupSticker", R.string.ChannelMessageGroupSticker, name, chat.title);
                                    } else {
                                        msg = LocaleController.formatString("ChannelMessageGroupDocument", R.string.ChannelMessageGroupDocument, name, chat.title);
                                    }
                                } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaAudio) {
                                    msg = LocaleController.formatString("ChannelMessageGroupAudio", R.string.ChannelMessageGroupAudio, name, chat.title);
                                }
                            }
                        } else {
                            if (messageObject.isMediaEmpty()) {
                                if (!shortMessage && messageObject.messageOwner.message != null && messageObject.messageOwner.message.length() != 0) {
                                    msg = LocaleController.formatString("NotificationMessageGroupText", R.string.NotificationMessageGroupText, name, chat.title, messageObject.messageOwner.message);
                                } else {
                                    msg = LocaleController.formatString("NotificationMessageGroupNoText", R.string.NotificationMessageGroupNoText, name, chat.title);
                                }
                            } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaPhoto) {
                                msg = LocaleController.formatString("NotificationMessageGroupPhoto", R.string.NotificationMessageGroupPhoto, name, chat.title);
                            } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaVideo) {
                                msg = LocaleController.formatString("NotificationMessageGroupVideo", R.string.NotificationMessageGroupVideo, name, chat.title);
                            } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaContact) {
                                msg = LocaleController.formatString("NotificationMessageGroupContact", R.string.NotificationMessageGroupContact, name, chat.title);
                            } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaGeo || messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaVenue) {
                                msg = LocaleController.formatString("NotificationMessageGroupMap", R.string.NotificationMessageGroupMap, name, chat.title);
                            } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaDocument) {
                                if (messageObject.isSticker()) {
                                    msg = LocaleController.formatString("NotificationMessageGroupSticker", R.string.NotificationMessageGroupSticker, name, chat.title);
                                } else {
                                    msg = LocaleController.formatString("NotificationMessageGroupDocument", R.string.NotificationMessageGroupDocument, name, chat.title);
                                }
                            } else if (messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaAudio) {
                                msg = LocaleController.formatString("NotificationMessageGroupAudio", R.string.NotificationMessageGroupAudio, name, chat.title);
                            }
                        }
                    }
                } else {
                    msg = LocaleController.formatString("NotificationMessageGroupNoText", R.string.NotificationMessageGroupNoText, name, chat.title);
                }
            }
        }
        return msg;
    }

    private void scheduleNotificationRepeat() {
        try {
            PendingIntent pintent = PendingIntent.getService(ApplicationLoader.applicationContext, 0, new Intent(ApplicationLoader.applicationContext, NotificationRepeat.class), 0);
            SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("Notifications", Activity.MODE_PRIVATE);
            int minutes = preferences.getInt("repeat_messages", 60);
            if (minutes > 0 && personal_count > 0) {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + minutes * 60 * 1000, pintent);
            } else {
                alarmManager.cancel(pintent);
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    private static String getLauncherClassName(Context context) {
        try {
            PackageManager pm = context.getPackageManager();

            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);

            List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);
            for (ResolveInfo resolveInfo : resolveInfos) {
                String pkgName = resolveInfo.activityInfo.applicationInfo.packageName;
                if (pkgName.equalsIgnoreCase(context.getPackageName())) {
                    return resolveInfo.activityInfo.name;
                }
            }
        } catch (Throwable e) {
            FileLog.e("tmessages", e);
        }
        return null;
    }

    private boolean isPersonalMessage(MessageObject messageObject) {
        return messageObject.messageOwner.to_id != null && messageObject.messageOwner.to_id.chat_id == 0 && messageObject.messageOwner.to_id.channel_id == 0
                && (messageObject.messageOwner.action == null || messageObject.messageOwner.action instanceof TLRPC.TL_messageActionEmpty);
    }

    private int getNotifyOverride(SharedPreferences preferences, long dialog_id) {
        int notifyOverride = preferences.getInt("notify2_" + dialog_id, 0);
        if (notifyOverride == 3) {
            int muteUntil = preferences.getInt("notifyuntil_" + dialog_id, 0);
            if (muteUntil >= ConnectionsManager.getInstance().getCurrentTime()) {
                notifyOverride = 2;
            }
        }
        return notifyOverride;
    }

    private void dismissNotification() {
        try {
            notificationManager.cancel(1);
            pushMessages.clear();
            pushMessagesDict.clear();
            for (HashMap.Entry<Long, Integer> entry : autoNotificationsIds.entrySet()) {
                notificationManager.cancel(entry.getValue());
            }
            autoNotificationsIds.clear();
            for (HashMap.Entry<Long, Integer> entry : wearNotificationsIds.entrySet()) {
                notificationManager.cancel(entry.getValue());
            }
            wearNotificationsIds.clear();
            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override
                public void run() {
                    NotificationCenter.getInstance().postNotificationName(NotificationCenter.pushMessagesUpdated);
                }
            });
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    private void playInChatSound() {
        if (!inChatSoundEnabled) {
            return;
        }
        try {
            if (audioManager.getRingerMode() == AudioManager.RINGER_MODE_SILENT) {
                return;
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }

        try {
            SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("Notifications", Context.MODE_PRIVATE);
            int notifyOverride = getNotifyOverride(preferences, openned_dialog_id);
            if (notifyOverride == 2) {
                return;
            }
            notificationsQueue.postRunnable(new Runnable() {
                @Override
                public void run() {
                    if (Math.abs(System.currentTimeMillis() - lastSoundPlay) <= 500) {
                        return;
                    }
                    try {
                        if (soundPool == null) {
                            soundPool = new SoundPool(2, AudioManager.STREAM_SYSTEM, 0);
                            soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                                @Override
                                public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                                    if (status == 0) {
                                        soundPool.play(sampleId, 1.0f, 1.0f, 1, 0, 1.0f);
                                    }
                                }
                            });
                        }
                        if (soundIn == 0 && !soundInLoaded) {
                            soundInLoaded = true;
                            soundIn = soundPool.load(ApplicationLoader.applicationContext, R.raw.sound_in, 1);
                        }
                        if (soundIn != 0) {
                            soundPool.play(soundIn, 1.0f, 1.0f, 1, 0, 1.0f);
                        }
                    } catch (Exception e) {
                        FileLog.e("tmessages", e);
                    }
                }
            });
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    private void scheduleNotificationDelay(boolean onlineReason) {
        try {
            FileLog.e("tmessages", "delay notification start, onlineReason = " + onlineReason);
            notificationDelayWakelock.acquire(10000);
            AndroidUtilities.cancelRunOnUIThread(notificationDelayRunnable);
            AndroidUtilities.runOnUIThread(notificationDelayRunnable, (onlineReason ? 3 * 1000 : 1000));
        } catch (Exception e) {
            FileLog.e("tmessages", e);
            showOrUpdateNotification(notifyCheck);
        }
    }

    protected void repeatNotificationMaybe() {
        notificationsQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                if (hour >= 11 && hour <= 22) {
                    notificationManager.cancel(1);
                    showOrUpdateNotification(true);
                } else {
                    scheduleNotificationRepeat();
                }
            }
        });
    }

    private void showOrUpdateNotification(boolean notifyAboutLast) {
        if (!UserConfig.isClientActivated() || pushMessages.isEmpty()) {
            dismissNotification();
            return;
        }
        try {
            ConnectionsManager.getInstance().resumeNetworkMaybe();

            MessageObject lastMessageObject = pushMessages.get(0);

            long dialog_id = lastMessageObject.getDialogId();
            long override_dialog_id = dialog_id;
            if (lastMessageObject.messageOwner.mentioned) {
                override_dialog_id = lastMessageObject.messageOwner.from_id;
            }
            int mid = lastMessageObject.getId();
            int chat_id = lastMessageObject.messageOwner.to_id.chat_id != 0 ? lastMessageObject.messageOwner.to_id.chat_id : lastMessageObject.messageOwner.to_id.channel_id;
            int user_id = lastMessageObject.messageOwner.to_id.user_id;
            if (user_id == 0) {
                user_id = lastMessageObject.messageOwner.from_id;
            } else if (user_id == UserConfig.getClientUserId()) {
                user_id = lastMessageObject.messageOwner.from_id;
            }

            TLRPC.User user = MessagesController.getInstance().getUser(user_id);
            TLRPC.Chat chat = null;
            if (chat_id != 0) {
                chat = MessagesController.getInstance().getChat(chat_id);
            }
            TLRPC.FileLocation photoPath = null;

            boolean notifyDisabled = false;
            int needVibrate = 0;
            String choosenSoundPath = null;
            int ledColor = 0xff00ff00;
            boolean inAppSounds;
            boolean inAppVibrate;
            boolean inAppPreview = false;
            boolean inAppPriority;
            int priority = 0;
            int priorityOverride;
            int vibrateOverride;

            SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("Notifications", Context.MODE_PRIVATE);
            int notifyOverride = getNotifyOverride(preferences, override_dialog_id);
            if (!notifyAboutLast || notifyOverride == 2 || (!preferences.getBoolean("EnableAll", true) || chat_id != 0 && !preferences.getBoolean("EnableGroup", true)) && notifyOverride == 0) {
                notifyDisabled = true;
            }

            if (!notifyDisabled && dialog_id == override_dialog_id && chat != null) {
                int notifyMaxCount = preferences.getInt("smart_max_count_" + dialog_id, 2);
                int notifyDelay = preferences.getInt("smart_delay_" + dialog_id, 3 * 60);
                if (notifyMaxCount != 0) {
                    Point dialogInfo = smartNotificationsDialogs.get(dialog_id);
                    if (dialogInfo == null) {
                        dialogInfo = new Point(1, (int) (System.currentTimeMillis() / 1000));
                        smartNotificationsDialogs.put(dialog_id, dialogInfo);
                    } else {
                        int lastTime = dialogInfo.y;
                        if (lastTime + notifyDelay < System.currentTimeMillis() / 1000) {
                            dialogInfo.set(1, (int) (System.currentTimeMillis() / 1000));
                        } else {
                            int count = dialogInfo.x;
                            if (count < notifyMaxCount) {
                                dialogInfo.set(count + 1, (int) (System.currentTimeMillis() / 1000));
                            } else {
                                notifyDisabled = true;
                            }
                        }
                    }
                }
            }

            String defaultPath = Settings.System.DEFAULT_NOTIFICATION_URI.getPath();
            if (!notifyDisabled) {
                inAppSounds = preferences.getBoolean("EnableInAppSounds", true);
                inAppVibrate = preferences.getBoolean("EnableInAppVibrate", true);
                inAppPreview = preferences.getBoolean("EnableInAppPreview", true);
                inAppPriority = preferences.getBoolean("EnableInAppPriority", false);
                vibrateOverride = preferences.getInt("vibrate_" + dialog_id, 0);
                priorityOverride = preferences.getInt("priority_" + dialog_id, 3);
                boolean vibrateOnlyIfSilent = false;

                choosenSoundPath = preferences.getString("sound_path_" + dialog_id, null);
                if (chat_id != 0) {
                    if (choosenSoundPath != null && choosenSoundPath.equals(defaultPath)) {
                        choosenSoundPath = null;
                    } else if (choosenSoundPath == null) {
                        choosenSoundPath = preferences.getString("GroupSoundPath", defaultPath);
                    }
                    needVibrate = preferences.getInt("vibrate_group", 0);
                    priority = preferences.getInt("priority_group", 1);
                    ledColor = preferences.getInt("GroupLed", 0xff00ff00);
                } else if (user_id != 0) {
                    if (choosenSoundPath != null && choosenSoundPath.equals(defaultPath)) {
                        choosenSoundPath = null;
                    } else if (choosenSoundPath == null) {
                        choosenSoundPath = preferences.getString("GlobalSoundPath", defaultPath);
                    }
                    needVibrate = preferences.getInt("vibrate_messages", 0);
                    priority = preferences.getInt("priority_group", 1);
                    ledColor = preferences.getInt("MessagesLed", 0xff00ff00);
                }
                if (preferences.contains("color_" + dialog_id)) {
                    ledColor = preferences.getInt("color_" + dialog_id, 0);
                }

                if (priorityOverride != 3) {
                    priority = priorityOverride;
                }

                if (needVibrate == 4) {
                    vibrateOnlyIfSilent = true;
                    needVibrate = 0;
                }
                if (needVibrate == 2 && (vibrateOverride == 1 || vibrateOverride == 3 || vibrateOverride == 5) || needVibrate != 2 && vibrateOverride == 2 || vibrateOverride != 0) {
                    needVibrate = vibrateOverride;
                }
                if (!ApplicationLoader.mainInterfacePaused) {
                    if (!inAppSounds) {
                        choosenSoundPath = null;
                    }
                    if (!inAppVibrate) {
                        needVibrate = 2;
                    }
                    if (!inAppPriority) {
                        priority = 0;
                    } else if (priority == 2) {
                        priority = 1;
                    }
                }
                if (vibrateOnlyIfSilent && needVibrate != 2) {
                    try {
                        int mode = audioManager.getRingerMode();
                        if (mode != AudioManager.RINGER_MODE_SILENT && mode != AudioManager.RINGER_MODE_VIBRATE) {
                            needVibrate = 2;
                        }
                    } catch (Exception e) {
                        FileLog.e("tmessages", e);
                    }
                }
            }

            Intent intent = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
            intent.setAction("com.tmessages.openchat" + Math.random() + Integer.MAX_VALUE);
            intent.setFlags(32768);
            if ((int)dialog_id != 0) {
                if (pushDialogs.size() == 1) {
                    if (chat_id != 0) {
                        intent.putExtra("chatId", chat_id);
                    } else if (user_id != 0) {
                        intent.putExtra("userId", user_id);
                    }
                }
                if (AndroidUtilities.needShowPasscode(false) || UserConfig.isWaitingForPasscodeEnter) {
                    photoPath = null;
                } else {
                    if (pushDialogs.size() == 1) {
                        if (chat != null) {
                            if (chat.photo != null && chat.photo.photo_small != null && chat.photo.photo_small.volume_id != 0 && chat.photo.photo_small.local_id != 0) {
                                photoPath = chat.photo.photo_small;
                            }
                        } else if (user != null) {
                            if (user.photo != null && user.photo.photo_small != null && user.photo.photo_small.volume_id != 0 && user.photo.photo_small.local_id != 0) {
                                photoPath = user.photo.photo_small;
                            }
                        }
                    }
                }
            } else {
                if (pushDialogs.size() == 1) {
                    intent.putExtra("encId", (int) (dialog_id >> 32));
                }
            }
            PendingIntent contentIntent = PendingIntent.getActivity(ApplicationLoader.applicationContext, 0, intent, PendingIntent.FLAG_ONE_SHOT);

            String name;
            boolean replace = true;
            if ((int) dialog_id == 0 || pushDialogs.size() > 1 || AndroidUtilities.needShowPasscode(false) || UserConfig.isWaitingForPasscodeEnter) {
                name = LocaleController.getString("AppName", R.string.AppName);
                replace = false;
            } else {
                if (chat != null) {
                    name = chat.title;
                } else {
                    name = UserObject.getUserName(user);
                }
            }

            String detailText;
            if (pushDialogs.size() == 1) {
                detailText = LocaleController.formatPluralString("NewMessages", total_unread_count);
            } else {
                detailText = LocaleController.formatString("NotificationMessagesPeopleDisplayOrder", R.string.NotificationMessagesPeopleDisplayOrder, LocaleController.formatPluralString("NewMessages", total_unread_count), LocaleController.formatPluralString("FromChats", pushDialogs.size()));
            }

            NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(ApplicationLoader.applicationContext)
                    .setContentTitle(name)
                    .setSmallIcon(R.drawable.notification)
                    .setAutoCancel(true)
                    .setNumber(total_unread_count)
                    .setContentIntent(contentIntent)
                    .setGroup("messages")
                    .setGroupSummary(true)
                    .setColor(0xff2ca5e0);

            if (!notifyAboutLast) {
                mBuilder.setPriority(NotificationCompat.PRIORITY_LOW);
            } else {
                if (priority == 0) {
                    mBuilder.setPriority(NotificationCompat.PRIORITY_DEFAULT);
                } else if (priority == 1) {
                    mBuilder.setPriority(NotificationCompat.PRIORITY_HIGH);
                } else if (priority == 2) {
                    mBuilder.setPriority(NotificationCompat.PRIORITY_MAX);
                }
            }

            mBuilder.setCategory(NotificationCompat.CATEGORY_MESSAGE);
            if (chat == null && user != null && user.phone != null && user.phone.length() > 0) {
                mBuilder.addPerson("tel:+" + user.phone);
            }

            String lastMessage = null;
            if (pushMessages.size() == 1) {
                String message = lastMessage = getStringForMessage(pushMessages.get(0), false);
                if (message == null) {
                    return;
                }
                if (replace) {
                    if (chat != null) {
                        message = message.replace(" @ " + name, "");
                    } else {
                        message = message.replace(name + ": ", "").replace(name + " ", "");
                    }
                }
                mBuilder.setContentText(message);
                mBuilder.setStyle(new NotificationCompat.BigTextStyle().bigText(message));
            } else {
                mBuilder.setContentText(detailText);
                NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();
                inboxStyle.setBigContentTitle(name);
                int limit = 7;
                int count = Math.min(limit, pushMessages.size());

                boolean newOnTop = preferences.getBoolean("messageOrder", true);
                
                    for (int i = 0; i < count; i++) {
                        int tmp = i;
                        if (!newOnTop) {
                            tmp = limit - 1  - i;
                        }

                        String message = getStringForMessage(pushMessages.get(tmp), false);
                        if (message == null) {
                            continue;
                        }
                        if (tmp == 0) {
                            lastMessage = message;
                        }
                        if (pushDialogs.size() == 1) {
                            if (replace) {
                                if (chat != null) {
                                    message = message.replace(" @ " + name, "");
                                } else {
                                    message = message.replace(name + ": ", "").replace(name + " ", "");
                                }
                            }
                        }
                        inboxStyle.addLine(message);
                    }

                if (pushMessages.size() > limit) {
                    detailText += " +";
                }

                inboxStyle.setSummaryText(detailText);
                mBuilder.setStyle(inboxStyle);
            }

            if (photoPath != null) {
                BitmapDrawable img = ImageLoader.getInstance().getImageFromMemory(photoPath, null, "50_50");
                if (img != null) {
                    mBuilder.setLargeIcon(img.getBitmap());
                }
            }

            if (!notifyDisabled) {
                if (ApplicationLoader.mainInterfacePaused || inAppPreview) {
                    if (lastMessage.length() > 100) {
                        lastMessage = lastMessage.substring(0, 100).replace("\n", " ").trim() + "...";
                    }
                    mBuilder.setTicker(lastMessage);
                }
                if (choosenSoundPath != null && !choosenSoundPath.equals("NoSound")) {
                    if (choosenSoundPath.equals(defaultPath)) {
                        /*MediaPlayer mediaPlayer = new MediaPlayer();
                        mediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
                        mediaPlayer.setDataSource(ApplicationLoader.applicationContext, Settings.System.DEFAULT_NOTIFICATION_URI);
                        mediaPlayer.prepare();
                        mediaPlayer.start();*/
                        mBuilder.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, AudioManager.STREAM_NOTIFICATION);
                    } else {
                        mBuilder.setSound(Uri.parse(choosenSoundPath), AudioManager.STREAM_NOTIFICATION);
                    }
                }
                if (ledColor != 0) {
                    mBuilder.setLights(ledColor, 1000, 1000);
                }
                if (needVibrate == 2) {
                    mBuilder.setVibrate(new long[]{0, 0});
                } else if (needVibrate == 1) {
                    mBuilder.setVibrate(new long[]{0, 100, 0, 100});
                } else if (needVibrate == 0 || needVibrate == 4) {
                    mBuilder.setDefaults(NotificationCompat.DEFAULT_VIBRATE);
                } else if (needVibrate == 3) {
                    mBuilder.setVibrate(new long[]{0, 1000});
                }
            } else {
                mBuilder.setVibrate(new long[]{0, 0});
            }

            showExtraNotifications(mBuilder, notifyAboutLast);
            notificationManager.notify(1, mBuilder.build());

            scheduleNotificationRepeat();
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    @SuppressLint("InlinedApi")
    private void showExtraNotifications(NotificationCompat.Builder notificationBuilder, boolean notifyAboutLast) {
        if (Build.VERSION.SDK_INT < 18) {
            return;
        }

        ArrayList<Long> sortedDialogs = new ArrayList<>();
        HashMap<Long, ArrayList<MessageObject>> messagesByDialogs = new HashMap<>();
        for (int a = 0; a < pushMessages.size(); a++) {
            MessageObject messageObject = pushMessages.get(a);
            long dialog_id = messageObject.getDialogId();
            if ((int)dialog_id == 0) {
                continue;
            }

            ArrayList<MessageObject> arrayList = messagesByDialogs.get(dialog_id);
            if (arrayList == null) {
                arrayList = new ArrayList<>();
                messagesByDialogs.put(dialog_id, arrayList);
                sortedDialogs.add(0, dialog_id);
            }
            arrayList.add(messageObject);
        }

        HashMap<Long, Integer> oldIdsWear = new HashMap<>();
        oldIdsWear.putAll(wearNotificationsIds);
        wearNotificationsIds.clear();

        HashMap<Long, Integer> oldIdsAuto = new HashMap<>();
        oldIdsAuto.putAll(autoNotificationsIds);
        autoNotificationsIds.clear();

        for (int b = 0; b < sortedDialogs.size(); b++) {
            long dialog_id = sortedDialogs.get(b);
            ArrayList<MessageObject> messageObjects = messagesByDialogs.get(dialog_id);
            int max_id = messageObjects.get(0).getId();
            int max_date = messageObjects.get(0).messageOwner.date;
            TLRPC.Chat chat = null;
            TLRPC.User user = null;
            String name;
            if (dialog_id > 0) {
                user = MessagesController.getInstance().getUser((int)dialog_id);
                if (user == null) {
                    continue;
                }
            } else {
                chat = MessagesController.getInstance().getChat(-(int)dialog_id);
                if (chat == null) {
                    continue;
                }
            }
            TLRPC.FileLocation photoPath = null;
            if (AndroidUtilities.needShowPasscode(false) || UserConfig.isWaitingForPasscodeEnter) {
                name = LocaleController.getString("AppName", R.string.AppName);
            } else {
                if (chat != null) {
                    name = chat.title;
                } else {
                    name = UserObject.getUserName(user);
                }
                if (chat != null) {
                    if (chat.photo != null && chat.photo.photo_small != null && chat.photo.photo_small.volume_id != 0 && chat.photo.photo_small.local_id != 0) {
                        photoPath = chat.photo.photo_small;
                    }
                } else {
                    if (user.photo != null && user.photo.photo_small != null && user.photo.photo_small.volume_id != 0 && user.photo.photo_small.local_id != 0) {
                        photoPath = user.photo.photo_small;
                    }
                }
            }

            Integer notificationIdWear = oldIdsWear.get(dialog_id);
            if (notificationIdWear == null) {
                notificationIdWear = wearNotificationId++;
            } else {
                oldIdsWear.remove(dialog_id);
            }

            Integer notificationIdAuto = oldIdsAuto.get(dialog_id);
            if (notificationIdAuto == null) {
                notificationIdAuto = autoNotificationId++;
            } else {
                oldIdsAuto.remove(dialog_id);
            }

            NotificationCompat.CarExtender.UnreadConversation.Builder unreadConvBuilder = new NotificationCompat.CarExtender.UnreadConversation.Builder(name).setLatestTimestamp((long) max_date * 1000);

            Intent msgHeardIntent = new Intent();
            msgHeardIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            msgHeardIntent.setAction("org.telegram.messenger.ACTION_MESSAGE_HEARD");
            msgHeardIntent.putExtra("dialog_id", dialog_id);
            msgHeardIntent.putExtra("max_id", max_id);
            PendingIntent msgHeardPendingIntent = PendingIntent.getBroadcast(ApplicationLoader.applicationContext, notificationIdAuto, msgHeardIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            unreadConvBuilder.setReadPendingIntent(msgHeardPendingIntent);

            NotificationCompat.Action wearReplyAction = null;

            if (!ChatObject.isChannel(chat) && !AndroidUtilities.needShowPasscode(false) && !UserConfig.isWaitingForPasscodeEnter) {
                Intent msgReplyIntent = new Intent();
                msgReplyIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                msgReplyIntent.setAction("org.telegram.messenger.ACTION_MESSAGE_REPLY");
                msgReplyIntent.putExtra("dialog_id", dialog_id);
                msgReplyIntent.putExtra("max_id", max_id);
                PendingIntent msgReplyPendingIntent = PendingIntent.getBroadcast(ApplicationLoader.applicationContext, notificationIdAuto, msgReplyIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                RemoteInput remoteInputAuto = new RemoteInput.Builder(NotificationsController.EXTRA_VOICE_REPLY).setLabel(LocaleController.getString("Reply", R.string.Reply)).build();
                unreadConvBuilder.setReplyAction(msgReplyPendingIntent, remoteInputAuto);

                Intent replyIntent = new Intent(ApplicationLoader.applicationContext, WearReplyReceiver.class);
                replyIntent.putExtra("dialog_id", dialog_id);
                replyIntent.putExtra("max_id", max_id);
                PendingIntent replyPendingIntent = PendingIntent.getBroadcast(ApplicationLoader.applicationContext, notificationIdWear, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                RemoteInput remoteInputWear = new RemoteInput.Builder(EXTRA_VOICE_REPLY).setLabel(LocaleController.getString("Reply", R.string.Reply)).build();
                String replyToString;
                if (chat != null) {
                    replyToString = LocaleController.formatString("ReplyToGroup", R.string.ReplyToGroup, name);
                } else {
                    replyToString = LocaleController.formatString("ReplyToUser", R.string.ReplyToUser, name);
                }
                wearReplyAction = new NotificationCompat.Action.Builder(R.drawable.ic_reply_icon, replyToString, replyPendingIntent).addRemoteInput(remoteInputWear).build();
            }

            String text = "";
            for (int a = messageObjects.size() - 1; a >= 0; a--) {
                MessageObject messageObject = messageObjects.get(a);
                String message = getStringForMessage(messageObject, false);
                if (message == null) {
                    continue;
                }
                if (chat != null) {
                    message = message.replace(" @ " + name, "");
                } else {
                    message = message.replace(name + ": ", "").replace(name + " ", "");
                }
                if (text.length() > 0) {
                    text += "\n\n";
                }
                text += message;

                unreadConvBuilder.addMessage(message);
            }

            Intent intent = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
            intent.setAction("com.tmessages.openchat" + Math.random() + Integer.MAX_VALUE);
            intent.setFlags(32768);
            if (chat != null) {
                intent.putExtra("chatId", chat.id);
            } else if (user != null) {
                intent.putExtra("userId", user.id);
            }
            PendingIntent contentIntent = PendingIntent.getActivity(ApplicationLoader.applicationContext, 0, intent, PendingIntent.FLAG_ONE_SHOT);

            NotificationCompat.WearableExtender wearableExtender = new NotificationCompat.WearableExtender();
            if (wearReplyAction != null) {
                wearableExtender.addAction(wearReplyAction);
            }

            NotificationCompat.Builder builder = new NotificationCompat.Builder(ApplicationLoader.applicationContext)
                    .setContentTitle(name)
                    .setSmallIcon(R.drawable.notification)
                    .setGroup("messages")
                    .setContentText(text)
                    .setColor(0xff2ca5e0)
                    .setGroupSummary(false)
                    .setContentIntent(contentIntent)
                    .extend(wearableExtender)
                    .extend(new NotificationCompat.CarExtender().setUnreadConversation(unreadConvBuilder.build()))
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE);
            if (photoPath != null) {
                BitmapDrawable img = ImageLoader.getInstance().getImageFromMemory(photoPath, null, "50_50");
                if (img != null) {
                    builder.setLargeIcon(img.getBitmap());
                }
            }

            if (chat == null && user != null && user.phone != null && user.phone.length() > 0) {
                builder.addPerson("tel:+" + user.phone);
            }

            notificationManager.notify(notificationIdWear, builder.build());
            wearNotificationsIds.put(dialog_id, notificationIdWear);
        }

        for (HashMap.Entry<Long, Integer> entry : oldIdsWear.entrySet()) {
            notificationManager.cancel(entry.getValue());
        }
    }

    public void playOutChatSound() {
        if (!inChatSoundEnabled) {
            return;
        }
        try {
            if (audioManager.getRingerMode() == AudioManager.RINGER_MODE_SILENT) {
                return;
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        notificationsQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Math.abs(System.currentTimeMillis() - lastSoundOutPlay) <= 100) {
                        return;
                    }
                    lastSoundOutPlay = System.currentTimeMillis();
                    if (soundPool == null) {
                        soundPool = new SoundPool(2, AudioManager.STREAM_SYSTEM, 0);
                        soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                            @Override
                            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                                if (status == 0) {
                                    soundPool.play(sampleId, 1.0f, 1.0f, 1, 0, 1.0f);
                                }
                            }
                        });
                    }
                    if (soundOut == 0 && !soundOutLoaded) {
                        soundOutLoaded = true;
                        soundOut = soundPool.load(ApplicationLoader.applicationContext, R.raw.sound_out, 1);
                    }
                    if (soundOut != 0) {
                        soundPool.play(soundOut, 1.0f, 1.0f, 1, 0, 1.0f);
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public static void updateServerNotificationsSettings(long dialog_id) {
        NotificationCenter.getInstance().postNotificationName(NotificationCenter.notificationsSettingsUpdated);
        if ((int)dialog_id == 0) {
            return;
        }
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("Notifications", Activity.MODE_PRIVATE);
        TLRPC.TL_account_updateNotifySettings req = new TLRPC.TL_account_updateNotifySettings();
        req.settings = new TLRPC.TL_inputPeerNotifySettings();
        req.settings.sound = "default";
        req.settings.events_mask = 0;
        int mute_type = preferences.getInt("notify2_" + dialog_id, 0);
        if (mute_type == 3) {
            req.settings.mute_until = preferences.getInt("notifyuntil_" + dialog_id, 0);
        } else {
            req.settings.mute_until = mute_type != 2 ? 0 : Integer.MAX_VALUE;
        }
        req.settings.show_previews = preferences.getBoolean("preview_" + dialog_id, true);

        req.peer = new TLRPC.TL_inputNotifyPeer();

        ((TLRPC.TL_inputNotifyPeer)req.peer).peer = MessagesController.getInputPeer((int) dialog_id);
        if (((TLRPC.TL_inputNotifyPeer)req.peer).peer == null) {
            return;
        }

        ConnectionsManager.getInstance().sendRequest(req, new RequestDelegate() {
            @Override
            public void run(TLObject response, TLRPC.TL_error error) {

            }
        });
    }
}
