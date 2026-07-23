package com.zomdroid.steam;

/** Implemented by a running download so the UI can stop it. */
public interface Cancellable {
    void cancel();
}
