package com.kaizen.eventbuscompat;

import android.support.v4.app.FragmentActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class MainActivity extends FragmentActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        EventBusManager.get().register(this, getLifecycle());
        EventBusManager.get().register(new Person(), getLifecycle());
    }


    public void clickEvent(View view) {
        EventBusManager.get().post("attention","ygd 发送消息  11112222");
        EventBusManager.get().post("ygd 发送消息  global global  11112222");
        EventBusManager.get().unregister(this);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessage(String msg) {
        Log.d("ygd", msg);
    }


    @Subscribe(threadMode = ThreadMode.POSTING, channel = "person")
    public void onSomeMessage(String msg) {
        Log.d("ygd", "channel : person  msg: " +  msg);
    }
}
