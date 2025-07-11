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

public class WikiFragment extends Fragment {
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Context context = requireContext();

        WebView webView = new WebView(context);
        webView.loadUrl("file:///android_asset/wiki/index.html");

        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.colorBackground, typedValue, true);
        @ColorInt int backgroundColor = ContextCompat.getColor(context, typedValue.resourceId);
        webView.setBackgroundColor(backgroundColor);

        return webView;
    }
}
