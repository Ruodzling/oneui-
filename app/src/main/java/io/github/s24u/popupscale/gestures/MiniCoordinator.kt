package io.github.s24u.popupscale.gestures

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

/** Each live task owns its surface, lease and recovery journal; the stack only arranges cards. */
class MiniCoordinator(private val context:Context,private val backend:TaskBackend,private val config:FeatureConfig,
                      private val frame:TransitionFrameOverlay,private val released:(Int)->Unit) {
    private val main=Handler(Looper.getMainLooper())
    private val snapshot=MiniWindowController(context,backend)
    private val lives=ConcurrentHashMap<Int,LiveMiniController>()
    private var shadeObscured=false
    private val stack=MiniStackController(context,backend){lives}
    init {
        val journal=context.createDeviceProtectedStorageContext().getSharedPreferences("popupscale_live_recovery",Context.MODE_PRIVATE)
        journal.all.forEach{(key,value)->
            if(key=="task"||key.matches(Regex("task-[0-9]+"))){
                val id=(value as? String)?.substringBefore(',')?.toIntOrNull()
                if(id!=null&&id>=0&&!lives.containsKey(id))create(id,key)
            }
        }
    }
    private fun create(id:Int,key:String="task-"+id):LiveMiniController {
        lateinit var member:LiveMiniController
        member=LiveMiniController(context,backend,frame,{finished->
            if(lives.remove(finished,member))main.post{Guard.call("mini-stack-release"){stack.remove(finished);released(finished)}}
        },key,{changedId->main.post{Guard.call("mini-stack-ready"){stack.invalidate(changedId);stack.refresh(true)}}},{task,event->stack.touch(task,event)})
            .also{it.setShadeObscured(shadeObscured);lives[id]=it}
        return member
    }
    private fun controller(id:Int):LiveMiniController=synchronized(lives){
        lives[id] ?: create(id)
    }
    fun isMini(id:Int)=snapshot.isMini(id)||lives[id]?.isMini(id)==true
    fun inputTouchable(id:Int):Boolean?=lives[id]?.inputTouchable()
    fun setShadeObscured(obscured:Boolean){
        shadeObscured=obscured
        Guard.call("snapshot-shade-state"){snapshot.setShadeObscured(obscured)}
        lives.values.toList().forEach{Guard.call("live-shade-state"){it.setShadeObscured(obscured)}}
        if(obscured)Guard.call("stack-shade-cancel"){stack.cancel()}
    }
    fun busy()=snapshot.busy()||lives.values.any{it.busy()&&!it.stackReady()}
    fun activeTaskId()=stack.front().takeIf{isMini(it)} ?: lives.entries.firstOrNull{it.value.activeTaskId()>=0}?.key ?: snapshot.activeTaskId()
    fun status()="liveCount="+lives.values.count{it.activeTaskId()>=0}+" stackCount="+stack.count()+" "+
        (lives[activeTaskId()]?.status() ?: "livePhase=IDLE")
    fun hang(task:TaskRecord,nonce:String=""){
        if(isMini(task.id)||busy())return
        if(config.live)backend.loadContentProfile(task){
            config.reload()
            if(!isMini(task.id)&&!busy()&&config.enabled&&!config.observe&&config.live&&config.hang)
                controller(task.id).hang(task,nonce)
        }else snapshot.hang(task)
    }
    fun hangForHome(ids:List<Int>):List<Int>{
        val accepted=mutableListOf<Int>()
        for(id in ids.distinct()){
            if(isMini(id)){accepted.add(id);continue}
            val task=backend.info(id)?.let{backend.record(it)} ?: continue
            if(task.mode!=5||!task.visible||backend.locked())continue
            val fallback=backend.miniBounds(task.bounds) ?: continue
            val destination=stack.entryTarget(fallback)
            if(controller(id).shrinkFromFreeformResize(task,false,task.bounds,allowOffscreen=true,destination=destination))accepted.add(id)
            else if(lives[id]?.busy()!=true)lives.remove(id)
        }
        stack.add(accepted);stack.refresh()
        Guard.info("HOME live minis accepted="+accepted.joinToString(",")+" count="+accepted.size)
        return accepted
    }
    fun dockFromFreeform(task:TaskRecord,startRect:Rect,left:Boolean):Boolean {
        if(isMini(task.id)||busy()||!config.live)return false
        return controller(task.id).dockFromFreeform(task,startRect,left)
    }
    fun shrinkFromFreeformResize(task:TaskRecord,left:Boolean,startRect:Rect=task.bounds):Boolean {
        if(isMini(task.id)||busy())return false
        if(config.live)return controller(task.id).shrinkFromFreeformResize(task,left,startRect)
        snapshot.hang(task);return true
    }
    fun pinFreeformStash(task:TaskRecord,startRect:Rect){
        if(!isMini(task.id)&&!busy()&&config.live)controller(task.id).pinFreeformStash(task,startRect)
    }
    fun reassertSurface(id:Int){lives[id]?.reassertSurface(id)}
    fun prepareFrame(task:TaskRecord,bounds:Rect,nonce:String,reply:android.os.ResultReceiver){
        if(busy()){reply.send(0,null);return}
        frame.prepare(task,bounds,nonce,reply)
    }
    fun recoverIfNeeded(){snapshot.recoverIfNeeded();lives.values.toList().forEach{it.recoverIfNeeded()}}
    fun tick(){
        if(config.live)snapshot.restoreImmediately() else {stack.cancel();lives.values.toList().forEach{it.restoreImmediately()}}
        lives.values.toList().forEach{it.tick()}
        lives.entries.filter{!it.value.busy()&&!it.value.hasPreparedPin()}.forEach{lives.remove(it.key,it.value);stack.remove(it.key)}
        stack.refresh()
    }
    fun restoreImmediately(){frame.clear();stack.cancel();snapshot.restoreImmediately();lives.values.toList().forEach{it.restoreImmediately()}}
    fun onTaskVanished(id:Int){snapshot.onTaskVanished(id);lives[id]?.onTaskVanished(id)}
    fun hideForLock(){frame.clear();stack.cancel();snapshot.hideForLock();lives.values.toList().forEach{it.restoreImmediately()}}
    fun screenOn(){snapshot.screenOn()}
    fun lockVisibility(){snapshot.lockVisibility()}
    fun onTaskChanged(id:Int){lives[id]?.onTaskChanged(id)}
    fun onDisplayChanged(){stack.cancel();stack.invalidateAll();lives.values.toList().forEach{it.tick()};stack.refresh()}
}
