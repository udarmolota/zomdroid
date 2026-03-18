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
            default:
                wikiFile = "index.html";
        }
        webView.loadUrl("file:///android_asset/wiki/" + wikiFile);

        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true);
        @ColorInt int backgroundColor = ContextCompat.getColor(context, typedValue.resourceId);
        webView.setBackgroundColor(backgroundColor);

        return webView;
    }
}
