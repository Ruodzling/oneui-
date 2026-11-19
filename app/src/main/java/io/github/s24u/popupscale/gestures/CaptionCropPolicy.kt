package io.github.s24u.popupscale.gestures

import kotlin.math.ceil

/** Screen-frame Insets become window-container coordinates after the native surface scale. */
object CaptionCropPolicy {
    // Hosts whose root wrapper (e.g. SwipeBackLayout) consumes captionBar height without applying topMargin
    // leave the bottom caption strip transparent (square corners) and suffer top over-crop when cropped.
    private val CLIENT_CAPTION_SUPPRESSED_PACKAGES=setOf("com.xingin.xhs")
    fun suppressesClientCaption(packageName:String?)=packageName in CLIENT_CAPTION_SUPPRESSED_PACKAGES
    @JvmOverloads fun activityRoot(root:LivePlacement.Bounds,task:LivePlacement.Bounds,caption:Int,
                     directParent:Boolean,rotating:Boolean,allowZeroCaption:Boolean=false):Crop? {
        if(!directParent||rotating||root!=task)return null
        return crop(root.width,root.height,caption,1f,1f,allowZeroCaption)
    }
    fun caption(current:Int,last:Int?,aligned:Boolean,fullSize:Boolean,zeroElapsed:Long):Int? {
        if(!aligned)return last?.takeIf{fullSize}
        if(current<=0&&last!=null&&zeroElapsed in 0L..350L)return last
        return current.takeIf{it>0}
    }
    data class Crop(val top:Int,val right:Int,val bottom:Int)
    @JvmOverloads fun crop(width:Int,height:Int,caption:Int,sx:Float,sy:Float,allowZeroCaption:Boolean=false):Crop? {
        if(width<=0||height<=0||!sx.isFinite()||!sy.isFinite()||sx !in 0.125f..8f||sy !in 0.125f..8f)return null
        if(caption==0){
            if(!allowZeroCaption)return null
            val right=ceil(width/sx.toDouble()).toInt()
            val bottom=ceil(height/sy.toDouble()).toInt()
            return if(right>0&&bottom>0)Crop(0,right,bottom) else null
        }
        if(caption<0||caption.toLong()*3>=height)return null
        val top=ceil(caption/sy.toDouble()).toInt()
        val right=ceil(width/sx.toDouble()).toInt()
        val bottom=ceil(height/sy.toDouble()).toInt()
        return if(top in 1 until bottom&&right>0)Crop(top,right,bottom) else null
    }
    fun resizesFreeformBounds(configSetMask:Int,windowSetMask:Int,targetWindowingMode:Int,
                              currentX:Int,currentY:Int,currentW:Int,currentH:Int,
                              targetX:Int,targetY:Int,targetW:Int,targetH:Int):Boolean {
        if((configSetMask and 0x20000000)==0||(windowSetMask and 1)==0)return false
        if((windowSetMask and 8)!=0&&targetWindowingMode!=0&&targetWindowingMode!=5)return false
        if(targetW<=0||targetH<=0)return false
        return currentX!=targetX||currentY!=targetY||currentW!=targetW||currentH!=targetH
    }
}

/** Tracks Organizer syncs resizing a freeform Task while Shell holds the previous surface scale. */
class ResizeSyncHold(private val ttlMs:Long=6000L) {
    private val syncTasks=mutableMapOf<Int,Set<Int>>()
    private val taskDeadlines=mutableMapOf<Int,MutableMap<Int,Long>>()

    @Synchronized fun hold(syncId:Int,taskIds:Set<Int>,nowMs:Long) {
        if(syncId<=0||taskIds.isEmpty())return
        pruneLocked(nowMs)
        val expires=nowMs+ttlMs
        val copy=taskIds.toSet()
        syncTasks[syncId]=copy
        for(id in copy) taskDeadlines.getOrPut(id){mutableMapOf()}[syncId]=expires
    }

    @Synchronized fun isHeld(taskId:Int,nowMs:Long):Boolean {
        pruneLocked(nowMs)
        return taskDeadlines[taskId]?.isNotEmpty()==true
    }

    @Synchronized fun releaseSync(syncId:Int,nowMs:Long):Set<Int> {
        pruneLocked(nowMs)
        val tasks=syncTasks.remove(syncId) ?: return emptySet()
        val released=linkedSetOf<Int>()
        for(id in tasks) {
            val map=taskDeadlines[id] ?: continue
            map.remove(syncId)
            if(map.isEmpty()){taskDeadlines.remove(id);released.add(id)}
        }
        return released
    }

    @Synchronized fun clearTask(taskId:Int) {
        val syncs=taskDeadlines.remove(taskId)?.keys?.toList() ?: return
        for(syncId in syncs) {
            val current=syncTasks[syncId] ?: continue
            val remaining=current-taskId
            if(remaining.isEmpty())syncTasks.remove(syncId) else syncTasks[syncId]=remaining
        }
    }

    private fun pruneLocked(nowMs:Long) {
        val iter=taskDeadlines.entries.iterator()
        while(iter.hasNext()){
            val (_,map)=iter.next()
            map.entries.removeAll{nowMs>it.value}
            if(map.isEmpty())iter.remove()
        }
        syncTasks.entries.removeAll{(syncId,ids)->ids.none{taskDeadlines[it]?.containsKey(syncId)==true}}
    }
}
