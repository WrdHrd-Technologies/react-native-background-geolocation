package com.marianhello.bgloc.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.marianhello.bgloc.ResourceResolver;
import com.marianhello.logging.LoggerManager;

public final class NotificationHelper {
    private static final String TAG = "NotificationHelper";

    public static final String SERVICE_CHANNEL_ID = "bglocservice";
    public static final String ANDROID_PERMISSIONS_CHANNEL_ID = "android-permissions";
    public static final String SYNC_CHANNEL_ID = "syncservice";
    
    public static final String SYNC_CHANNEL_NAME = "Sync Service";
    public static final String SYNC_CHANNEL_DESCRIPTION = "Shows sync progress";

    private NotificationHelper() {
        throw new UnsupportedOperationException("Notification utility configuration layers cannot be instantiated.");
    }

    public static class NotificationFactory {
        private final Context mContext;
        private final ResourceResolver mResolver;
        private final org.slf4j.Logger logger;

        public NotificationFactory(@NonNull Context context) {
            this.mContext = context.getApplicationContext();
            this.mResolver = ResourceResolver.newInstance(mContext);
            this.logger = LoggerManager.getLogger(NotificationFactory.class);
        }

        @Nullable
        private Integer parseNotificationIconColor(@Nullable String color) {
            if (color == null || color.trim().isEmpty()) return null;
            try {
                return Color.parseColor(color);
            } catch (IllegalArgumentException e) {
                logger.error("Failed to decode requested string color mapping array from background options: {}", color);
                return null;
            }
        }

        @NonNull
        public Notification getNotification(@Nullable String title, @Nullable String text, @Nullable String largeIcon, @Nullable String smallIcon, @Nullable String color) {
            Context appContext = mContext;

            NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, NotificationHelper.SERVICE_CHANNEL_ID);

            builder.setContentTitle(title != null ? title : "")
                   .setContentText(text != null ? text : "");

            int smallIconResId = 0;
            if (smallIcon != null && !smallIcon.isEmpty()) {
                smallIconResId = mResolver.getDrawable(smallIcon);
            }
            
            if (smallIconResId != 0) {
                builder.setSmallIcon(smallIconResId);
            } else {
                int defaultAppIcon = appContext.getApplicationInfo().icon;
                builder.setSmallIcon(defaultAppIcon != 0 ? defaultAppIcon : android.R.drawable.sym_def_app_icon);
            }

            if (largeIcon != null && !largeIcon.isEmpty()) {
                int largeIconResId = mResolver.getDrawable(largeIcon);
                if (largeIconResId != 0) {
                    try {
                        builder.setLargeIcon(BitmapFactory.decodeResource(appContext.getResources(), largeIconResId));
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to compile large icon resource descriptor layer layout bounds.", e);
                    }
                }
            }

            Integer decodedColor = parseNotificationIconColor(color);
            if (decodedColor != null) {
                builder.setColor(decodedColor);
            }

            String packageName = appContext.getPackageName();
            Intent launchIntent = appContext.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                
                int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
                }
                
