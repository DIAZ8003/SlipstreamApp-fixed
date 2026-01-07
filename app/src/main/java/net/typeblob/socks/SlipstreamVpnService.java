package net.typeblob.socks;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

public class SlipstreamVpnService extends VpnService {

    private static final String TAG = "SlipstreamVPN";
    private ParcelFileDescriptor tun;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Starting Slipstream VPNService");

        try {
            Builder builder = new Builder();
            builder.setSession("Slipstream VPN");
            builder.setMtu(1500);

            builder.addAddress("26.26.26.1", 24);
            builder.addRoute("0.0.0.0", 0);
            builder.addDnsServer("8.8.8.8");

            tun = builder.establish();
            if (tun == null) {
                Log.e(TAG, "Failed to establish TUN");
                stopSelf();
                return START_NOT_STICKY;
            }

            startForegroundNotification();

            Log.i(TAG, "TUN established, fd=" + tun.getFd());

            //  NO tun2socks aquðŸ todavšÃ­Ã­a
            //  NO backend
            // ðŸš NO exec

        } catch (Exception e) {
            Log.e(TAG, "Fatal error", e);
            stopSelf();
        }

        return START_STICKY;
    }

    private void startForegroundNotification() {
        String channelId = "slipstream_vpn";

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "Slipstream VPN",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Notification notification = new Notification.Builder(this, channelId)
                .setContentTitle("Slipstream VPN")
                .setContentText("VPN active (safe mode)")
                .setSmallIcon(R.drawable.ic_vpn)
                .build();

        startForeground(1, notification);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "VPNService destroyed");
        try {
            if (tun != null) tun.close();
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
