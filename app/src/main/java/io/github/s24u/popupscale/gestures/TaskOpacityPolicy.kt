package io.github.s24u.popupscale.gestures

object TaskOpacityPolicy {
    const val MAX_TRANSPARENCY=80
    // A temporary visibility/keyguard boundary must not erase the user's chosen value.
    enum class Lifetime { DROP, SUSPEND, ACTIVE }
    fun lifetime(admitted:Boolean,visible:Boolean,locked:Boolean)=
        if(!admitted)Lifetime.DROP else if(!visible||locked)Lifetime.SUSPEND else Lifetime.ACTIVE
    fun canReconcile(nativeAlpha:Float,lastNativeWrite:Long,now:Long)=
        nativeAlpha==1f&&now>=lastNativeWrite&&now-lastNativeWrite>=250L
    fun eligible(enabled:Boolean,observe:Boolean,selected:Boolean,user:Int,display:Int,type:Int,mode:Int,visible:Boolean,locked:Boolean)=
        enabled&&!observe&&selected&&io.github.s24u.popupscale.CloneUserPolicy.supported(user)&&
            display==0&&type==1&&mode==5&&visible&&!locked
    fun compose(nativeAlpha:Float,transparency:Int):Float {
        if(!nativeAlpha.isFinite()||nativeAlpha !in 0f..1f||transparency !in 0..MAX_TRANSPARENCY)return nativeAlpha
        return nativeAlpha*(1f-transparency/100f)
    }
    fun isMenuPolicyClass(name:String):Boolean =
        name.endsWith(".FreeformPopupButtonPolicy")||
            name.endsWith(".FreeformScrollPopupButtonPolicy")||
            name.endsWith(".CaptionPopupButtonPolicy")
    fun isMenuShowMethod(name:String):Boolean {
        if(name=="show")return true
        if(name.startsWith("close")||name.startsWith("dismiss")||name.startsWith("relayout")||
            name.startsWith("update")||name.startsWith("check")||name.startsWith("load")||
            name.startsWith("get")||name.startsWith("set")||name.startsWith("is")||
            name.startsWith("createAdditional"))return false
        return name.contains("MenuPopup",ignoreCase=true)||
            name.contains("PopupMenu",ignoreCase=true)||
            name=="createMenu"||name=="openMenu"
    }
}
