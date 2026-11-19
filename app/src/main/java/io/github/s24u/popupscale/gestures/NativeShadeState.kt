package io.github.s24u.popupscale.gestures

/** Reads native state only; View.isShown is not evidence that a shade is expanded. */
data class NativeShadeState(val expanded:Boolean,val tracking:Boolean,val source:String,
    val panelFraction:Float?=null,val panelHeight:Float?=null,
    val qsReportedExpanded:Boolean?=null,val qsFraction:Float?=null,
    val qsTracking:Boolean=false,val qsAnimating:Boolean=false,
    val geometryCollapsed:Boolean=false,val runningAnimator:Boolean?=null,
    val qsAnimationFlag:Boolean?=null,val qsImmediate:Boolean?=null,
    val qsAnimatorRunning:Boolean?=null,val qsAnimatorStarted:Boolean?=null,
    val panelAnimatorRunning:Boolean?=null) {
    companion object {
        fun read(panel:Any,field:(Any,String)->Any?,call:(Any,String)->Any?):NativeShadeState? {
            fun boolean(owner:Any,method:String,vararg fields:String):Boolean? =
                (call(owner,method) as? Boolean) ?: fields.firstNotNullOfOrNull{field(owner,it) as? Boolean}
            val fraction=(field(panel,"mExpandedFraction") as? Number)?.toFloat()
                ?: (field(panel,"expandedFraction") as? Number)?.toFloat()
                ?: (call(panel,"getExpandedFraction") as? Number)?.toFloat()
            val nativeOpen=call(panel,"isExpandedWithoutHeadsUp") as? Boolean
            val collapsed=call(panel,"isFullyCollapsed") as? Boolean
            val height=(field(panel,"mExpandedHeight") as? Number)?.toFloat()
            val open=nativeOpen ?: fraction?.takeIf{it.isFinite()}?.let{it>0f}
                ?: collapsed?.let{!it} ?: height?.takeIf{it.isFinite()}?.let{it>0f} ?: return null
            val qs=field(panel,"mQsController") ?: field(panel,"qsController")
            val qsReported=qs?.let{boolean(it,"getExpanded","mExpanded","expanded")}
            val qsTrackingValue=qs?.let{boolean(it,"isTracking","mTracking","tracking")}
            val qsTracking=qsTrackingValue ?: false
            // Samsung can leave legacyIsQsExpanded=true after a corner gesture although
            // the QS height is exactly its collapsed minimum (451/451 on the capture).
            // Only trust geometry with a valid initialized range; unknown ROMs retain
            // the original boolean. Never alter native state or unlock a live gesture.
            fun number(owner:Any,name:String)=(field(owner,name) as? Number)?.toFloat()?.takeIf{it.isFinite()}
            val qsHeight=qs?.let{number(it,"mExpansionHeight")}
            val qsMin=qs?.let{number(it,"mMinExpansionHeight")}
            val qsMax=qs?.let{number(it,"mMaxExpansionHeight")}
            val qsFraction=if(qsHeight!=null&&qsMin!=null&&qsMax!=null&&qsMin>=0f&&qsMax>qsMin)
                ((qsHeight-qsMin)/(qsMax-qsMin)).coerceIn(0f,1f) else null
            val qsAnimationFlag=qs?.let{field(it,"mAnimating") as? Boolean}
            val qsImmediate=qs?.let{boolean(it,"isExpandImmediate","mExpandImmediate")}
            val qsAnimator=qs?.let{field(it,"mExpansionAnimator")}
            val qsRunning=qsAnimator?.let{call(it,"isRunning") as? Boolean}
            val qsStarted=qsAnimator?.let{call(it,"isStarted") as? Boolean}
            val panelRunning=field(panel,"mHeightAnimator")?.let{call(it,"isRunning") as? Boolean}
            val qsAnimating=qs?.let {
                qsAnimationFlag==true||qsImmediate==true||
                    qsRunning==true||qsStarted==true
            } ?: false
            val qsOpen=when {
                qsFraction==null->qsReported ?: false
                qsFraction>0f->true
                qsTracking||qsAnimating->true
                qsTrackingValue==false&&qsAnimationFlag==false&&qsImmediate==false->false
                else->qsReported ?: false // Missing live-state ABI must not unlock a reported open QS.
            }
            val panelTracking=boolean(panel,"isTracking","mTracking","tracking")
            val tracking=(panelTracking ?: false)||
                qsTracking
            val geometryClosed=fraction?.isFinite()==true&&fraction<=0f&&
                height?.isFinite()==true&&height<=0f&&qsFraction==0f&&
                panelTracking==false&&qsTrackingValue==false
            val running=if(qsRunning==true||panelRunning==true)true
                else if(qsRunning==false||panelRunning==false)false else null
            val source=if(qsReported==true&&!qsOpen)"native-qs-collapsed"
                else if(nativeOpen!=null)"native-expanded" else "native-geometry"
            return NativeShadeState(open||qsOpen,tracking,source,fraction?.takeIf{it.isFinite()},
                height?.takeIf{it.isFinite()},qsReported,qsFraction,qsTracking,qsAnimating,
                geometryClosed,running,qsAnimationFlag,qsImmediate,qsRunning,qsStarted,panelRunning)
        }
    }
}
