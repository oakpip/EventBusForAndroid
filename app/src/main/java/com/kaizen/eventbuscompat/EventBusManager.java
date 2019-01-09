package com.kaizen.eventbuscompat;

import android.arch.lifecycle.Lifecycle;
import android.util.Log;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.EventBusBuilder;

/**
 * Created By YuanGuodong
 * 2018/12/21
 */
public class EventBusManager {
    private static final String TAG = EventBusManager.class.getSimpleName();

    public static volatile EventBusManager sInstance;
    private EventBus mEventBus;

    public static EventBusManager get() {
        if (sInstance == null) {
            synchronized (EventBusManager.class) {
                if (sInstance == null) {
                    sInstance = new EventBusManager();
                }
            }
        }
        return sInstance;
    }

    private EventBusManager() {
        final EventBusBuilder builder = EventBus.builder().logNoSubscriberMessages(false)
                .addIndex(new EventBusIndex_Compat());
        if (builder != null) {
            mEventBus = builder.build();
        }
    }

    public void post(Object event) {
        mEventBus.post(event);
    }

    public void post(String channel, Object event) {
        mEventBus.post(channel, event);
    }


    public void postSticky(Object event) {
        mEventBus.postSticky(event);
    }

    public void register(Object subscriber) {
        try {
            mEventBus.register(subscriber);
        } catch (Exception e) {
            Log.e(TAG, e.getMessage(), e);
        }
    }

    /**
     * 订阅者注册的时候,如果传入相依附的lifecycle, 会自动反注册，不需要手动unRegister
     *
     * @param subscriber 订阅者
     * @param lifecycle  订阅者依附的生命周期
     */
    public void register(Object subscriber, Lifecycle lifecycle) {
        try {
            mEventBus.register(subscriber, lifecycle);
        } catch (Exception e) {
            Log.e(TAG, e.getMessage(), e);
        }
    }


    public void unregister(Object subscriber) {
        try {
            mEventBus.unregister(subscriber);
        } catch (Exception e) {
            Log.e(TAG, e.getMessage(), e);
        }
    }

}
