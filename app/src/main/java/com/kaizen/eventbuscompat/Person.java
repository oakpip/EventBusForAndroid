package com.kaizen.eventbuscompat;

import android.util.Log;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * Created By YuanGuodong
 * 2018/12/21
 */
public class Person {

    @Subscribe(threadMode = ThreadMode.MAIN, channel = "person")
    public void onEventMessage(String msg) {
        Log.d("ygd", "person : " + msg + " chanel : person");
    }


    @Subscribe(threadMode = ThreadMode.MAIN, channel = "attention")
    public void onAttentionMessage(String msg) {
        Log.d("ygd", "attention : " + msg + " chanel : attention"
                + " thread ： " + Thread.currentThread().getName() + "  methodName : onAttentionMessage");
    }

    @Subscribe(threadMode = ThreadMode.ASYNC, channel = "attention")
    public void onAttentaionMessageOnOtherThread(String msg) {
        Log.d("ygd", "attention : " + msg + " chanel : attention"
                + " thread ： " + Thread.currentThread().getName() + "  methodName : onAttentaionMessageOnOtherThread");
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND, channel = "attention")
    public void onAttentaionMessageOnBackgroundThread(String msg) {
        Log.d("ygd", "attention : " + msg + " chanel : attention"
                + " thread ： " + Thread.currentThread().getName() + "  methodName : onAttentaionMessageOnBackgroundThread");
    }
}
