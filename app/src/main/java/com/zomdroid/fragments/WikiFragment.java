package com.zomdroid.fragments;

import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.util.Locale;

public class WikiFragment extends Fragment {
    /** Optional anchor ("renderers", "native-libraries", ...) to open the page at that section. */
    public static final String ARG_SECTION = "section";

    /** Navigation arguments that open the wiki at {@code section}. */
    public static Bundle section(String section) {
        Bundle args = new Bundle();
        args.putString(ARG_SECTION, section);
        return args;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Context context = requireContext();

        WebView webView = new WebView(context);

        String language = Locale.getDefault().getLanguage();
        String wikiFile;
        switch (language) {
            case "ru":
                wikiFile = "index_ru.html";
                break;
            case "zh":
                wikiFile = "index_zh.html";
                break;
            case "pt":
                wikiFile = "index_pt.html";
                break;
            // Android reports Indonesian with the legacy code "in"; newer runtimes may say "id".
            case "in":
            case "id":
                wikiFile = "index_in.html";
                break;
            default:
                wikiFile = "index.html";
        }
        String section = getArguments() != null ? getArguments().getString(ARG_SECTION) : null;
        // A page without that anchor (a translation not updated yet) simply opens at the top.
        final String url = "file:///android_asset/wiki/" + wikiFile
                + (section != null && section.matches("[a-z0-9-]+") ? "#" + section : "");
        // Load only once the WebView has its real width. The page comes from assets and finishes
        // loading before the view is laid out; Chromium then works out the #section offset on a
        // layout of almost no width — every word on its own line, the page several times taller —
        // and does not re-apply it after the resize, so the page opens far below the section.
        // Found in RimDroid's copy of this screen (2026-09-15): its "Quick start" opened at GOG.
        webView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (right - left <= 0) return;
                v.removeOnLayoutChangeListener(this);
                ((WebView) v).loadUrl(url);
            }
        });

        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true);
        @ColorInt int backgroundColor = ContextCompat.getColor(context, typedValue.resourceId);
        webView.setBackgroundColor(backgroundColor);

        return webView;
    }
}
