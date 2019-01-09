package org.greenrobot.eventbus;

import java.util.ArrayList;

/**
 * Created By YuanGuodong
 * 2018/12/27
 */
final class InnerEventWrapper {
    /**
     * 对象池
     */
    private static final ArrayList<InnerEventWrapper> mPool = new ArrayList<>();
    private static final int POOL_MAX_SIZE = 128;

    String channel = "";
    Object event;

    private InnerEventWrapper(String channel, Object event) {
        this.channel = channel;
        this.event = event;
    }

    static InnerEventWrapper obtain(String channel, Object event) {
        synchronized (mPool) {
            final int size = mPool.size();
            if (size > 0) {
                final InnerEventWrapper innerEventWrapper = mPool.remove(size - 1);
                innerEventWrapper.event = event;
                innerEventWrapper.channel = channel;
                return innerEventWrapper;
            }
        }
        return new InnerEventWrapper(channel, event);
    }

    static InnerEventWrapper create(String channel, Object event) {
        return new InnerEventWrapper(channel, event);
    }

    static void release(InnerEventWrapper innerEventWrapper) {
        innerEventWrapper.event = null;
        innerEventWrapper.channel = "";
        synchronized (mPool) {
            if (mPool.size() < POOL_MAX_SIZE) {
                mPool.add(innerEventWrapper);
            }
        }
    }

}
