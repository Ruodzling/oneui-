package io.github.s24u.popupscale.gestures

import kotlin.math.roundToInt

object LivePlacement {
    fun preserveLandscape(sourceWidth:Int,sourceHeight:Int,game:Boolean,statusHidden:Boolean,requestsLandscape:Boolean):Boolean =
        (sourceWidth>sourceHeight&&(game||statusHidden))||((game||statusHidden)&&requestsLandscape)
    data class Bounds(val x:Int,val y:Int,val width:Int,val height:Int)
    /** Resolve a synced resize from WM's applied frame, never the old organizer crop.
     * The task may have a non-zero parent origin and native minimum-size adjustments. */
    fun resizeFrame(requested:Bounds,applied:Bounds?,previous:Bounds,parentX:Int,parentY:Int):Bounds {
        val frame=applied?.takeIf{it.width>0&&it.height>0} ?: requested
        return frame.copy(x=frame.x-(previous.x-parentX),y=frame.y-(previous.y-parentY))
    }
    data class Placement(val x:Int,val y:Int,val width:Int,val height:Int,val scale:Float)
    data class MotionBounds(val stable:Bounds,val safe:Bounds,val maximum:Bounds)
    data class MiniTransform(val scale:Float,val x:Float,val y:Float,val cropWidth:Int,val cropHeight:Int,
                             val cropLeft:Int=0,val cropTop:Int=0) {
        val cropRight:Int get()=cropLeft+cropWidth
        val cropBottom:Int get()=cropTop+cropHeight
    }
    /** Standardize upright freeform minis to a uniform card size; preserve landscape content aspect. */
    fun miniPlace(screenWidth:Int,screenHeight:Int,sourceWidth:Int,sourceHeight:Int,density:Float,left:Boolean=false):Placement? {
        if(sourceWidth<=0||sourceHeight<=0)return null
        if(sourceWidth<=sourceHeight){
            val standard=standardWindow(screenWidth,screenHeight)?:return null
            return place(screenWidth,screenHeight,standard.width,standard.height,density,left)
        }
        return place(screenWidth,screenHeight,sourceWidth,sourceHeight,density,left)
    }
    fun scaledCornerRadius(nativeRadius:Float?,scale:Float,cropWidth:Int,cropHeight:Int):Float? {
        if(nativeRadius==null||!nativeRadius.isFinite()||nativeRadius<=0f||
            !scale.isFinite()||scale<=0f||cropWidth<=0||cropHeight<=0)return null
        return minOf(nativeRadius/scale,minOf(cropWidth,cropHeight)/2f)
    }
    fun miniTransform(source:Bounds,parentX:Int,parentY:Int,target:Bounds,topInset:Int=0,
                      topInsetWeight:Float=1f):MiniTransform {
        require(source.width>0&&source.height>0&&target.width>0&&target.height>0)
        val top=if(topInset>0&&topInset.toLong()*3<source.height)topInset else 0
        val weight=if(topInsetWeight.isFinite())topInsetWeight.coerceIn(0f,1f) else 1f
        val scaleX=target.width.toFloat()/source.width
        val scaleY=target.height.toFloat()/source.height
        val raw=if(kotlin.math.abs(source.width*scaleY-target.width)<=2f&&kotlin.math.abs(source.height*scaleX-target.height)<=2f){
            MiniTransform(scaleX,
                parentX+(target.x-source.x).toFloat(),parentY+(target.y-source.y).toFloat(),
                source.width,source.height)
        }else if(scaleX>=scaleY){
            val cropHeight=(target.height/scaleX).roundToInt().coerceIn(1,source.height)
            MiniTransform(scaleX,
                parentX+(target.x-source.x).toFloat(),parentY+(target.y-source.y).toFloat(),
                source.width,cropHeight,0,0)
        }else{
            val cropWidth=(target.width/scaleY).roundToInt().coerceIn(1,source.width)
            val cropLeft=((source.width-cropWidth)/2).coerceAtLeast(0)
            MiniTransform(scaleY,
                parentX+(target.x-source.x).toFloat()-cropLeft*scaleY,
                parentY+(target.y-source.y).toFloat(),
                cropWidth,source.height,cropLeft,0)
        }
        if(top<=0||top+4>=source.height)return raw
        if(raw.cropLeft==0&&raw.cropWidth==source.width){
            val visibleBottom=minOf(source.height,top+(target.height/raw.scale).roundToInt().coerceAtLeast(1))
            return raw.copy(cropTop=top,cropHeight=(visibleBottom-top).coerceAtLeast(1),
                y=raw.y-top*raw.scale*weight)
        }
        val visibleHeight=(source.height-top).coerceAtLeast(1)
        val fitScale=target.height.toFloat()/visibleHeight
        val fitCropWidth=(target.width/fitScale).roundToInt().coerceIn(1,source.width)
        val fitCropLeft=((source.width-fitCropWidth)/2).coerceAtLeast(0)
        return MiniTransform(fitScale,
            parentX+(target.x-source.x).toFloat()-fitCropLeft*fitScale,
            parentY+(target.y-source.y).toFloat()-top*fitScale*weight,
            fitCropWidth,visibleHeight,fitCropLeft,top)
    }
    /** Remove landscape vertical reserves from BOTH dragging and release physics.
     * Keep horizontal stash limits and the native amount of visible caption at the bottom. */
    fun edgeMotionBounds(screenWidth:Int,screenHeight:Int,source:MotionBounds):MotionBounds {
        if(screenWidth<=screenHeight||screenHeight<=0)return source
        val bottomDelta=screenHeight-(source.stable.y+source.stable.height)
        fun vertical(b:Bounds)=b.copy(y=0,height=(b.y+b.height+bottomDelta).coerceAtLeast(1))
        return MotionBounds(source.stable.copy(y=0,height=screenHeight),vertical(source.safe),vertical(source.maximum))
    }
    /** Keep a return-to-portrait request until native bounds really reach the default shape. */
    class RotationLayoutState {
        private val landscape=mutableMapOf<Int,Boolean>()
        private val pending=mutableSetOf<Int>()
        @Synchronized fun observe(task:Int,width:Int,height:Int){
            if(task<0||width<=0||height<=0)return
            val now=width>height
            val previous=landscape.put(task,now)
            if(now)pending.remove(task) else if(previous==true)pending.add(task)
        }
        @Synchronized fun portraitDefault(task:Int,width:Int,height:Int,source:Bounds):Bounds? {
            observe(task,width,height)
            if(task !in pending)return null
            val target=standardWindow(width,height) ?: return null
            if(kotlin.math.abs(source.width-target.width)<=2&&kotlin.math.abs(source.height-target.height)<=2){
                pending.remove(task);return null
            }
            return target
        }
        @Synchronized fun awaitingPortrait(task:Int)=task in pending
        @Synchronized fun forget(task:Int){landscape.remove(task);pending.remove(task)}
    }
    /** One normalization per freeform mode session. Activity transitions can briefly hide
     * the whole task; a visibility flap must not reset the user's manually chosen size. */
    class EntryLayoutState {
        private val sessions=mutableSetOf<Int>()
        private val pending=mutableSetOf<Int>()
        @Synchronized fun observe(task:Int,mode:Int,visible:Boolean,width:Int,height:Int,owned:Boolean=false):Boolean {
            if(task<0||width<=0||height<=0)return false
            if(mode!=5){sessions.remove(task);pending.remove(task);return false}
            if(!visible)return false
            val entering=sessions.add(task)
            if(width>height||owned){pending.remove(task);return entering}
            if(entering)pending.add(task)
            return entering
        }
        @Synchronized fun portraitDefault(task:Int,width:Int,height:Int,source:Bounds):Bounds? {
            if(width>height){pending.remove(task);return null}
            if(task !in pending)return null
            val target=standardWindow(width,height) ?: return null
            if(kotlin.math.abs(source.width-target.width)<=2&&kotlin.math.abs(source.height-target.height)<=2){
                pending.remove(task);return null
            }
            return target
        }
        @Synchronized fun awaiting(task:Int)=task in pending
        @Synchronized fun cancel(task:Int){pending.remove(task)}
        @Synchronized fun forget(task:Int){sessions.remove(task);pending.remove(task)}
    }
    fun standardWindow(screenWidth:Int,screenHeight:Int):Bounds? {
        if(screenWidth<=0||screenHeight<=0)return null
        val landscape=screenWidth>screenHeight
        val width=if(landscape)(screenHeight*.84f/1.45f).roundToInt() else (screenWidth*.76f).toInt()
        val height=if(landscape)(width*1.45f).roundToInt() else minOf((width*1.65f).toInt(),(screenHeight*.7f).toInt())
        if(width<1||height<1)return null
        val x=(screenWidth-width)/2;val y=(screenHeight-height)/2
        return Bounds(x,y,(screenWidth+width)/2-x,(screenHeight+height)/2-y)
    }
    fun landscapeContentWindow(screenWidth:Int,screenHeight:Int,aspect:Float):Bounds? {
        if(screenWidth<=0||screenHeight<=0||!aspect.isFinite()||aspect<=1f)return null
        val width=minOf(screenWidth*.76f,screenHeight*.70f*aspect).roundToInt().coerceAtLeast(1)
        val height=(width/aspect).roundToInt().coerceAtLeast(1)
        return Bounds((screenWidth-width)/2,(screenHeight-height)/2,width,height)
    }
    fun maxResizeWidth(screenWidth:Int,screenHeight:Int,aspect:Float,density:Float):Int {
        require(screenWidth>0&&screenHeight>0&&aspect>0&&aspect.isFinite()&&density>0&&density.isFinite())
        val room=if(screenWidth>screenHeight)screenHeight.toFloat() else screenHeight-32*density
        return minOf(screenWidth*.90f,room.coerceAtLeast(1f)/aspect).roundToInt().coerceAtLeast(1)
    }
    fun portraitInLandscape(screenWidth:Int,screenHeight:Int,source:Bounds,density:Float):Bounds {
        if(screenWidth<=screenHeight||source.width<=0||source.height<=0)return source
        val room=screenHeight.toFloat()
        // A manually enlarged upright window is still an upright window. Keep its size
        // up to the physical height; don't treat the old 32dp reserve as a rotation error.
        if(source.width<source.height&&source.height<=room&&source.width<=screenWidth*.90f)return source
        val shape=if(source.width<source.height){
            val scale=minOf(1f,room/source.height,screenWidth*.90f/source.width)
            source.copy(width=(source.width*scale).toInt().coerceAtLeast(1),height=(source.height*scale).toInt().coerceAtLeast(1))
        }else standardWindow(screenWidth,screenHeight) ?: return source
        val x=(source.x+source.width/2-shape.width/2).coerceIn(0,(screenWidth-shape.width).coerceAtLeast(0))
        val y=(source.y+source.height/2-shape.height/2).coerceIn(0,(room-shape.height).toInt().coerceAtLeast(0))
        return shape.copy(x=x,y=y)
    }
    fun hangTarget(screenWidth:Int,screenHeight:Int,density:Float,live:Boolean=true):Placement? {
        val source=standardWindow(screenWidth,screenHeight)?:return null
        return if(live)place(screenWidth,screenHeight,source.width,source.height,density)
            else place(screenWidth,screenHeight,screenWidth,screenHeight,density)
    }
    fun guideBounds(screenWidth:Int,screenHeight:Int,density:Float,live:Boolean=true):Bounds? {
        val mini=hangTarget(screenWidth,screenHeight,density,live)?:return null
        // Upper-right approach zone anchored to the mini's top/right corner, kept above normal portrait Home flicks.
        val width=(mini.width*1.45f).roundToInt()
        val right=mini.x+mini.width
        val ratio=if(screenWidth>screenHeight)2f/3f else 0.54f
        val bottom=maxOf(mini.y+mini.height,(screenHeight*ratio).roundToInt())
        if(right-width<0||bottom>screenHeight)return null
        return Bounds(right-width,mini.y,width,bottom-mini.y)
    }
    fun place(screenWidth:Int,screenHeight:Int,sourceWidth:Int,sourceHeight:Int,density:Float,left:Boolean=false):Placement? {
        if(screenWidth<=0||screenHeight<=0||sourceWidth<=0||sourceHeight<=0||!density.isFinite()||density<=0)return null
        val scale=minOf(1f,screenWidth*.25f/sourceWidth,screenHeight*.32f/sourceHeight)
        val width=(sourceWidth*scale).roundToInt().coerceAtLeast(1)
        val height=(sourceHeight*scale).roundToInt().coerceAtLeast(1)
        val margin=(12*density).roundToInt()
        val x=if(left)margin else screenWidth-width-margin
        val y=(48*density).roundToInt()
        if(x<0||y<0||x+width>screenWidth||y+height>screenHeight)return null
        return Placement(x,y,width,height,scale)
    }
}
