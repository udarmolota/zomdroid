package com.zomdroid;

public interface TaskProgressListener {
    void onProgressUpdate(String message, int progress, int progressMax);
}
