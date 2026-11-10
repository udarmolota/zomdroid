package com.zomdroid;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Locale;

/**
 * Who signed the installed APK, for the bug report. GitHub releases are signed with the release
 * key, rotated from the public AOSP test key that signed every release before it (the lineage in
 * tools/signing/zomdroid.lineage), so an update installs over an old release and keeps its data.
 * Repacks from APK sites carry the site's own key (seen in ValDroid: CN=APKVISION.ORG), which makes
 * a report from one recognisable at a glance.
 */
final class ApkSigner {
    /** SHA-256 of the release signing certificate (also printed in README.md). */
    private static final String RELEASE_CERT_SHA256 =
            "f85a437a5090fb84b9eb2a4cbff5abc07e4d3ba2d5c4be0259636f3a624ed2c6";
    /** SHA-256 of the AOSP test key: releases before the rotation, and test builds. */
    private static final String AOSP_TEST_KEY_SHA256 =
            "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc";

    private ApkSigner() {}

    static String describe(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            android.content.pm.Signature[] signers = pi.signingInfo.getApkContentsSigners();
            StringBuilder sb = new StringBuilder();
            for (android.content.pm.Signature s : signers) {
                byte[] der = s.toByteArray();
                byte[] d = MessageDigest.getInstance("SHA-256").digest(der);
                StringBuilder hex = new StringBuilder(64);
                for (byte b : d) hex.append(String.format(Locale.US, "%02x", b));
                if (sb.length() > 0) sb.append("; ");
                if (RELEASE_CERT_SHA256.contentEquals(hex)) {
                    sb.append("release key");
                } else if (AOSP_TEST_KEY_SHA256.contentEquals(hex)) {
                    sb.append("AOSP test key (an older release or a test build)");
                } else {
                    String subject;
                    try {
                        subject = ((X509Certificate) CertificateFactory.getInstance("X.509")
                                .generateCertificate(new ByteArrayInputStream(der)))
                                .getSubjectX500Principal().getName();
                    } catch (Throwable t) {
                        subject = "?";
                    }
                    sb.append("NOT the release key: ").append(subject).append(", sha256 ").append(hex);
                }
            }
            return sb.length() > 0 ? sb.toString() : "(no signers)";
        } catch (Throwable t) {
            return "unavailable: " + t;
        }
    }
}
