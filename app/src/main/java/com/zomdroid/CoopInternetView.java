package com.zomdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Properties;

/** Small, tappable hosting status; full explanation stays out of the game controls. */
final class CoopInternetView {
    private final Activity activity;
    private LinearLayout bar;
    private TextView label;
    private AlertDialog dialog;
    private String message = "";
    CoopInternetView(Activity activity) { this.activity = activity; }

    void update(Properties status) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        String state = status.getProperty("state", "pending");
        String external = status.getProperty("externalAddress", "");
        String port = status.getProperty("port", "0");
        if (!port.matches("[0-9]{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535)
            port = activity.getString(R.string.coop_internet_port_unknown);
        int title;
        int detail;
        if (state.equals("mapped")) {
            title = R.string.coop_internet_mapped;
            detail = R.string.coop_internet_mapped_detail;
        } else if (state.equals("mapping_failed")) {
            title = R.string.coop_internet_failed;
            detail = R.string.coop_internet_failed_detail;
        } else if (state.equals("no_gateway")) {
            title = R.string.coop_internet_no_gateway;
            detail = R.string.coop_internet_no_gateway_detail;
        } else if (state.equals("unavailable")) {
            title = R.string.coop_internet_unknown;
            detail = R.string.coop_internet_unavailable_detail;
        } else if (state.equals("unknown")) {
            title = R.string.coop_internet_unknown;
            detail = R.string.coop_internet_unknown_detail;
        } else {
            title = R.string.coop_internet_pending;
            detail = R.string.coop_internet_pending_detail;
        }
        String shownAddress = external.isEmpty() ? activity.getString(R.string.coop_internet_address_unknown) : external;
        message = activity.getString(detail) + "\n\n" + activity.getString(R.string.coop_internet_addresses,
                port, shownAddress, lanAddress(activity.getString(R.string.coop_internet_address_unknown)));
        if (isNonPublicIpv4(external)) message += "\n\n" + activity.getString(R.string.coop_internet_double_nat);
        if (state.equals("mapped")) message += "\n\n" + activity.getString(R.string.coop_internet_unverified);
        if (label == null) {
            int pad = Math.round(8 * activity.getResources().getDisplayMetrics().density);
            // Text and close button share one background. Closing hides the strip only until the
            // status changes, so a later result (port mapped, mapping failed) still reaches the player.
            bar = new LinearLayout(activity);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(0xCC202020);
            label = new TextView(activity);
            label.setTextColor(Color.WHITE);
            label.setTextSize(12);
            label.setMaxLines(2);
            label.setPadding(pad, pad, pad / 2, pad);
            bar.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView dismiss = new TextView(activity);
            dismiss.setText("\u2715");
            dismiss.setTextColor(Color.WHITE);
            dismiss.setTextSize(14);
            dismiss.setGravity(Gravity.CENTER);
            dismiss.setMinWidth(pad * 5);
            dismiss.setPadding(pad, pad, pad, pad);
            dismiss.setContentDescription(activity.getString(R.string.coop_internet_close));
            dismiss.setOnClickListener(view -> bar.setVisibility(View.GONE));
            bar.addView(dismiss, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            params.topMargin = pad * 5;
            activity.addContentView(bar, params);
            label.setOnClickListener(view -> {
                if (dialog != null) dialog.dismiss();
                dialog = new AlertDialog.Builder(activity).setTitle(R.string.coop_internet_title).setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.coop_internet_copy, (d, which) -> {
                            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
                            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Hosting", message));
                        }).create();
                dialog.show();
            });
        }
        label.setText(activity.getString(title) + "  ⓘ");
        label.setContentDescription(activity.getString(R.string.coop_internet_title) + ": " + activity.getString(title));
        // Every call is a new status (the bridge deduplicates), so it shows the strip again.
        bar.setVisibility(View.VISIBLE);
        if (dialog != null && dialog.isShowing()) dialog.setMessage(message);
    }

    static boolean isNonPublicIpv4(String address) {
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("[0-9]{1,3}")) return false;
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) return false;
        }
        int a = octets[0], b = octets[1];
        return a == 0 || a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 168) || (a == 169 && b == 254)
                || (a == 100 && b >= 64 && b <= 127) || a >= 224;
    }

    private static String lanAddress(String fallback) {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback() || !network.getName().startsWith("wlan")) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses()))
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) return address.getHostAddress();
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    void close() {
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        if (bar != null && bar.getParent() instanceof ViewGroup) ((ViewGroup)bar.getParent()).removeView(bar);
        bar = null;
        label = null;
    }
}
