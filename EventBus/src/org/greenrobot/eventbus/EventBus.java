/*
 * Copyright (C) 2012-2016 Markus Junginger, greenrobot (http://greenrobot.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.greenrobot.eventbus;

import android.arch.lifecycle.GenericLifecycleObserver;
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleOwner;
import android.text.TextUtils;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

/**
 * EventBus is a central publish/subscribe event system for Android. Events are posted ({@link #post(Object)}) to the
 * bus, which delivers it to subscribers that have a matching handler method for the event type. To receive events,
 * subscribers must register themselves to the bus using {@link #register(Object)}. Once registered, subscribers
 * receive events until {@link #unregister(Object)} is called. Event handling methods must be annotated by
 * {@link Subscribe}, must be public, return nothing (void), and have exactly one parameter
 * (the event).
 *
 * @author Markus Junginger, greenrobot
 */
public class EventBus {

    /**
     * Log tag, apps may override it.
     */
    public static String TAG = "EventBus";

    static volatile EventBus defaultInstance;

    private static final EventBusBuilder DEFAULT_BUILDER = new EventBusBuilder();

    private static final Map<Class<?>, List<Class<?>>> eventTypesCache = new HashMap<>();
    //key为事件类型，value为订阅关系列表。用于查询订阅了该事件的所有订阅关系列表，下称事件类型集合
    private final Map<Class<?>, CopyOnWriteArrayList<Subscription>> subscriptionsByEventType;
    private final Map<Object, SubscriberWrapper> typesBySubscriber;
    private final Map<Class<?>, InnerEventWrapper> stickyEvents;
    private final ThreadLocal<PostingThreadState> currentPostingThreadState = new ThreadLocal<PostingThreadState>() {
        @Override
        protected PostingThreadState initialValue() {
            return new PostingThreadState();
        }
    };

    // @Nullable
    private final MainThreadSupport mainThreadSupport;
    // @Nullable
    private final Poster mainThreadPoster;
    private final BackgroundPoster backgroundPoster;
    private final AsyncPoster asyncPoster;
    private final SubscriberMethodFinder subscriberMethodFinder;
    private final ExecutorService executorService;

    private final boolean throwSubscriberException;
    private final boolean logSubscriberExceptions;
    private final boolean logNoSubscriberMessages;
    private final boolean sendSubscriberExceptionEvent;
    private final boolean sendNoSubscriberEvent;
    private final boolean eventInheritance;

    private final int indexCount;
    private final Logger logger;

    /**
     * Convenience singleton for apps using a process-wide EventBus instance.
     */
    public static EventBus getDefault() {
        EventBus instance = defaultInstance;
        if (instance == null) {
            synchronized (EventBus.class) {
                instance = EventBus.defaultInstance;
                if (instance == null) {
                    instance = EventBus.defaultInstance = new EventBus();
                }
            }
        }
        return instance;
    }

    public static EventBusBuilder builder() {
        return new EventBusBuilder();
    }

    /**
     * For unit test primarily.
     */
    public static void clearCaches() {
        SubscriberMethodFinder.clearCaches();
        eventTypesCache.clear();
    }

    /**
     * Creates a new EventBus instance; each instance is a separate scope in which events are delivered. To use a
     * central bus, consider {@link #getDefault()}.
     */
    public EventBus() {
        this(DEFAULT_BUILDER);
    }

    EventBus(EventBusBuilder builder) {
        logger = builder.getLogger();
        subscriptionsByEventType = new HashMap<>();
        typesBySubscriber = new HashMap<>();
        stickyEvents = new ConcurrentHashMap<>();
        mainThreadSupport = builder.getMainThreadSupport();
        mainThreadPoster = mainThreadSupport != null ? mainThreadSupport.createPoster(this) : null;
        backgroundPoster = new BackgroundPoster(this);
        asyncPoster = new AsyncPoster(this);
        indexCount = builder.subscriberInfoIndexes != null ? builder.subscriberInfoIndexes.size() : 0;
        subscriberMethodFinder = new SubscriberMethodFinder(builder.subscriberInfoIndexes,
                builder.strictMethodVerification, builder.ignoreGeneratedIndex);
        logSubscriberExceptions = builder.logSubscriberExceptions;
        logNoSubscriberMessages = builder.logNoSubscriberMessages;
        sendSubscriberExceptionEvent = builder.sendSubscriberExceptionEvent;
        sendNoSubscriberEvent = builder.sendNoSubscriberEvent;
        throwSubscriberException = builder.throwSubscriberException;
        eventInheritance = builder.eventInheritance;
        executorService = builder.executorService;
    }