                PendingIntent contentIntent = PendingIntent.getActivity(appContext, 0, launchIntent, pendingIntentFlags);
                builder.setContentIntent(contentIntent);
            }

            builder.setOngoing(true)
                   .setLocalOnly(true)
                   .setShowWhen(true)
                   .setPriority(NotificationCompat.PRIORITY_MAX); 

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setCategory(Notification.CATEGORY_NAVIGATION);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
            }

            Notification notification = builder.build();
            notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;

            return notification;
        }

        /**
         * Standard Non-Dismissible Sync Notification (HeadlessService / WorkManager getForegroundInfoAsync)
         */
        @NonNull
        public Notification getSyncNotification(@Nullable String title, @Nullable String text) {
            Context appContext = mContext;

            NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, NotificationHelper.SYNC_CHANNEL_ID);

            builder.setContentTitle(title != null ? title : "Syncing locations")
                   .setContentText(text != null ? text : "Sync in progress");

            int defaultAppIcon = appContext.getApplicationInfo().icon;
            builder.setSmallIcon(defaultAppIcon != 0 ? defaultAppIcon : android.R.drawable.sym_def_app_icon);

            builder.setOngoing(true)
                   .setLocalOnly(true)
                   .setShowWhen(true)
                   .setPriority(NotificationCompat.PRIORITY_MIN);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
            }

            Notification notification = builder.build();
            notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;

            return notification;
        }

        /**
         * Progress-enabled Sync Notification for Active Network Batches
         */
        @NonNull
        public Notification getSyncProgressNotification(@Nullable String title, @Nullable String text, int progress) {
            Context appContext = mContext;

            NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, NotificationHelper.SYNC_CHANNEL_ID);

            builder.setContentTitle(title != null ? title : "Syncing locations")
                   .setContentText(text != null ? text : "Sync in progress");

            int defaultAppIcon = appContext.getApplicationInfo().icon;
            builder.setSmallIcon(defaultAppIcon != 0 ? defaultAppIcon : android.R.drawable.sym_def_app_icon);

            builder.setOngoing(true)
                   .setOnlyAlertOnce(true)
                   .setLocalOnly(true)
                   .setPriority(NotificationCompat.PRIORITY_MIN);

            if (progress >= 0) {
                builder.setProgress(100, progress, false);
            } else {
                builder.setProgress(0, 0, true);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
            }

            Notification notification = builder.build();
            notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;

            return notification;
        }

        /**
         * Dismissible Completion Notification when Sync Finishes
         */
        @NonNull
        public Notification getSyncCompletedNotification(@Nullable String title, @Nullable String text) {
            Context appContext = mContext;

            NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, NotificationHelper.SYNC_CHANNEL_ID);

            builder.setContentTitle(title != null ? title : "Syncing locations")
                   .setContentText(text != null ? text : "Sync completed");

            int defaultAppIcon = appContext.getApplicationInfo().icon;
            builder.setSmallIcon(defaultAppIcon != 0 ? defaultAppIcon : android.R.drawable.sym_def_app_icon);

            builder.setOngoing(false)
                   .setAutoCancel(true)
                   .setLocalOnly(true)
                   .setPriority(NotificationCompat.PRIORITY_MIN);

            Notification notification = builder.build();
            notification.flags &= ~Notification.FLAG_ONGOING_EVENT;
            notification.flags &= ~Notification.FLAG_NO_CLEAR;

            return notification;
        }

        /**
         * Permission Alert Notification
         */
        @NonNull
        public Notification getPermissionDeniedNotification(@Nullable String title, @Nullable String text) {
            Context appContext = mContext;

            NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, NotificationHelper.ANDROID_PERMISSIONS_CHANNEL_ID);

            builder.setContentTitle(title != null ? title : "Permission Required")
                   .setContentText(text != null ? text : "Background location access is required.")
                   .setSmallIcon(android.R.drawable.ic_dialog_info)
                   .setPriority(NotificationCompat.PRIORITY_HIGH)
                   .setAutoCancel(true);

            Intent launchIntent = appContext.getPackageManager().getLaunchIntentForPackage(appContext.getPackageName());
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            
                int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
                }
            
                PendingIntent contentIntent = PendingIntent.getActivity(appContext, 0, launchIntent, pendingIntentFlags);
                builder.setContentIntent(contentIntent);
            }

            return builder.build();
        }
    }

    public static void registerAllChannels(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = (NotificationManager) context.getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                String appName = ResourceResolver.newInstance(context).getString("app_name");
                notificationManager.createNotificationChannel(createServiceChannel(appName));
                notificationManager.createNotificationChannel(createSyncChannel());
                notificationManager.createNotificationChannel(createAndroidPermissionsChannel(appName));
            }
        }
    }

    public static void registerServiceChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = (NotificationManager) context.getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                String appName = ResourceResolver.newInstance(context).getString("app_name");
                notificationManager.createNotificationChannel(createServiceChannel(appName));
            }
        }
    }

    public static void registerSyncChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = (NotificationManager) context.getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(createSyncChannel());
            }
        }
    }

    @NonNull
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static NotificationChannel createServiceChannel(CharSequence name) {
        NotificationChannel channel = new NotificationChannel(SERVICE_CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW);
        channel.enableVibration(false);
        channel.setSound(null, null);
        channel.setShowBadge(false);
        return channel;
    }

    @NonNull
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static NotificationChannel createSyncChannel() {
        NotificationChannel channel = new NotificationChannel(SYNC_CHANNEL_ID, SYNC_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(SYNC_CHANNEL_DESCRIPTION);
        channel.enableVibration(false);
        channel.setSound(null, null);
        channel.setShowBadge(false);
        return channel;
    }

    @NonNull
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static NotificationChannel createAndroidPermissionsChannel(CharSequence name) {
        NotificationChannel channel = new NotificationChannel(ANDROID_PERMISSIONS_CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH);
        channel.enableVibration(false);
        return channel;
    }
}