/*
 * libBink2x64 stub for Zomdroid.
 *
 * Project Zomboid's zombie.core.textures.VideoTexture.<clinit> calls
 * System.loadLibrary("Bink2x64") to play RAD Bink intro/preview videos. That
 * proprietary library does not exist on Android, so the load fails and every
 * video request spams the game log with a huge UnsatisfiedLinkError /
 * NoClassDefFoundError stack trace.
 *
 * This empty library exists only so loadLibrary() succeeds and the class
 * initializes. Since no Bink video files are shipped, getVideo() then just logs
 * "Could not find video" and returns — without the stack trace.
 *
 * No JNI symbols are needed: dlopen() of a valid (even empty) ELF is enough for
 * System.loadLibrary to succeed; JNI_OnLoad is optional.
 */
int zomdroid_bink_stub = 0;