    /**
     * Registers the given subscriber to receive events. Subscribers must call {@link #unregister(Object)} once they
     * are no longer interested in receiving events.
     * <p/>
     * Subscribers have event handling methods that must be annotated by {@link Subscribe}.
     * The {@link Subscribe} annotation also allows configuration like {@link
     * ThreadMode} and priority.
     */
    public void register(Object subscriber) {
        register(subscriber, null);
    }

    public void register(Object subscriber, Lifecycle lifecycle) {
        Class<?> subscriberClass = subscriber.getClass();
        List<SubscriberMethod> subscriberMethods = subscriberMethodFinder.findSubscriberMethods(subscriberClass);
        synchronized (this) {
            for (SubscriberMethod subscriberMethod : subscriberMethods) {
                subscribe(subscriber, subscriberMethod, lifecycle);
            }
        }
    }

    // Must be called in synchronized block
    private void subscribe(Object subscriber, SubscriberMethod subscriberMethod, Lifecycle lifecycle) {
        Class<?> eventType = subscriberMethod.eventType;
        Subscription newSubscription = new Subscription(subscriber, subscriberMethod);
        CopyOnWriteArrayList<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        if (subscriptions == null) {
            subscriptions = new CopyOnWriteArrayList<>();
            subscriptionsByEventType.put(eventType, subscriptions);
        } else {
            if (subscriptions.contains(newSubscription)) {
                throw new EventBusException("Subscriber " + subscriber.getClass() + " already registered to event " + eventType);
            }
        }

        int size = subscriptions.size();
        for (int i = 0; i <= size; i++) {
            if (i == size || subscriberMethod.priority > subscriptions.get(i).subscriberMethod.priority) {
                subscriptions.add(i, newSubscription);
                break;
            }
        }
        SubscriberWrapper subscriberWrapper = typesBySubscriber.get(subscriber);
        if (subscriberWrapper == null) {
            final List<Class<?>> subscribedEvents = new ArrayList<>();
            subscriberWrapper = new SubscriberWrapper(this, subscriber, subscribedEvents, lifecycle);
            typesBySubscriber.put(subscriber, subscriberWrapper);
        }
        subscriberWrapper.getEventTypes().add(eventType);
        if (subscriberMethod.sticky) {
            if (eventInheritance) {
                // Existing sticky events of all subclasses of eventType have to be considered.
                // Note: Iterating over all events may be inefficient with lots of sticky events,
                // thus data structure should be changed to allow a more efficient lookup
                // (e.g. an additional map storing sub classes of super classes: Class -> List<Class>).
                final Set<Map.Entry<Class<?>, InnerEventWrapper>> entries = stickyEvents.entrySet();
                for (Map.Entry<Class<?>, InnerEventWrapper> entry : entries) {
                    final Class<?> candidateEventType = entry.getKey();
                    if (eventType.isAssignableFrom(candidateEventType)) {
                        final InnerEventWrapper eventWrapper = entry.getValue();
                        final Object originalStickyEvent = eventWrapper.event;
                        checkPostStickyEventToSubscription(newSubscription, eventWrapper, originalStickyEvent);
                    }
                }
            } else {
                final InnerEventWrapper eventWrapper = stickyEvents.get(eventType);
                final Object originalStickyEvent = eventWrapper.event;
                checkPostStickyEventToSubscription(newSubscription, eventWrapper, originalStickyEvent);
            }
        }
    }

    private void checkPostStickyEventToSubscription(Subscription newSubscription, InnerEventWrapper eventWrapper, Object stickyEvent) {
        if (eventWrapper != null && stickyEvent != null) {
            // If the subscriber is trying to abort the event, it will fail (event is not tracked in posting state)
            // --> Strange corner case, which we don't take care of here.
            postToSubscription(newSubscription, eventWrapper, isMainThread());
        }
    }

