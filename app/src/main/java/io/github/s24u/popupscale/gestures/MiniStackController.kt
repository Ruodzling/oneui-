package io.github.s24u.popupscale.gestures

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.Choreographer
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator

/** The cards are real task surfaces. Every stacked card intercepts input so back cards cannot
 * leak touches to their underlying apps; taps only expand when started on the front card. */
class MiniStackController(private val context:Context,private val backend:TaskBackend,
                          private val members:()->Map<Int,LiveMiniController>){
    private val order=MiniStackPolicy.Order()
    private val applied=MiniStackPolicy.FrameCache()
    private val choreographer=Choreographer.getInstance()
    private var previewCallback:Choreographer.FrameCallback?=null
    private data class Preview(val motion:MiniStackPolicy.Motion,val dx:Float,val dy:Float)
    private var preview:Preview?=null
    private var touchWidth=0
    private var touchHeight=0
    private var anchor:Rect?=null
    private var animator:ValueAnimator?=null
    private var down=false
    private var gesture:MiniStackPolicy.Gesture?=null
    private var touchTask=-1
    private var touchTarget=-1
    private var touchStartedOnFront=false
    private var touchFrames=emptyList<MiniStackPolicy.Frame>()
    private var downX=0f;private var downY=0f;private var downAt=0L
    private var start=Rect()
    private var velocity:VelocityTracker?=null
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private var raised=-1
    private fun reconcileMembers():Map<Int,LiveMiniController>{
        val all=members()
        val before=order.ids()
        val eligible=all.filterValues{it.stackMember()}.keys.sortedDescending()
        if(order.reconcile(eligible)){
            // A stale animation/input batch must not pull a docked icon back into a card.
            cancel()
            before.filter{it !in eligible}.forEach{id->all[id]?.releaseStack();applied.invalidate(id)}
            if(eligible.isEmpty())anchor=null
            raised=-1
        }
        return all
    }
    fun front():Int {reconcileMembers();return order.ids().firstOrNull() ?: -1}
    fun count():Int {reconcileMembers();return order.ids().size}
    fun add(ids:List<Int>){order.add(ids);raised=-1}
    fun remove(id:Int){if(down)cancel();order.remove(id);applied.invalidate(id);raised=-1;refresh()}
    fun homeAfterExpand(expandedId:Int){
        if(down)cancel()
        order.remove(expandedId);applied.invalidate(expandedId);raised=-1
        val all=reconcileMembers()
        val remaining=order.ids().filter{all[it]?.stackReady()==true}
        val nextDefault=remaining.firstOrNull()?.let{all[it]?.defaultMiniRect()}?.let{
            LivePlacement.Bounds(it.left,it.top,it.width(),it.height())
        }
        val home=MiniStackPolicy.homeAnchor(nextDefault,remaining.size)
        if(home!=null){
            anchor=rectangle(home)
            Guard.info("mini stack homing after expand expanded=$expandedId front=${remaining.first()} count=${remaining.size} target=${home.x},${home.y}")
        }
        refresh(animated=true,returnReleasedToDefault=true)
    }
    fun invalidate(id:Int){applied.invalidate(id)}
    fun invalidateAll(){applied.clear()}
    fun entryTarget(fallback:Rect):Rect {
        val all=reconcileMembers()
        val existing=order.ids().firstNotNullOfOrNull{id->all[id]?.takeIf{it.stackReady()}?.miniRect()}
        val origin=anchor ?: existing
        val m=GestureDisplay.metrics(context)
        fun b(r:Rect)=LivePlacement.Bounds(r.left,r.top,r.width(),r.height())
        val target=MiniStackPolicy.entryTarget(b(fallback),origin?.let{b(it)},m.widthPixels,m.heightPixels)
        if(origin!=null){anchor=Rect(target.x,target.y,target.x+target.width,target.y+target.height)
            Guard.info("HOME mini direct stack target=${target.x},${target.y} existing=true")}
        return rectangle(target)
    }
    private fun unlockTouch(){members().values.toList().forEach{it.lockStackTouch(false)}}
    fun cancel(){
        stopPreview(false)
        if(gesture?.motion==MiniStackPolicy.Motion.DRAG)anchor=Rect(start)
        down=false;gesture=null;touchTask=-1;touchTarget=-1;touchStartedOnFront=false;touchFrames=emptyList();unlockTouch()
        animator?.cancel();animator=null;velocity?.recycle();velocity=null
    }
    private fun frames():List<MiniStackPolicy.Frame>{
        val all=reconcileMembers()
        val ids=order.ids().filter{all[it]?.stackReady()==true}
        if(ids.size<2)return emptyList()
        val m=GestureDisplay.metrics(context)
        val current=anchor ?: all[ids.first()]!!.miniRect().also{anchor=Rect(it)}
        val layers=MiniStackPolicy.layers(ids,ids.associateWith{all[it]!!.nativeStackLayer()})
        return MiniStackPolicy.layout(ids,ids.associateWith{all[it]!!.miniSize()},current.left,current.top,
            m.widthPixels,m.heightPixels,m.density).map{it.copy(layer=layers[it.task])}
    }
    private fun rectangle(b:LivePlacement.Bounds)=Rect(b.x,b.y,b.x+b.width,b.y+b.height)
    private fun apply(frames:List<MiniStackPolicy.Frame>){
        val all=members()
        val changes=applied.changes(frames)
        if(changes.isEmpty())return
        backend.surfaceTransaction{tx->changes.asReversed().forEach{f->
            all[f.task]?.stackFrame(rectangle(f.bounds),f.front,f.alpha,f.layer,tx)
        }}
        applied.committed(changes)
    }
    private fun raise(frames:List<MiniStackPolicy.Frame>){
        val id=frames.firstOrNull()?.task ?: return
        if(raised==id)return
        // Reordering native Tasks does not change these non-focusable task surfaces on One UI.
        // The scoped layer pin is submitted with each card's geometry instead.
        raised=id
        Guard.info("mini stack surface order="+frames.joinToString(","){"${it.task}:${it.layer}"})
    }
    fun refresh(animated:Boolean=false,returnReleasedToDefault:Boolean=false){
        val allMembers=reconcileMembers()
        if(down||animator!=null)return
        if(order.ids().any{allMembers[it]?.let{member->member.busy()&&!member.stackReady()}==true})return
        val frames=frames()
        if(frames.isEmpty()){
            unlockTouch();applied.clear()
            members().values.forEach{it.releaseStack(returnReleasedToDefault)}
            anchor=null;raised=-1;return
        }
        raise(frames)
        if(!animated){apply(frames);return}
        val all=members()
        val from=frames.associate{it.task to (all[it.task]?.miniRect() ?: rectangle(it.bounds))}
        if(frames.all{from[it.task]==rectangle(it.bounds)}){apply(frames);unlockTouch();return}
        frames.forEach{all[it.task]?.lockStackTouch(true)}
        var cancelled=false
        var count=0;var lastAt=0L;var maxGap=0L;var maxWork=0L
        animator=ValueAnimator.ofFloat(0f,1f).apply{
            duration=220;interpolator=DecelerateInterpolator()
            addUpdateListener{
                val began=System.nanoTime()
                if(lastAt!=0L)maxGap=maxOf(maxGap,began-lastAt)
                lastAt=began;count++
                val t=it.animatedValue as Float
                if(Guard.call("mini-stack-animation"){
                    apply(frames.map{f->
                        val a=from.getValue(f.task);val b=f.bounds
                        f.copy(bounds=LivePlacement.Bounds(
                            (a.left+(b.x-a.left)*t).toInt(),(a.top+(b.y-a.top)*t).toInt(),
                            (a.width()+(b.width-a.width())*t).toInt().coerceAtLeast(1),
                            (a.height()+(b.height-a.height())*t).toInt().coerceAtLeast(1)))
                    });true
                }!=true){
                    cancel()
                    frames.forEach{f->all[f.task]?.let{member->Guard.call("mini-stack-recover"){member.restoreImmediately()}}}
                }
                maxWork=maxOf(maxWork,System.nanoTime()-began)
            }
            addListener(object:AnimatorListenerAdapter(){
                override fun onAnimationCancel(animation:Animator){cancelled=true}
                override fun onAnimationEnd(animation:Animator){
                    animator=null;unlockTouch()
                    Guard.info("mini stack animation callbacks=$count maxGapMs=${maxGap/1000000} maxWorkMs=${maxWork/1000000} cancelled=$cancelled")
                    if(!cancelled)Guard.call("mini-stack-settle"){refresh()}
                }
            })
            start()
        }
    }
    fun touch(id:Int,event:MotionEvent):Boolean {
        val all=reconcileMembers()
        if(all[id]?.stackMember()!=true)return false
        if(!down&&order.ids().size<2)return false
        val frontId=front()
        // Never fall through to an individual card's free-drag handler while in a stack.
        if(!down&&(frontId<0||order.ids().any{all[it]?.stackReady()!=true})){
            if(event.actionMasked==MotionEvent.ACTION_DOWN)Guard.info("mini stack input deferred task=$id front=$frontId")
            refresh();return true
        }
        if(down&&id!=touchTask)return true
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{
                animator?.cancel();animator=null
                val frontRect=all[frontId]?.miniRect() ?: return true
                val metrics=GestureDisplay.metrics(context);touchWidth=metrics.widthPixels;touchHeight=metrics.heightPixels
                val pad=(4*metrics.density).toInt()
                val onFront=MiniStackPolicy.hitFront(event.rawX,event.rawY,
                    LivePlacement.Bounds(frontRect.left,frontRect.top,frontRect.width(),frontRect.height()),pad)
                down=true;touchTask=id;touchTarget=frontId;touchStartedOnFront=onFront
                downX=event.rawX;downY=event.rawY;downAt=event.eventTime
                start=Rect(anchor ?: frontRect)
                gesture=MiniStackPolicy.Gesture(downX,downY,downAt,slop,ViewConfiguration.getLongPressTimeout().coerceAtLeast(350))
                touchFrames=frames().map{f->
                    val r=all[f.task]?.miniRect()
                    if(r==null||r.isEmpty)f else f.copy(bounds=LivePlacement.Bounds(r.left,r.top,r.width(),r.height()))
                }
                touchFrames.forEach{all[it.task]?.lockStackTouch(true)}
                velocity?.recycle();velocity=VelocityTracker.obtain().also{it.addMovement(event)}
            }
            MotionEvent.ACTION_MOVE->{
                if(!down)return true
                velocity?.addMovement(event)
                if(event.pointerCount!=1){cancel();refresh(true);return true}
                val dx=event.rawX-downX;val dy=event.rawY-downY
                val motion=gesture?.move(event.rawX,event.rawY,event.eventTime)
                if(motion!=null&&motion!=MiniStackPolicy.Motion.TAP)queuePreview(Preview(motion,dx,dy))
            }
            MotionEvent.ACTION_UP->{
                if(!down)return true
                velocity?.addMovement(event);velocity?.computeCurrentVelocity(1000)
                val m=GestureDisplay.metrics(context)
                val targetId=touchTarget.takeIf{it>=0} ?: front()
                val startedOnFront=touchStartedOnFront
                val motion=gesture?.move(event.rawX,event.rawY,event.eventTime) ?: MiniStackPolicy.Motion.TAP
                val direction=if(motion==MiniStackPolicy.Motion.SWIPE)MiniStackPolicy.swipe(event.rawX-downX,event.rawY-downY,
                    velocity?.xVelocity ?: 0f,m.density,all[targetId]?.miniSize()?.width ?: 0) else 0
                val tap=motion==MiniStackPolicy.Motion.TAP
                stopPreview(true)
                Guard.info("mini stack release task=$targetId source=$id onFront=$startedOnFront mode=$motion dxDp=${((event.rawX-downX)/m.density).toInt()} " +
                    "dyDp=${((event.rawY-downY)/m.density).toInt()} elapsed=${event.eventTime-downAt} direction=$direction")
                down=false;gesture=null;touchTask=-1;touchTarget=-1;touchStartedOnFront=false;touchFrames=emptyList()
                velocity?.recycle();velocity=null
                if(tap){
                    unlockTouch()
                    if(MiniStackPolicy.expandOnTap(startedOnFront)&&targetId>=0){
                        order.remove(targetId);raised=-1
                        all[targetId]?.expandStack()
                    }
                }else{
                    if(direction!=0){order.switch(direction);raised=-1;Guard.info("mini stack switched front=${front()} count=${count()}")}
                    if(motion==MiniStackPolicy.Motion.DRAG){
                        val box=frames().firstOrNull()?.bounds
                        if(box!=null)anchor=rectangle(box)
                    }
                    refresh(true)
                }
            }
            MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->{cancel();refresh(true)}
        }
        return true
    }
    private fun queuePreview(value:Preview){
        preview=value
        if(previewCallback!=null)return
        val callback=Choreographer.FrameCallback{
            previewCallback=null
            val value=preview;preview=null
            if(down&&value!=null)Guard.call("mini-stack-preview"){renderPreview(value)}
        }
        previewCallback=callback;choreographer.postFrameCallback(callback)
    }
    private fun stopPreview(flush:Boolean){
        previewCallback?.let{choreographer.removeFrameCallback(it)};previewCallback=null
        val value=preview;preview=null
        if(flush&&down&&value!=null)renderPreview(value)
    }
    private fun renderPreview(value:Preview){
        if(value.motion==MiniStackPolicy.Motion.DRAG){
            anchor=Rect(start).apply{offset(value.dx.toInt(),value.dy.toInt())}
            apply(touchFrames.map{f->f.copy(bounds=f.bounds.copy(
                x=(f.bounds.x+value.dx).toInt().coerceIn(0,(touchWidth-f.bounds.width).coerceAtLeast(0)),
                y=(f.bounds.y+value.dy).toInt().coerceIn(0,(touchHeight-f.bounds.height).coerceAtLeast(0))))})
        }else if(value.motion==MiniStackPolicy.Motion.SWIPE){
            apply(touchFrames.map{f->if(f.front)f.copy(bounds=f.bounds.copy(
                x=(f.bounds.x+value.dx*.55f).toInt().coerceIn(0,(touchWidth-f.bounds.width).coerceAtLeast(0)))) else f})
        }
    }
}
