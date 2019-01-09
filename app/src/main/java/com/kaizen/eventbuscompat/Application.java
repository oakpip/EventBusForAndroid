package com.kaizen.eventbuscompat;

import org.greenrobot.eventbus.EventBus;

import java.lang.ref.WeakReference;

/**
 * Created By YuanGuodong
 * 2018/12/21
 */
public class Application extends android.app.Application {

    private static WeakReference<Application> sApplication;
    @Override
    public void onCreate() {
        super.onCreate();
        sApplication = new WeakReference<>(this);
        EventBusManager.get();
    }

    public static Application getSelf() {
        if (sApplication != null && sApplication.get() != null) {
            return sApplication.get();
        }
        return null;
    }
}
