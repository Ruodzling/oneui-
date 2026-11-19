package io.github.s24u.popupscale.gestures

import android.content.res.Configuration
import android.graphics.Rect

/** Edit the entry WCT's existing bounds before WM collects its transition end state.
 * Other configuration fields, flags, surface transactions and hierarchy ops stay native. */
// Constructed in injected system_server after probing this ABI, not in the settings UI.
@android.annotation.SuppressLint("BlockedPrivateApi")
class PopupBoundsChange(type:Class<*>) {
    private val configuration=type.getDeclaredMethod("getConfiguration").apply{isAccessible=true}
    private val configMask=type.getDeclaredField("mConfigSetMask").apply{isAccessible=true}
    private val windowMask=type.getDeclaredField("mWindowSetMask").apply{isAccessible=true}
    private val window=Configuration::class.java.getDeclaredField("windowConfiguration").apply{isAccessible=true}
    private val getBounds=window.type.getDeclaredMethod("getBounds").apply{isAccessible=true}
    private val setBounds=window.type.getDeclaredMethod("setBounds",Rect::class.java).apply{isAccessible=true}
    // Stable AOSP configuration bits; verified against native WCT.setBounds in device checks.
    private val boundsBit=1 // WindowConfiguration.WINDOW_CONFIG_BOUNDS
    private val configurationBit=0x20000000 // ActivityInfo.CONFIG_WINDOW_CONFIGURATION
    fun apply(change:Any,bounds:Rect):Boolean {
        if(bounds.isEmpty)return false
        val target=window.get(configuration.invoke(change))?:return false
        val previous=Rect(getBounds.invoke(target) as Rect)
        val oldConfig=configMask.getInt(change);val oldWindow=windowMask.getInt(change)
        if(previous==bounds&&oldConfig and configurationBit!=0&&oldWindow and boundsBit!=0)return false
        try {
            setBounds.invoke(target,Rect(bounds))
            configMask.setInt(change,oldConfig or configurationBit)
            windowMask.setInt(change,oldWindow or boundsBit)
        }catch(error:Throwable){
            setBounds.invoke(target,previous)
            configMask.setInt(change,oldConfig);windowMask.setInt(change,oldWindow)
            throw error
        }
        return true
    }
}
