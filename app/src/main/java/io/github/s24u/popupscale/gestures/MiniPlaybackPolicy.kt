package io.github.s24u.popupscale.gestures

/** Only an explicitly supported visible mini may opt into Android's multi-resume lifecycle. */
object MiniPlaybackPolicy {
    const val PACKAGE = "com.ss.android.ugc.aweme"
    const val ACTION = "io.github.s24u.popupscale.action.MINI_PLAYBACK_INTENT"
    const val DEFAULT_PACKAGES = "com.ss.android.ugc.aweme\ncom.ss.android.yumme.video"
    private val OBSERVER_UNAVAILABLE = setOf(
        "timeout-check-app-scope-and-restart",
        "app-not-installed",
        "reply-proxy-failed",
        "request-failed"
    )
    fun eligible(name: String, selected: Boolean, protected: Boolean, visible: Boolean,
                 mode: Int, docked: Boolean, locked: Boolean) =
        name == PACKAGE && selected && protected && visible && mode == 5 && !docked && !locked
    fun eligible(name: String, observerSelected: Boolean, keepPlaybackSelected: Boolean,
                 protected: Boolean, visible: Boolean, mode: Int, docked: Boolean, locked: Boolean) =
        ((name == PACKAGE && observerSelected) || keepPlaybackSelected) &&
            protected && visible && mode == 5 && !docked && !locked
    fun needsAppObserver(name: String) = name == PACKAGE
    fun isObserverUnavailableReason(reason: String) = reason in OBSERVER_UNAVAILABLE
    fun allowWithoutObserver(keepPlaybackSelected: Boolean, observerUnavailable: Boolean) =
        keepPlaybackSelected && observerUnavailable
    fun trusted(sender: Int, systemUi: Int, version: Int, current: Int, now: Long, sent: Long) =
        systemUi >= 0 && sender == systemUi && version == current && now - sent in 0..1500
}