    /**
     * Checks if the current thread is running in the main thread.
     * If there is no main thread support (e.g. non-Android), "true" is always returned. In that case MAIN thread
     * subscribers are always called in posting thread, and BACKGROUND subscribers are always called from a background
     * poster.
     */
    private boolean isMainThread() {
        return mainThreadSupport != null ? mainThreadSupport.isMainThread() : true;
    }

    public synchronized boolean isRegistered(Object subscriber) {
        return typesBySubscriber.containsKey(subscriber);
    }

    /**
     * Only updates subscriptionsByEventType, not typesBySubscriber! Caller must update typesBySubscriber.
     */
    private void unsubscribeByEventType(Object subscriber, Class<?> eventType) {
        List<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        if (subscriptions != null) {
            int size = subscriptions.size();
            for (int i = 0; i < size; i++) {
                Subscription subscription = subscriptions.get(i);
                if (subscription.subscriber == subscriber) {
                    subscription.active = false;
                    subscriptions.remove(i);
                    i--;
                    size--;
                }
            }
        }
    }

    /**
     * Unregisters the given subscriber from all event classes.
     */
    public synchronized void unregister(Object subscriber) {
        final SubscriberWrapper subscriberWrapper = typesBySubscriber.get(subscriber);
        if (subscriberWrapper != null) {
            List<Class<?>> subscribedTypes = subscriberWrapper.getEventTypes();
            if (subscribedTypes != null) {
                for (Class<?> eventType : subscribedTypes) {
                    unsubscribeByEventType(subscriber, eventType);
                }
                typesBySubscriber.remove(subscriber);
                subscriberWrapper.unRegister(null);
            } else {
                logger.log(Level.WARNING, "Subscriber to unregister was not registered before: " + subscriber.getClass());
            }
        }
    }

    /**
     * Posts the given event to the event bus.
     */
    public void post(Object event) {
        post("", event);
    }

    public void post(String channel, Object event) {
        post(InnerEventWrapper.create(channel, event));
    }

    private void post(InnerEventWrapper eventWrapper) {
        final PostingThreadState postingState = currentPostingThreadState.get();
        final List<InnerEventWrapper> eventQueue = postingState.eventQueue;
        eventQueue.add(eventWrapper);

        if (!postingState.isPosting) {
            postingState.isMainThread = isMainThread();
            postingState.isPosting = true;
            if (postingState.canceled) {
                throw new EventBusException("Internal error. Abort state was not reset");
            }
            try {
                while (!eventQueue.isEmpty()) {
                    postSingleEvent(eventQueue.remove(0), postingState);
                }
            } finally {
                postingState.isPosting = false;
                postingState.isMainThread = false;
            }
        }
    }


    /**
     * Called from a subscriber's event handling method, further event delivery will be canceled. Subsequent
     * subscribers
     * won't receive the event. Events are usually canceled by higher priority subscribers (see
     * {@link Subscribe#priority()}). Canceling is restricted to event handling methods running in posting thread
     * {@link ThreadMode#POSTING}.
     */
    public void cancelEventDelivery(Object event) {
        PostingThreadState postingState = currentPostingThreadState.get();
        if (!postingState.isPosting) {
            throw new EventBusException(
                    "This method may only be called from inside event handling methods on the posting thread");
        } else if (event == null) {
            throw new EventBusException("Event may not be null");
        } else if (postingState.eventWrapper == null) {
            throw new EventBusException("Only the currently handled event may be send");
        } else if (postingState.eventWrapper.event != event) {
            throw new EventBusException("Only the currently handled event may be aborted");
        } else if (postingState.subscription.subscriberMethod.threadMode != ThreadMode.POSTING) {
            throw new EventBusException(" event handlers may only abort the incoming event");
        }

        postingState.canceled = true;
    }

    /**
     * Posts the given event to the event bus and holds on to the event (because it is sticky). The most recent sticky
     * event of an event's type is kept in memory for future access by subscribers using {@link Subscribe#sticky()}.
     */
    public void postSticky(Object event) {
        postSticky("", event);
    }

