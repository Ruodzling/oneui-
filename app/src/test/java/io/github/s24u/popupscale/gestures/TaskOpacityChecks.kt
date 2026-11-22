package io.github.s24u.popupscale.gestures

object TaskOpacityChecks {
    fun run(){
        check(kotlin.math.abs(TaskOpacityPolicy.compose(1f,80)-0.2f)<0.0001f)
        check(kotlin.math.abs(TaskOpacityPolicy.compose(0.5f,40)-0.3f)<0.0001f)
        check(TaskOpacityPolicy.compose(0f,80)==0f) // Native hidden/closing must stay hidden.
        check(TaskOpacityPolicy.compose(0.5f,0)==0.5f)
        check(TaskOpacityPolicy.compose(0.5f,81)==0.5f)
        check(TaskOpacityPolicy.compose(Float.NaN,40).isNaN())
        check(TaskOpacityPolicy.MIN_OPACITY==20&&TaskOpacityPolicy.MAX_OPACITY==100)
        check(TaskOpacityPolicy.opacityPercent(0)==100&&TaskOpacityPolicy.opacityPercent(80)==20)
        check(TaskOpacityPolicy.opacityPercent(-5)==100&&TaskOpacityPolicy.opacityPercent(95)==20)
        check(TaskOpacityPolicy.transparencyPercent(100)==0&&TaskOpacityPolicy.transparencyPercent(20)==80)
        check(TaskOpacityPolicy.transparencyPercent(120)==0&&TaskOpacityPolicy.transparencyPercent(5)==80)
        check(TaskOpacityPolicy.lifetime(true,false,false)==TaskOpacityPolicy.Lifetime.SUSPEND)
        check(TaskOpacityPolicy.lifetime(true,true,true)==TaskOpacityPolicy.Lifetime.SUSPEND)
        check(TaskOpacityPolicy.lifetime(true,true,false)==TaskOpacityPolicy.Lifetime.ACTIVE)
        check(TaskOpacityPolicy.lifetime(false,true,false)==TaskOpacityPolicy.Lifetime.DROP)
        check(TaskOpacityPolicy.canReconcile(1f,1000L,1250L))
        check(!TaskOpacityPolicy.canReconcile(1f,1000L,1249L))
        check(!TaskOpacityPolicy.canReconcile(0f,0L,10000L))
        check(!TaskOpacityPolicy.canReconcile(0.5f,0L,10000L))
        check(!TaskOpacityPolicy.canReconcile(1f,1000L,900L))
        fun eligible(enabled:Boolean=true,observe:Boolean=false,selected:Boolean=true,user:Int=0,display:Int=0,
                     type:Int=1,mode:Int=5,visible:Boolean=true,locked:Boolean=false)=
            TaskOpacityPolicy.eligible(enabled,observe,selected,user,display,type,mode,visible,locked)
        check(eligible())
        check(!eligible(enabled=false)&&!eligible(observe=true)&&!eligible(selected=false))
        check(!eligible(user=444)&&!eligible(display=1)&&!eligible(type=2)&&!eligible(mode=1)&&!eligible(mode=6))
        check(!eligible(visible=false)&&!eligible(locked=true))
        check(TaskOpacityPolicy.isMenuPolicyClass("com.android.wm.shell.windowdecor.policy.FreeformPopupButtonPolicy"))
        check(TaskOpacityPolicy.isMenuPolicyClass("com.android.wm.shell.windowdecor.policy.FreeformScrollPopupButtonPolicy"))
        check(TaskOpacityPolicy.isMenuPolicyClass("com.android.wm.shell.windowdecor.policy.CaptionPopupButtonPolicy"))
        check(!TaskOpacityPolicy.isMenuPolicyClass("com.android.wm.shell.windowdecor.policy.SplitPopupButtonPolicy"))
        check(!TaskOpacityPolicy.isMenuPolicyClass("com.android.wm.shell.windowdecor.policy.DexFreeformPopupButtonPolicy"))
        check(TaskOpacityPolicy.isMenuShowMethod("showMenuPopup"))
        check(TaskOpacityPolicy.isMenuShowMethod("createMenuPopup"))
        check(TaskOpacityPolicy.isMenuShowMethod("showPopupMenu"))
        check(TaskOpacityPolicy.isMenuShowMethod("show"))
        check(!TaskOpacityPolicy.isMenuShowMethod("closeMenuPopupImmediately"))
        check(!TaskOpacityPolicy.isMenuShowMethod("updateMenuPopupPosition"))
        check(!TaskOpacityPolicy.isMenuShowMethod("createAdditionalViewHostViewContainer"))
        println("PASS: task transparency composes once with native alpha; temporary hidden/lock suspends, exclusions drop; idle repair waits for settled opaque native baseline and never overrides fades/hidden alpha.")
    }
}
