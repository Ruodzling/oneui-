package io.github.s24u.popupscale.gestures

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ResultReceiver
import android.os.SystemClock
import java.util.UUID

/** Bounded first-frame handshake; no bitmaps or app content leave SystemUI. */
@android.annotation.TargetApi(34)
class LauncherFrameHandoff {
    private val main=Handler(Looper.getMainLooper())
    private var cancel: (() -> Unit)?=null
    private var active:Pair<Context,String>?=null
    fun cancelPending(){
        val action=cancel;cancel=null;action?.invoke()
        active?.let{abort(it.first,it.second)};active=null
    }
    fun prepare(context:Context,task:Int,rect:Rect,done:(String)->Unit) {
        cancelPending()
        val nonce=UUID.randomUUID().toString()
        var finished=false
        fun finish(prepared:Boolean){
            if(finished){if(prepared)abort(context,nonce);return}
            finished=true;cancel=null
            if(prepared)active=context.applicationContext to nonce
            if(!prepared)abort(context,nonce)
            done(if(prepared)nonce else "")
        }
        cancel={finish(false)}
        val reply=object:ResultReceiver(main){override fun onReceiveResult(code:Int,data:Bundle?){
            if(code!=1)Guard.info("transition frame prepare rejected task=$task")
            finish(code==1)
        }}
        // Marshal the framework ResultReceiver, not this module's anonymous subclass;
        // SystemUI's host class loader must not resolve a Launcher module class.
        val parcel=Parcel.obtain()
        val frameworkReply=try{
            reply.writeToParcel(parcel,0);parcel.setDataPosition(0)
            ResultReceiver.CREATOR.createFromParcel(parcel)
        }finally{parcel.recycle()}
        val sent=Guard.call("launcher-frame-prepare"){
            context.sendBroadcast(Intent(SystemUiHooks.PREPARE_FRAME).setPackage(RomProfile.SYSTEM_UI)
                .putExtra("task",task).putExtra("rect",rect).putExtra("frame",nonce).putExtra("reply",frameworkReply)
                .putExtra("when",SystemClock.uptimeMillis()),null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());true
        }==true
        if(!sent)finish(false)
        else main.postDelayed({if(!finished){Guard.info("transition frame prepare timeout");finish(false)}},650)
    }
    fun abort(context:Context,nonce:String) {
        if(nonce.isEmpty())return
        if(active?.second==nonce)active=null
        Guard.call("launcher-frame-cancel"){
            context.sendBroadcast(Intent(SystemUiHooks.CANCEL_FRAME).setPackage(RomProfile.SYSTEM_UI)
                .putExtra("frame",nonce).putExtra("when",SystemClock.uptimeMillis()),null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        }
    }
}
