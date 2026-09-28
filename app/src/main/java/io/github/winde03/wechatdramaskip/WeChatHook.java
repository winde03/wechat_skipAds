package io.github.winde03.wechatdramaskip;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * WeChat 8.0.78 (3180): the native drama timeline's g() only rejects a swipe
 * when the current feed is a promotion and its unlock countdown is unfinished.
 */
public final class WeChatHook implements IXposedHookLoadPackage {
    private static final String TAG = "WeChatDramaAdSkip: ";
    private static final String DRAMA_ACTIVITY =
            "com.tencent.mm.plugin.finder.drama.timeline.FinderNativeDramaTimelineUI";
    private static final AtomicBoolean LIFECYCLE_HOOKED = new AtomicBoolean(false);
    private static final Set<Class<?>> HOOKED_PRESENTERS =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final AtomicBoolean BYPASS_LOGGED = new AtomicBoolean(false);
    private static final AtomicInteger FOCUS_LOGGED = new AtomicInteger();
    private static final AtomicInteger PRESENTER_LOGGED = new AtomicInteger();
    private static final AtomicInteger REQUEST_LOGGED = new AtomicInteger();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"com.tencent.mm".equals(param.packageName)) {
            return;
        }

        if (LIFECYCLE_HOOKED.compareAndSet(false, true)) {
            try {
                Method createActivity = Instrumentation.class.getDeclaredMethod(
                        "callActivityOnCreate", Activity.class, Bundle.class);
                XposedBridge.hookMethod(createActivity, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam hook) {
                        Activity activity = (Activity) hook.args[0];
                        if (!DRAMA_ACTIVITY.equals(activity.getClass().getName())) {
                            return;
                        }
                        ClassLoader runtimeLoader = activity.getClass().getClassLoader();
                        XposedBridge.log(TAG + "短剧页面创建，运行时类加载器="
                                + loaderId(runtimeLoader));
                        installHooks(runtimeLoader, "页面运行时");
                    }
                });
                XposedBridge.log(TAG + "已监控短剧页面创建");
            } catch (Throwable error) {
                XposedBridge.log(TAG + "监控短剧页面创建失败");
                XposedBridge.log(error);
            }
        }
        installHooks(param.classLoader, "进程初始化");
    }

    private static synchronized void installHooks(ClassLoader loader, String source) {
        Class<?> presenter = null;
        try {
            presenter = XposedHelpers.findClass("lh2.w", loader);
            if (HOOKED_PRESENTERS.contains(presenter)) {
                XposedBridge.log(TAG + source + "与已安装 Hook 使用同一类加载器="
                        + loaderId(presenter.getClassLoader()));
                return;
            }
            Class<?> feed = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.finder.model.BaseFinderFeed", loader);
            Class<?> item = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.finder.storage.FinderItem", loader);
            Class<?> holder = XposedHelpers.findClass("vv5.s0", loader);
            Class<?> dataMerger = XposedHelpers.findClass("lh2.g0", loader);

            // These checks prevent a similarly named, changed class in another
            // WeChat release from receiving a broad swipe override.
            if (!"com.tencent.mm.plugin.finder.feed.jk".equals(
                    presenter.getSuperclass().getName())) {
                throw new NoSuchMethodException("native drama presenter signature changed");
            }
            Method allowSwipe = presenter.getDeclaredMethod("g");
            Method promotionCheck = item.getDeclaredMethod("isNativeDramaPromotionFeed");
            Method feedItem = feed.getDeclaredMethod("z");
            Method countdownDone = feed.getDeclaredMethod("R");
            Method gateToast = presenter.getDeclaredMethod("G0", feed);
            Method promotionFeed = presenter.getDeclaredMethod("r0", feed);
            Method focusFeed = presenter.getDeclaredMethod("f2", holder, feed);
            Method requestPromotion = presenter.getDeclaredMethod("C0", feed);
            Method mergeDramaFeeds = dataMerger.getDeclaredMethod("mergeDataAndNotify",
                    int.class, List.class, boolean.class, Object.class);
            Field countdownField = feed.getDeclaredField("nativeDramaUnlockCountDownFinish");
            if (allowSwipe.getReturnType() != boolean.class
                    || promotionCheck.getReturnType() != boolean.class
                    || feedItem.getReturnType() != item
                    || countdownDone.getReturnType() != boolean.class
                    || gateToast.getReturnType() != void.class
                    || promotionFeed.getReturnType() != boolean.class
                    || focusFeed.getReturnType() != void.class
                    || requestPromotion.getReturnType() != void.class
                    || !"com.tencent.mm.plugin.finder.feed.model.internal.h".equals(
                            dataMerger.getSuperclass().getName())
                    || !"com.tencent.mm.plugin.finder.feed.model.internal.u0".equals(
                            mergeDramaFeeds.getReturnType().getName())
                    || countdownField.getType() != boolean.class) {
                throw new NoSuchMethodException("native drama gate signature changed");
            }
            feedItem.setAccessible(true);
            promotionCheck.setAccessible(true);
            countdownField.setAccessible(true);
            HOOKED_PRESENTERS.add(presenter);

            // C0 only fetches an ad for the current drama episode; its result
            // callback inserts the promotion directly into the timeline.
            XposedBridge.hookMethod(requestPromotion, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam hook) {
                    if (REQUEST_LOGGED.getAndIncrement() < 24) {
                        XposedBridge.log(TAG + "已阻止短剧推广内容请求");
                    }
                    hook.setResult(null);
                }
            });

            // Also remove promotion feeds supplied by the timeline loader,
            // without changing ordinary drama episodes or other Finder pages.
            XposedBridge.hookMethod(mergeDramaFeeds, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam hook) {
                    if (!(hook.args[1] instanceof List<?> incoming) || incoming.isEmpty()) {
                        return;
                    }
                    ArrayList<Object> filtered = new ArrayList<>(incoming.size());
                    int removed = 0;
                    for (Object entry : incoming) {
                        try {
                            if (feed.isInstance(entry)) {
                                Object finderItem = feedItem.invoke(entry);
                                if (finderItem != null
                                        && Boolean.TRUE.equals(promotionCheck.invoke(finderItem))) {
                                    removed++;
                                    continue;
                                }
                            }
                        } catch (ReflectiveOperationException | RuntimeException error) {
                            XposedBridge.log(TAG + "检查短剧列表推广内容失败");
                            XposedBridge.log(error);
                        }
                        filtered.add(entry);
                    }
                    if (removed > 0) {
                        hook.args[1] = filtered;
                        XposedBridge.log(TAG + "已从短剧列表移除 " + removed + " 条推广内容");
                    }
                }
            });

            XposedBridge.hookAllConstructors(presenter, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam hook) {
                    if (PRESENTER_LOGGED.getAndIncrement() < 4) {
                        XposedBridge.log(TAG + "短剧 Presenter 已创建，类加载器="
                                + loaderId(hook.thisObject.getClass().getClassLoader()));
                    }
                }
            });

            // The timeline reads this field while binding a promotion feed.
            // Changing it before the focus callback avoids the separate
            // RecyclerView lock that is installed before a swipe is checked.
            XposedBridge.hookMethod(focusFeed, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam hook) {
                    Object currentFeed = hook.args[1];
                    try {
                        Object finderItem = feedItem.invoke(currentFeed);
                        boolean promotion = finderItem != null
                                && Boolean.TRUE.equals(promotionCheck.invoke(finderItem));
                        if (FOCUS_LOGGED.getAndIncrement() < 24) {
                            XposedBridge.log(TAG + "短剧焦点内容：推广=" + promotion);
                        }
                        if (promotion && !countdownField.getBoolean(currentFeed)) {
                            countdownField.setBoolean(currentFeed, true);
                            logFirstBypass();
                        }
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        XposedBridge.log(TAG + "设置推广内容倒计时状态失败");
                        XposedBridge.log(error);
                    }
                }
            });

            XposedBridge.hookMethod(allowSwipe, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam hook) {
                    if (!hook.hasThrowable() && Boolean.FALSE.equals(hook.getResult())) {
                        hook.setResult(true);
                        logFirstBypass();
                    }
                }
            });

            // The getter is also consulted by other timeline paths. Only a
            // native drama promotion feed gets the completed state.
            XposedBridge.hookMethod(countdownDone, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam hook) {
                    if (hook.hasThrowable() || !Boolean.FALSE.equals(hook.getResult())) {
                        return;
                    }
                    try {
                        Object finderItem = feedItem.invoke(hook.thisObject);
                        if (finderItem != null
                                && Boolean.TRUE.equals(promotionCheck.invoke(finderItem))) {
                            hook.setResult(true);
                            logFirstBypass();
                        }
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // Preserve WeChat's original result if the feed changed.
                    }
                }
            });

            XposedBridge.log(TAG + source + "已安装微信 8.0.78 短剧去推广 Hook，类加载器="
                    + loaderId(presenter.getClassLoader()));
        } catch (Throwable error) {
            if (presenter != null) {
                HOOKED_PRESENTERS.remove(presenter);
            }
            XposedBridge.log(TAG + "未安装 Hook：当前微信版本的类或方法不匹配");
            XposedBridge.log(error);
        }
    }

    private static String loaderId(ClassLoader loader) {
        return loader == null ? "boot" : Integer.toHexString(System.identityHashCode(loader));
    }

    private static void logFirstBypass() {
        if (BYPASS_LOGGED.compareAndSet(false, true)) {
            XposedBridge.log(TAG + "已放行短剧推广广告的倒计时上滑限制");
        }
    }
}
