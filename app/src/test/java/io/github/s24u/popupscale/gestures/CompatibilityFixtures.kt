package io.github.s24u.popupscale.gestures

internal open class CompatibilityParentFixture {
    @Suppress("unused") private val caption=1
    @Suppress("unused") private val deviceState=Any()
    @Suppress("unused") fun configure(value:Int,preserve:Boolean):Boolean=value>0&&preserve
    @Suppress("unused") fun minimizeAllTasksLocked(displayId:Int,reason:Int,animate:Boolean){}
}
internal class CompatibilityChildFixture:CompatibilityParentFixture()
internal object CompatibilityInitCounter {var initialized=false}
internal class CompatibilityColdFixture {
    companion object {init{CompatibilityInitCounter.initialized=true}}
}

/** Actual ordering from the S9280ZCU4BYDA report; invoking this catches mask/x corruption. */
internal class LegacyDragFixture {
    @Suppress("unused") fun onDragPositioningStart(x:Float,y:Float,mask:Int):String="$x/$y/$mask"
}
internal class ModernDragFixture {
    @Suppress("unused") fun onDragPositioningStart(mask:Int,display:Int,input:Int,x:Float,y:Float):String="$x/$y/$mask/$display/$input"
}
internal open class LegacyModelParentFixture(@Suppress("unused") private val mWindowDecorByTaskId:Map<Int,Any>)
internal class LegacyModelFixture(entries:Map<Int,Any>):LegacyModelParentFixture(entries)
internal class LegacyListenerFixture(@Suppress("unused") private val mWindowDecorationViewModel:Any)
internal data class UnknownDecorationFixture(val id:Int)
internal class UnrelatedGraphFixture(@Suppress("unused") val arbitraryChildren:List<Any>)

internal open class OutlineLegacyFixture {
    @Suppress("unused") fun updateOutlineViewData(radius:Int,width:Int,caption:Int,focused:Boolean):Boolean=true
    @Suppress("unused") fun onDraw(canvas:android.graphics.Canvas){}
}
internal class OutlineModernFixture:OutlineLegacyFixture() {
    @Suppress("unused") fun updateOutlineViewData(radius:Int,width:Int,caption:Int,focused:Boolean,stashed:Boolean):Boolean=false
    @Suppress("unused") fun updateOutlineViewData(radius:Int,width:Int,caption:Int,focused:Boolean,stashed:Boolean,scale:Float){}
}
internal class OutlineNoUpdateFixture {
    @Suppress("unused") fun draw(canvas:android.graphics.Canvas){}
}
