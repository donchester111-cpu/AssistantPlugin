package com.godwin.assistant.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;

import com.godwin.assistant.util.Logger;

/**
 * Battery Receiver - Handle battery state changes
 */
public class BatteryReceiver extends BroadcastReceiver {

    private static final String TAG = "BatteryReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int batteryPct = (level * 100) / scale;
            
            Logger.d(TAG, "Battery level: " + batteryPct + "%");

            Intent broadcast = new Intent("com.godwin.assistant.BATTERY_CHANGED");
            broadcast.putExtra("battery_level", batteryPct);
            context.sendBroadcast(broadcast);
        } else if (Intent.ACTION_BATTERY_LOW.equals(action)) {
            Logger.d(TAG, "Battery low");
            Intent broadcast = new Intent("com.godwin.assistant.BATTERY_LOW");
            context.sendBroadcast(broadcast);
        } else if (Intent.ACTION_BATTERY_OKAY.equals(action)) {
            Logger.d(TAG, "Battery okay");
            Intent broadcast = new Intent("com.godwin.assistant.BATTERY_OKAY");
            context.sendBroadcast(broadcast);
        }
    }
}
