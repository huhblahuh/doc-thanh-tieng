package app.docthanhtieng;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;

/** Keeps reading alive with the screen off and shows the controls in the notification. */
public class ReaderService extends Service {
    private static final String CH = "doc";
    private static final int ID = 1;
    private Player player;
    private final Player.Listener listener = this::refresh;

    @Override
    public void onCreate() {
        super.onCreate();
        player = Player.get(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CH, "Đang đọc", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        player.svc = listener;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(ID, n);
            }
        } catch (Exception ignored) {
        }
        String a = intent == null ? null : intent.getAction();
        if ("toggle".equals(a)) player.toggle();
        else if ("prev".equals(a)) player.prevSentence();
        else if ("next".equals(a)) player.nextItem();
        else if ("close".equals(a)) {
            player.pause();
            quit();
        }
        return START_NOT_STICKY;
    }

    private void quit() {
        stopForeground(true);
        stopSelf();
    }

    private void refresh() {
        if (!player.isPlaying() && player.hasEnded()) {
            quit();
            return;
        }
        try {
            getSystemService(NotificationManager.class).notify(ID, build());
        } catch (Exception ignored) {
        }
    }

    private PendingIntent pi(String action, int code) {
        Intent i = new Intent(this, ReaderService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification.Action act(int icon, String label, String action, int code) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi(action, code)).build();
    }

    private Notification build() {
        boolean playing = player.isPlaying();
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String title = player.title();
        return new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title.isEmpty() ? getString(R.string.app_name) : title)
                .setContentText(player.progress())
                .setContentIntent(content)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(act(android.R.drawable.ic_media_previous, "Câu trước", "prev", 1))
                .addAction(act(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Dừng" : "Đọc", "toggle", 2))
                .addAction(act(android.R.drawable.ic_media_next, "Bài kế", "next", 3))
                .addAction(act(android.R.drawable.ic_menu_close_clear_cancel, "Đóng", "close", 4))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(player.session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!player.isPlaying()) quit();
    }

    @Override
    public void onDestroy() {
        if (player.svc == listener) player.svc = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
