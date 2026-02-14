package com.example.helloworld;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // 启用 Material You 动态取色
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}