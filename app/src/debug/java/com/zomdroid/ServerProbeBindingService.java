package com.zomdroid;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/** The visible client binds here to keep the already-running server process important. */
public final class ServerProbeBindingService extends Service {
    private final Binder binder = new Binder();

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public boolean onUnbind(Intent intent) {
        // Also called when the client JVM exits its Android process. The server remains
        // alive long enough to save instead of being left orphaned after the host leaves.
        android.util.Log.i("ServerProbe", "Client unbound; requesting server shutdown");
        ServerProbeActivity.requestSaveAndQuit();
        return false;
    }
}
