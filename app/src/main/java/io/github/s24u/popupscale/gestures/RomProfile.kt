package io.github.s24u.popupscale.gestures

import android.os.Build

/** Samsung Android 15-17 admission; feature ABIs are selected by RomCapabilities. */
object RomProfile {
    const val SYSTEM_UI = "com.android.systemui"
    const val LAUNCHER = "com.sec.android.app.launcher"
    const val CONSUMER = "com.honeyspace.gesture.inputconsumer.OtherActivityInputConsumer"
    const val ORGANIZER = "com.android.wm.shell.ShellTaskOrganizer"
    const val MOTION_CONTROLLER = "com.android.wm.shell.windowdecor.TaskMotionController"
    const val RESIZE_GUIDE = "com.samsung.android.multiwindow.FreeformResizeGuide"
    const val TASK_POSITIONER = "com.android.wm.shell.windowdecor.MultiDisplayVeiledResizeTaskPositioner"
    val POSITIONERS=listOf(TASK_POSITIONER,
        "com.android.wm.shell.windowdecor.TaskPositioner",
        "com.android.wm.shell.windowdecor.VeiledResizeTaskPositioner",
        "com.android.wm.shell.windowdecor.FluidResizeTaskPositioner")
    val CONSUMERS=listOf(CONSUMER,"com.android.quickstep.inputconsumers.OtherActivityInputConsumer")
    val SERVER_CORES=listOf("com.android.server.wm.MultiTaskingCoreController","com.android.server.wm.MultiTaskingController")
    val DECORATIONS = listOf(
        "com.android.wm.shell.windowdecor.DefaultWindowDecoration",
        "com.android.wm.shell.windowdecor.CaptionWindowDecoration",
        "com.android.wm.shell.windowdecor.DesktopModeWindowDecoration",
        "com.android.wm.shell.windowdecor.MultitaskingWindowDecoration"
    )
    @JvmStatic fun supported() = CompatibilityPolicy.platform(Build.VERSION.SDK_INT,Build.MANUFACTURER,Build.BRAND)
}
