package io.github.s24u.popupscale.gestures

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.util.UUID

/** SystemUI owns one session; system_server must acknowledge before any window is changed. */
@android.annotation.TargetApi(34)
class MiniTaskLease(private val context:Context,private val onAck:(Int)->Unit={},
                    private val selected:(Int,Boolean)->Unit={_,_->}) {
    private val main=Handler(Looper.getMainLooper())
    private var session=""
    private var task=-1
    private var taskUser=-1
    private var preparingFullscreen=false
    private var acknowledged=0L
    private var waiting:((Boolean)->Unit)?=null
    var nativeLayer:Int?=null;private set
    var framelessCaption:Int=0;private set
    init {
        context.registerReceiver(object:BroadcastReceiver(){
            override fun onReceive(c:Context,intent:Intent){
                if(sentFromUid!=Process.SYSTEM_UID||session.isEmpty()||
                    intent.getStringExtra("session")!=session||intent.getIntExtra("task",-1)!=task)return
                val whenSent=intent.getLongExtra("when",-10000)
                if(SystemClock.uptimeMillis()-whenSent !in 0..3000)return
                if(intent.action==SELECTED){
                    Guard.call("mini-task-selected"){
                        // The source is trusted only after the UID/session/task/age checks above.
                        // Missing source from an older server keeps the Recents timing.
                        selected(task,intent.getStringExtra("source")=="Launcher")
                    }
                    return
                }
                acknowledged=SystemClock.uptimeMillis()
                if(intent.getIntExtra("mode",-1)==5)preparingFullscreen=false
                if(nativeLayer==null)nativeLayer=intent.getIntExtra("layer",-1).takeIf{it in 0..1000000}
                val caption=intent.getIntExtra("framelessCaption",0).coerceAtLeast(0)
                val captionChanged=caption!=framelessCaption
                framelessCaption=caption
                val done=waiting;waiting=null
                if(done!=null)Guard.info("live task protection acknowledged task=$task")
                done?.invoke(true)
                if(captionChanged)Guard.call("mini-caption-ack"){onAck(task)}
            }
        },IntentFilter(ACK).apply{addAction(SELECTED)},PERMISSION,main,Context.RECEIVER_EXPORTED)
    }
    fun acquire(id:Int,user:Int,preparingFullscreen:Boolean=false,done:(Boolean)->Unit) {
        release()
        if(!io.github.s24u.popupscale.CloneUserPolicy.supported(user)){done(false);return}
        session=UUID.randomUUID().toString();task=id;taskUser=user;waiting=done
        this.preparingFullscreen=preparingFullscreen
        val marker=session
        send()
        main.postDelayed({if(session==marker&&waiting!=null){
            val callback=waiting;waiting=null;release();callback?.invoke(false)
        }},1500)
    }
    fun refresh():Boolean {
        if(task<0||SystemClock.uptimeMillis()-acknowledged>MiniLeasePolicy.TTL)return false
        send();return true
    }
    fun release(keepVisualTask:Int=-1) {
        if(task>=0)send(-1,keepVisualTask=keepVisualTask)
        task=-1;taskUser=-1;preparingFullscreen=false;session="";acknowledged=0;waiting=null;nativeLayer=null;framelessCaption=0
    }
    fun releaseVisual(taskId:Int,resyncLayers:Boolean=false) {
        if(taskId<0)return
        Guard.call("mini-lease-visual-end"){
            context.sendBroadcast(Intent(ACTION).setPackage("android")
                .putExtra("task",-1).putExtra("endVisualTask",taskId)
                .putExtra("resyncLayers",resyncLayers)
                .putExtra("session",java.util.UUID.randomUUID().toString())
                .putExtra("when",SystemClock.uptimeMillis()),
                PERMISSION,BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        }
    }
    private fun send(id:Int=task,keepVisualTask:Int=-1) {
        Guard.call("mini-lease-send"){
            context.sendBroadcast(Intent(ACTION).setPackage("android")
                .putExtra("task",id).putExtra("user",taskUser)
                .putExtra("preparingFullscreen",preparingFullscreen)
                .putExtra("keepVisualTask",keepVisualTask)
                .putExtra("session",session).putExtra("when",SystemClock.uptimeMillis()),
                PERMISSION,BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        }
    }
    companion object {
        const val HOME_HANG="io.github.s24u.popupscale.action.HOME_HANG"
        const val HOME_REPLY="io.github.s24u.popupscale.action.HOME_HANG_REPLY"
        const val ACTION="io.github.s24u.popupscale.action.MINI_TASK_LEASE"
        const val ACK="io.github.s24u.popupscale.action.MINI_TASK_LEASE_ACK"
        const val SELECTED="io.github.s24u.popupscale.action.MINI_TASK_SELECTED"
        const val CONTENT_PROFILE="io.github.s24u.popupscale.action.CONTENT_PROFILE"
        const val CONTENT_CHANGED="io.github.s24u.popupscale.action.CONTENT_CHANGED"
        const val PERMISSION="android.permission.MANAGE_ACTIVITY_TASKS"
    }
}