    public void postSticky(String channel, Object event) {
        final InnerEventWrapper eventWrapper = InnerEventWrapper.create(channel, event);
        synchronized (stickyEvents) {
            stickyEvents.put(event.getClass(), eventWrapper);
        }
        // Should be posted after it is putted, in case the subscriber wants to remove immediately
        post(eventWrapper);
    }

    /**
     * Gets the most recent sticky event for the given type.
     *
     * @see #postSticky(Object)
     */
    public <T> T getStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            final InnerEventWrapper innerEventWrapper = stickyEvents.get(eventType);
            if (innerEventWrapper != null && innerEventWrapper.event != null) {
                final Object originalEvent = innerEventWrapper.event;
                return eventType.cast(originalEvent);
            }
            return null;
        }
    }

    /**
     * Remove and gets the recent sticky event for the given event type.
     *
     * @see #postSticky(Object)
     */
    public <T> T removeStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            final InnerEventWrapper innerEventWrapper = stickyEvents.remove(eventType);
            if (innerEventWrapper != null && innerEventWrapper.event != null) {
                return eventType.cast(innerEventWrapper.event);
            }
            return null;
        }
    }

    /**
     * Removes the sticky event if it equals to the given event.
     *
     * @return true if the events matched and the sticky event was removed.
     */
    public boolean removeStickyEvent(Object event) {
        synchronized (stickyEvents) {
            final Class<?> eventType = event.getClass();
            final InnerEventWrapper innerEventWrapper = stickyEvents.get(eventType);
            if (innerEventWrapper != null && innerEventWrapper.event != null) {
                final Object existingEvent = innerEventWrapper.event;
                if (event.equals(existingEvent)) {
                    stickyEvents.remove(eventType);
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Removes all sticky events.
     */
    public void removeAllStickyEvents() {
        synchronized (stickyEvents) {
            stickyEvents.clear();
        }
    }

    public boolean hasSubscriberForEvent(Class<?> eventClass) {
        List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
        if (eventTypes != null) {
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                CopyOnWriteArrayList<Subscription> subscriptions;
                synchronized (this) {
                    subscriptions = subscriptionsByEventType.get(clazz);
                }
                if (subscriptions != null && !subscriptions.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void postSingleEvent(InnerEventWrapper eventWrapper, PostingThreadState postingState) throws Error {
        final Object originEvent = eventWrapper.event;
        final Class<?> eventClass = originEvent.getClass();
        boolean subscriptionFound = false;
        if (eventInheritance) {
            final List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
            final int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                final Class<?> clazz = eventTypes.get(h);
                subscriptionFound |= postSingleEventForEventType(eventWrapper, postingState, clazz);
            }
        } else {
            subscriptionFound = postSingleEventForEventType(eventWrapper, postingState, eventClass);
        }
        if (!subscriptionFound) {
            if (logNoSubscriberMessages) {
                logger.log(Level.FINE, "No subscribers registered for event " + eventClass);
            }
            if (sendNoSubscriberEvent && eventClass != NoSubscriberEvent.class &&
                    eventClass != SubscriberExceptionEvent.class) {
                post(new NoSubscriberEvent(this, originEvent));
            }
        }
    }

    private boolean postSingleEventForEventType(InnerEventWrapper eventWrapper, PostingThreadState postingState, Class<?> eventClass) {
        CopyOnWriteArrayList<Subscription> subscriptions;
        synchronized (this) {
            subscriptions = subscriptionsByEventType.get(eventClass);
        }
        if (subscriptions != null && !subscriptions.isEmpty()) {
            for (Subscription subscription : subscriptions) {
                postingState.eventWrapper = eventWrapper;
                postingState.subscription = subscription;
                boolean aborted = false;
                try {
                    postToSubscription(subscription, eventWrapper, postingState.isMainThread);
                    aborted = postingState.canceled;
                } finally {
                    postingState.eventWrapper = null;
                    postingState.subscription = null;
                    postingState.canceled = false;
                }
                if (aborted) {
                    break;
                }
            }
            return true;
        }
        return false;
    }

    private void postToSubscription(Subscription subscription, InnerEventWrapper eventWrapper, boolean isMainThread) {
        switch (subscription.subscriberMethod.threadMode) {
            case POSTING:
                invokeSubscriber(subscription, eventWrapper);
                break;
            case MAIN:
                if (isMainThread) {
                    invokeSubscriber(subscription, eventWrapper);
                } else {
                    mainThreadPoster.enqueue(subscription, eventWrapper);
                }
                break;
            case MAIN_ORDERED:
                if (mainThreadPoster != null) {
                    mainThreadPoster.enqueue(subscription, eventWrapper);
                } else {
                    // temporary: technically not correct as poster not decoupled from subscriber
                    invokeSubscriber(subscription, eventWrapper);
                }
                break;
            case BACKGROUND:
                if (isMainThread) {
                    backgroundPoster.enqueue(subscription, eventWrapper);
                } else {
                    invokeSubscriber(subscription, eventWrapper);
                }
                break;
            case ASYNC:
                asyncPoster.enqueue(subscription, eventWrapper);
                break;
            default:
                throw new IllegalStateException("Unknown thread mode: " + subscription.subscriberMethod.threadMode);
        }
    }

    /**
     * Looks up all Class objects including super classes and interfaces. Should also work for interfaces.
     */
    private static List<Class<?>> lookupAllEventTypes(Class<?> eventClass) {
        synchronized (eventTypesCache) {
            List<Class<?>> eventTypes = eventTypesCache.get(eventClass);
            if (eventTypes == null) {
                eventTypes = new ArrayList<>();
                Class<?> clazz = eventClass;
                while (clazz != null) {
                    eventTypes.add(clazz);
                    addInterfaces(eventTypes, clazz.getInterfaces());
                    clazz = clazz.getSuperclass();
                }
                eventTypesCache.put(eventClass, eventTypes);
            }
            return eventTypes;
        }
    }

    /**
     * Recurses through super interfaces.
     */
    static void addInterfaces(List<Class<?>> eventTypes, Class<?>[] interfaces) {
        for (Class<?> interfaceClass : interfaces) {
            if (!eventTypes.contains(interfaceClass)) {
                eventTypes.add(interfaceClass);
                addInterfaces(eventTypes, interfaceClass.getInterfaces());
            }
        }
    }

    /**
     * Invokes the subscriber if the subscriptions is still active. Skipping subscriptions prevents race conditions
     * between {@link #unregister(Object)} and event delivery. Otherwise the event might be delivered after the
     * subscriber unregistered. This is particularly important for main thread delivery and registrations bound to the
     * live cycle of an Activity or Fragment.
     */
    void invokeSubscriber(PendingPost pendingPost) {
        final InnerEventWrapper event = pendingPost.event;
        final Subscription subscription = pendingPost.subscription;
        PendingPost.releasePendingPost(pendingPost);
        if (subscription.active) {
            invokeSubscriber(subscription, event);
        }
    }

    void invokeSubscriber(Subscription subscription, InnerEventWrapper eventWrapper) {
        try {
            final Object originalEvent = eventWrapper.event;
            final String channel = eventWrapper.channel;
            final boolean hasChannel = isMethodHasChannel(channel);
            //            if (hasChannel) {
            //                if (subscription.subscriberMethod.channel.equalsIgnoreCase(channel)) {
            //                    subscription.subscriberMethod.method.invoke(subscription.subscriber, originalEvent);
            //                }
            //            } else {
            //                subscription.subscriberMethod.method.invoke(subscription.subscriber, originalEvent);
            //            }
            if (TextUtils.equals(subscription.subscriberMethod.channel, channel)) {
                subscription.subscriberMethod.method.invoke(subscription.subscriber, originalEvent);
            }
        } catch (InvocationTargetException e) {
            handleSubscriberException(subscription, eventWrapper.event, e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException("Unexpected exception", e);
        }
    }

    private boolean isMethodHasChannel(String channel) {
        return !TextUtils.isEmpty(channel);
    }

    private void handleSubscriberException(Subscription subscription, Object event, Throwable cause) {
        if (event instanceof SubscriberExceptionEvent) {
            if (logSubscriberExceptions) {
                // Don't send another SubscriberExceptionEvent to avoid infinite event recursion, just log
                logger.log(Level.SEVERE, "SubscriberExceptionEvent subscriber " + subscription.subscriber.getClass()
                        + " threw an exception", cause);
                SubscriberExceptionEvent exEvent = (SubscriberExceptionEvent) event;
                logger.log(Level.SEVERE, "Initial event " + exEvent.causingEvent + " caused exception in "
                        + exEvent.causingSubscriber, exEvent.throwable);
            }
        } else {
            if (throwSubscriberException) {
                throw new EventBusException("Invoking subscriber failed", cause);
            }
            if (logSubscriberExceptions) {
                logger.log(Level.SEVERE, "Could not dispatch event: " + event.getClass() + " to subscribing class "
                        + subscription.subscriber.getClass(), cause);
            }
            if (sendSubscriberExceptionEvent) {
                SubscriberExceptionEvent exEvent = new SubscriberExceptionEvent(this, cause, event,
                        subscription.subscriber);
                post(exEvent);
            }
        }
    }

    /**
     * For ThreadLocal, much faster to set (and get multiple values).
     */
    final static class PostingThreadState {
        final List<InnerEventWrapper> eventQueue = new ArrayList<>();
        boolean isPosting;
        boolean isMainThread;
        Subscription subscription;
        InnerEventWrapper eventWrapper;
        boolean canceled;
    }

    ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * For internal use only.
     */
    public Logger getLogger() {
        return logger;
    }

    // Just an idea: we could provide a callback to post() to be notified, an alternative would be events, of course...
    /* public */interface PostCallback {
        void onPostCompleted(List<SubscriberExceptionEvent> exceptionEvents);
    }

    @Override
    public String toString() {
        return "EventBus[indexCount=" + indexCount + ", eventInheritance=" + eventInheritance + "]";
    }


    // 生命周期包裹类
    final static class SubscriberWrapper implements GenericLifecycleObserver {
        private Object mSubscriber;
        private List<Class<?>> mEventTypes;
        private WeakReference<Lifecycle> outerLifecycle;
        private WeakReference<EventBus> outerContext;

        SubscriberWrapper(EventBus eventBus, Object subscriber, List<Class<?>> eventTypes, Lifecycle lifecycle) {
            outerContext = new WeakReference<>(eventBus);
            setSubscriber(subscriber).setEventTypes(eventTypes);
            setLifecycle(lifecycle);
        }

        final SubscriberWrapper setSubscriber(Object subscriber) {
            this.mSubscriber = subscriber;
            return this;
        }

        final SubscriberWrapper setEventTypes(List<Class<?>> eventTypes) {
            this.mEventTypes = eventTypes;
            return this;
        }

        final SubscriberWrapper setLifecycle(Lifecycle lifecycle) {
            if (lifecycle != null) {
                lifecycle.addObserver(this);
                outerLifecycle = new WeakReference<>(lifecycle);
            }
            return this;
        }

        List<Class<?>> getEventTypes() {
            return mEventTypes;
        }

        @Override
        public void onStateChanged(LifecycleOwner source, Lifecycle.Event event) {
            final Lifecycle lifecycle = source.getLifecycle();
            if (lifecycle.getCurrentState() == Lifecycle.State.DESTROYED) {
                if (mSubscriber != null) {
                    Log.d("ygd", "ygd  unregister obj : " + mSubscriber + "   class type : " + mSubscriber.getClass());
                }
                innerRelease(lifecycle);
            }
        }

        private void innerRelease(Lifecycle lifecycle) {
            if (outerContext != null && outerContext.get() != null) {
                outerContext.get().unregister(mSubscriber);
            }
            unRegister(lifecycle);
        }

        final void unRegister(Lifecycle lifecycle) {
            if (lifecycle == null) {
                if (outerLifecycle != null && outerLifecycle.get() != null) {
                    lifecycle = outerLifecycle.get();
                }
            }
            if (lifecycle != null) {
                lifecycle.removeObserver(this);
            }
            mSubscriber = null;
            if (mEventTypes != null) {
                mEventTypes.clear();
            }
            mEventTypes = null;
            outerLifecycle = null;
            outerContext = null;
        }
    }
}
