package io.github.s24u.popupscale;

import java.net.URLClassLoader;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class CommentPanelChecks {
    public static void main(String[] args) throws Exception {
        check(DouyinHeaderGeometry.gap(129, 129, 96, 225, 1094, 1094, 2.1875f) == 129, "home/mall status prefix");
        check(DouyinHeaderGeometry.gap(129, 129, 114, 243, 1094, 1094, 2.1875f) == 129, "profile status prefix");
        check(DouyinHeaderGeometry.gap(0, 0, 96, 96, 1094, 1094, 2.1875f) == 0, "already removed is stable");
        check(DouyinHeaderGeometry.gap(129, 129, 96, 1745, 1094, 1094, 2.1875f) == 0, "never crop page body");
        check(DouyinHeaderGeometry.gap(129, 160, 96, 225, 1094, 1094, 2.1875f) == 0, "unrelated offset stays native");
        check(DouyinHeaderGeometry.gap(129, 129, 96, 225, 200, 1094, 2.1875f) == 0, "narrow cards stay native");
        check(DouyinHeaderGeometry.gap(129, 129, 96, 225, 1094, 1094, Float.NaN) == 0, "invalid scale stays native");
        check(CommerceHeaderGeometry.trim(180, 346, 1361, 1.65f) == 173, "commerce leading blank removed with row padding retained");
        check(346 - CommerceHeaderGeometry.trim(180, 346, 1361, 1.65f) >= Math.round(48 * 1.65f), "keep complete navigation row");
        check(CommerceHeaderGeometry.trim(8, 160, 1361, 1.65f) == 0, "normal header spacing is not removed");
        check(CommerceHeaderGeometry.trim(Integer.MAX_VALUE, 346, 1361, 1.65f) == 0, "no controls must not shift header");
        check(CommerceHeaderGeometry.trim(180, 900, 1361, 1.65f) == 0, "full content is not a header overlay");
        check(CommerceHeaderGeometry.trim(180, 200, 1361, 1.65f) == 0, "reject truncating navigation controls");
        check(CommerceHeaderGeometry.trim(180, 346, 1361, Float.NaN) == 0, "invalid dimensions stay native");
        CommentPanelGeometry panel = new CommentPanelGeometry();
        int first = panel.height(1227, 1745, 2991);
        check(Math.abs(first / 2991f - 1227 / 1745f) < .001f, "first-open height fraction");
        check(panel.height(first, 1745, 2991) == first, "don't rescale applied height");
        int smaller = panel.height(1227, 1425, 2969);
        check(Math.abs(smaller / 2969f - 1227 / 1745f) < .001f, "cached pixels survive window resizing");
        check(panel.height(smaller, 1425, 2969) == smaller, "repeated measure stays stable");
        int refreshed = panel.height(1000, 1425, 2969);
        check(Math.abs(refreshed / 2969f - 1000 / 1425f) < .001f, "new app height request");
        check(panel.height(1425, 1425, 2969) == 2969, "app expands to full panel");
        check(panel.height(-1, 1425, 2969) == -1 && panel.height(-2, 1425, 2969) == -2,
                "match/wrap semantics");
        CommentPanelGeometry virtual = new CommentPanelGeometry();
        check(virtual.height(2100, 1745, 2991) == 2100, "already virtual height");
        check(new CommentPanelGeometry().height(1000, 0, 2991) == 1000, "unmeasured host");
        Properties shared = new Properties();
        CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1), done = new CountDownLatch(8);
        AtomicInteger claims = new AtomicInteger();
        for (int i = 0; i < 8; i++) new Thread(() -> {
            ready.countDown();
            try { start.await(); if (AppHookProcessGuard.claim(shared)) claims.incrementAndGet(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { done.countDown(); }
        }).start();
        ready.await(); start.countDown(); done.await();
        check(claims.get() == 1, "concurrent duplicate hook delivery");
        var classes = AppHookProcessGuard.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader a = new URLClassLoader(new java.net.URL[]{classes}, null);
             URLClassLoader b = new URLClassLoader(new java.net.URL[]{classes}, null)) {
            Properties process = new Properties();
            String name = AppHookProcessGuard.class.getName();
            check((Boolean) a.loadClass(name).getMethod("claim", Properties.class).invoke(null, process), "first loader");
            check(!(Boolean) b.loadClass(name).getMethod("claim", Properties.class).invoke(null, process), "second loader shares process marker");
        }
        check(AppHookProcessGuard.claim(new Properties()), "another process independent");
        System.out.println("PASS: comment first-open/resized fraction, cached/native/virtual pixel requests, full expansion and duplicate hooks across threads/classloaders.");
    }
    private static void check(boolean result, String reason) { if (!result) throw new AssertionError(reason); }
}
