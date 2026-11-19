package io.github.s24u.popupscale.gestures

import io.github.s24u.popupscale.PlayerCompatPolicy
import io.github.s24u.popupscale.QQDensityPolicy
import io.github.s24u.popupscale.QQPanelPolicy
import io.github.s24u.popupscale.DouyinDensityPolicy

object GesturePolicyChecks{
    @JvmStatic fun main(args:Array<String>){
        CornerGestureChecks.run()
        NotificationRouteChecks.run()
        check(io.github.s24u.popupscale.UpdateRebootPolicy.shouldRemind(169,170,100,200,false))
        check(!io.github.s24u.popupscale.UpdateRebootPolicy.shouldRemind(170,170,100,200,false))
        check(!io.github.s24u.popupscale.UpdateRebootPolicy.shouldRemind(0,170,100,100,false))
        check(io.github.s24u.popupscale.UpdateRebootPolicy.shouldRemind(0,170,100,200,false))
        check(!io.github.s24u.popupscale.UpdateRebootPolicy.shouldRemind(169,170,100,200,true))
        println("PASS: update notice once per version, legacy-upgrade migration, fresh-install exclusion and loaded-core suppression.")
        val rootBounds=LivePlacement.Bounds(100,200,800,1400)
        check(CaptionCropPolicy.activityRoot(rootBounds,rootBounds,60,true,false)==CaptionCropPolicy.Crop(60,800,1400))
        check(CaptionCropPolicy.activityRoot(rootBounds,rootBounds,60,false,false)==null)
        check(CaptionCropPolicy.activityRoot(rootBounds,rootBounds,60,true,true)==null)
        check(CaptionCropPolicy.activityRoot(rootBounds.copy(x=101),rootBounds,60,true,false)==null)
        check(CaptionCropPolicy.activityRoot(rootBounds.copy(height=900),rootBounds,60,true,false)==null)
        check(CaptionCropPolicy.activityRoot(rootBounds,rootBounds,500,true,false)==null)
        check(OutlineHookMethods.updates(OutlineLegacyFixture::class.java).map{it.parameterCount}==listOf(4))
        check(OutlineHookMethods.updates(OutlineModernFixture::class.java).map{it.parameterCount}.toSet()==setOf(4,5,6))
        check(OutlineHookMethods.draws(OutlineModernFixture::class.java).single().name=="onDraw")
        check(OutlineHookMethods.updates(OutlineNoUpdateFixture::class.java).isEmpty())
        check(OutlineHookMethods.draws(OutlineNoUpdateFixture::class.java).single().name=="draw")
        check(FrameworkLayoutPolicy.organizerEntry(1,5))
        check(!FrameworkLayoutPolicy.organizerEntry(5,5)&&!FrameworkLayoutPolicy.organizerEntry(5,1))
        check(!FrameworkLayoutPolicy.organizerEntry(2,5)&&!FrameworkLayoutPolicy.organizerEntry(6,5))
        println("PASS: outline descriptors 4/5/6 and inherited draw callbacks discovered independently; only fullscreen-to-freeform entry normalizes transaction bounds.")
        TaskOpacityChecks.run()
        val extraction=NotificationMorphExtractionGate()
        val provider=Any();val bounds=Any()
        check(!extraction.owns(provider,bounds))
        val outer=extraction.begin(provider,bounds)
        check(extraction.owns(provider,bounds)&&!extraction.owns(Any(),bounds)&&!extraction.owns(provider,Any()))
        val nestedExtraction=extraction.begin(provider,Any())
        check(!extraction.owns(provider,bounds));extraction.end(nestedExtraction)
        check(extraction.owns(provider,bounds))
        val otherThread=java.util.concurrent.atomic.AtomicBoolean(true)
        Thread{otherThread.set(extraction.owns(provider,bounds))}.apply{start();join()}
        check(!otherThread.get());extraction.end(outer);check(!extraction.owns(provider,bounds))
        println("PASS: notification extraction guard matches only exact native provider/bounds on its invocation thread; nested scope restored and cleared.")
        fun morphTarget(pkg:String="owned",user:Int=0,display:Int=0,type:Int=1,wm:Int=5,
            mode:Int=2,shell:Boolean=false,task:Int=7,w:Int=800,h:Int=1300,leash:Boolean=true)=
            NotificationMorphTargetPolicy.accepts("owned",0,pkg,user,display,type,wm,mode,shell,task,w,h,leash)
        check(morphTarget()&&morphTarget(mode=0)&&morphTarget(mode=6,shell=true))
        check(!morphTarget(pkg="other")&&!morphTarget(user=95)&&!morphTarget(display=1))
        check(!morphTarget(type=2)&&!morphTarget(wm=1)&&!morphTarget(mode=1)&&!morphTarget(mode=2,shell=true))
        check(!morphTarget(task=-1)&&!morphTarget(w=0)&&!morphTarget(h=0)&&!morphTarget(leash=false))
        println("PASS: notification morph fallback selects only the same user/package standard freeform opening/change task; excludes closing/home/fullscreen/invalid surfaces.")
        fun notification(enabled:Boolean=true,observe:Boolean=false,option:Boolean=true,selected:Boolean=true,
                         activity:Boolean=true,appUid:Boolean=true,user:Int=0,display:Int=0,locked:Boolean=false,
                         interactive:Boolean=true,nativeAction:Boolean=true)=NotificationPullPolicy.eligible(
            enabled,observe,option,selected,activity,appUid,user,display,locked,interactive,nativeAction)
        check(notification())
        check(!notification(enabled=false)&&!notification(observe=true)&&!notification(option=false)&&!notification(selected=false))
        check(!notification(activity=false)&&!notification(appUid=false)&&!notification(user=444)&&!notification(display=1))
        check(!notification(locked=true)&&!notification(interactive=false)&&!notification(nativeAction=false))
        fun pull(dx:Float=0f,dy:Float=32f,vy:Float=0f,density:Float=1f,down:Int=1,current:Int=1,cancelled:Boolean=false)=
            NotificationPullPolicy.release(dx,dy,vy,density,down,current,cancelled)
        check(pull()&&pull(dy=12f,vy=700f)&&pull(dy=96f,density=3f))
        check(NotificationPullPolicy.shouldStartOnMove(2f,8f,8))
        check(!NotificationPullPolicy.shouldStartOnMove(0f,7f,8))
        check(!NotificationPullPolicy.shouldStartOnMove(12f,8f,8))
        check(!NotificationPullPolicy.shouldStartOnMove(0f,-8f,8))
        check(!pull(dy=31f)&&!pull(dy=11f,vy=2000f)&&!pull(dy=-40f,vy=-1000f))
        check(!pull(dx=40f)&&!pull(dx=20f,dy=12f,vy=1000f))
        check(!pull(current=2)&&!pull(cancelled=true)&&!pull(down=-1,current=-1))
        check(!pull(dy=Float.NaN)&&!pull(vy=Float.POSITIVE_INFINITY)&&!pull(density=0f))
        println("PASS: notification pull requires selected unlocked activity notification; downward thresholds, direction, generation, cancel and invalid values are gated.")
        check(CaptionAnchorPolicy.shift(60f,40f,3.75f)==12.5f)
        check(CaptionAnchorPolicy.shift(60f,55f,3.75f)==0f)
        check(CaptionAnchorPolicy.shift(60f,0f,3.75f)==30f)
        check(CaptionAnchorPolicy.shift(60f,Float.NaN,3.75f)==0f)
        check(CaptionCropPolicy.caption(0,60,false,true,5000L)==60)
        check(CaptionCropPolicy.caption(0,60,false,false,0L)==null)
        check(CaptionCropPolicy.caption(0,null,false,true,0L)==null)
        check(CaptionCropPolicy.caption(0,60,true,true,350L)==60)
        check(CaptionCropPolicy.caption(0,60,true,true,351L)==null)
        check(CaptionCropPolicy.caption(48,60,true,true,0L)==48)
        println("PASS: caption pill targets2dp gap without upward/accumulated movement; verified crop survives asynchronous full-window bounds and brief zero Insets, but does not convert an unverified/dialog window.")
        check(CaptionFillPolicy.taskFromHostTitle("Additional window of Task=1813 (Freeform Outline)")==1813)
        check(CaptionFillPolicy.taskFromHostTitle("Additional window of Task=1814 (Freeform Outline)")==1814)
        check(CaptionFillPolicy.taskFromHostTitle("Additional window of Task=1813 (Handle Menu)")==null)
        check(CaptionFillPolicy.taskFromHostTitle("Task=1813")==null)
        check(CaptionFillPolicy.taskFromHostTitle(null)==null)
        check(CaptionCropPolicy.crop(1000,1800,60,1f,1f)==CaptionCropPolicy.Crop(60,1000,1800))
        check(CaptionCropPolicy.crop(1000,1800,60,0.5f,0.5f)==CaptionCropPolicy.Crop(120,2000,3600))
        check(CaptionCropPolicy.crop(1000,1800,61,2f,2f)==CaptionCropPolicy.Crop(31,500,900))
        check(CaptionCropPolicy.crop(1000,1800,0,1f,1f)==null)
        check(CaptionCropPolicy.crop(1000,1800,0,1f,1f,allowZeroCaption=true)==CaptionCropPolicy.Crop(0,1000,1800))
        check(CaptionCropPolicy.crop(1000,1800,0,0.5f,0.5f,allowZeroCaption=true)==CaptionCropPolicy.Crop(0,2000,3600))
        check(CaptionCropPolicy.activityRoot(rootBounds,rootBounds,0,true,false)==null)
        check(CaptionCropPolicy.activityRoot(rootBounds,rootBounds,0,true,false,allowZeroCaption=true)==CaptionCropPolicy.Crop(0,800,1400))
        check(CaptionCropPolicy.suppressesClientCaption("com.xingin.xhs"))
        check(!CaptionCropPolicy.suppressesClientCaption("com.sec.android.app.popupcalculator"))
        check(!CaptionCropPolicy.suppressesClientCaption(null))
        check(CaptionCropPolicy.crop(1000,1800,600,1f,1f)==null)
        check(CaptionCropPolicy.crop(1000,1800,60,Float.NaN,1f)==null)
        check(CaptionCropPolicy.crop(1000,1800,60,1f,0f)==null)
        check(CaptionCropPolicy.resizesFreeformBounds(0x20000000,1,0,108,549,947,1562,300,866,840,1386))
        check(CaptionCropPolicy.resizesFreeformBounds(0x20000000,9,5,108,549,947,1562,300,866,840,1386))
        check(!CaptionCropPolicy.resizesFreeformBounds(0x20000000,1,0,300,866,840,1386,300,866,840,1386))
        check(!CaptionCropPolicy.resizesFreeformBounds(0,1,0,108,549,947,1562,300,866,840,1386))
        check(!CaptionCropPolicy.resizesFreeformBounds(0x20000000,2,0,108,549,947,1562,300,866,840,1386))
        check(!CaptionCropPolicy.resizesFreeformBounds(0x20000000,9,1,108,549,947,1562,300,866,840,1386))
        val holds=ResizeSyncHold(ttlMs=6000L)
        holds.hold(10,setOf(2583),1000L)
        check(holds.isHeld(2583,1000L)&&!holds.isHeld(2585,1000L))
        holds.hold(11,setOf(2583),1500L)
        check(holds.releaseSync(10,1600L).isEmpty())
        check(holds.isHeld(2583,1600L))
        check(holds.releaseSync(11,1700L)==setOf(2583))
        check(!holds.isHeld(2583,1700L))
        holds.hold(12,setOf(2585),2000L)
        check(holds.isHeld(2585,8000L)&&!holds.isHeld(2585,8001L))
        holds.hold(13,setOf(2586),3000L)
        holds.clearTask(2586)
        check(!holds.isHeld(2586,3001L)&&holds.releaseSync(13,3001L).isEmpty())
        println("PASS: caption-only surface crop follows native scale; zero/oversized Insets and invalid scales remain native; resize sync hold keeps prior crop until Organizer onTransactionReady.")
        check(CaptionFillPolicy.clear(true,false,true,true,5,true))
        check(!CaptionFillPolicy.clear(false,false,true,true,5,true))
        check(!CaptionFillPolicy.clear(true,true,true,true,5,true))
        check(!CaptionFillPolicy.clear(true,false,false,true,5,true))
        check(!CaptionFillPolicy.clear(true,false,true,false,5,true))
        check(!CaptionFillPolicy.clear(true,false,true,true,1,true))
        check(!CaptionFillPolicy.clear(true,false,true,true,6,true))
        check(!CaptionFillPolicy.clear(true,false,true,true,5,false))
        println("PASS: caption fill clearing applies only to opted-in selected ordinary freeform handle captions; disabled/observe/fullscreen/split/header retain native fill.")
        ShadeInteractionChecks.run()
        LeasePreparationChecks.run()
        DockInteractionChecks.run()
        LegacyHandleChecks.run()
        check(io.github.s24u.popupscale.CompatibilityStatus.reportState(-1,116,-1,2,0,20)=="missing")
        check(io.github.s24u.popupscale.CompatibilityStatus.reportState(115,116,2,2,0,20)=="stale_version")
        check(io.github.s24u.popupscale.CompatibilityStatus.reportState(116,116,1,2,0,20)=="previous_boot")
        check(io.github.s24u.popupscale.CompatibilityStatus.reportState(116,116,2,2,0,20)=="current")
        check(io.github.s24u.popupscale.CompatibilityStatus.reportState(116,116,-1,-1,30,20)=="previous_boot_or_unknown")
        println("PASS: old boot/version compatibility reports are never current.")
        fun qqCallbacks(version:Long=16750,pkg:String=QQDensityPolicy.PACKAGE,
            activity:String="com.tencent.mobileqq.activity.SplashActivity",active:Boolean=true,
            external:Boolean=true,user:Int=0,display:Int=0,type:Int=1,mode:Int=5,flags:Int=0x40000da4)=
            QQDensityPolicy.densityCallbacks(version,pkg,activity,active,external,user,display,type,mode,flags)
        check(qqCallbacks()==0x40001da4)
        check(qqCallbacks(activity="com.tencent.mobileqq.activity.ChatActivity",flags=0xda4)==0x1da4)
        check(qqCallbacks(flags=0x40001da4)==0x40001da4) // idempotent, preserves every native bit
        check(qqCallbacks(version=0)==0x40000da4)
        check(qqCallbacks(version=16368)==0x40001da4)
        check(qqCallbacks(version=15899)==0x40000da4)
        check(qqCallbacks(version=3351)==0x40000da4)
        check(qqCallbacks(pkg="other.app")==0x40000da4)
        check(qqCallbacks(activity="com.tencent.mobileqq.activity.QQSettingSettingActivity")==0x40000da4)
        check(qqCallbacks(active=false)==0x40000da4)
        check(qqCallbacks(external=false)==0x40000da4)
        check(qqCallbacks(user=10)==0x40000da4)
        check(qqCallbacks(display=1)==0x40000da4)
        check(qqCallbacks(type=2)==0x40000da4)
        for(mode in listOf(0,1,2,6))check(qqCallbacks(mode=mode)==0x40000da4)
        for(missing in listOf(0x80,0x400,0x800)) {
            val flags=0xda4 and missing.inv()
            check(qqCallbacks(flags=flags)==flags)
        }
        println("PASS: only verified QQ chat hosts with existing size/orientation handling receive the missing density callback; disabled/unknown/native/unrelated states remain unchanged.")
        check(QQPanelPolicy.height(true,16368,0,0,5,.58346665f,3.75f,3094,821)==1350)
        check(QQPanelPolicy.height(true,16750,0,0,5,.58346665f,3.75f,3094,821)==1350)
        check(QQPanelPolicy.height(true,16750,0,0,5,.4448f,3.6f,1800,684)==900)
        check(QQPanelPolicy.height(true,16750,0,0,5,.4448f,3.6f,1000,684)==684)
        check(QQPanelPolicy.height(true,16750,0,0,5,.4448f,3.6f,2600,1500)==1500)
        check(QQPanelPolicy.height(true,16750,0,0,5,.58346665f,3.75f,3094,1350)==1350)
        check(QQPanelPolicy.height(false,16750,0,0,5,.4448f,3.6f,2600,684)==684)
        check(QQPanelPolicy.height(true,16751,0,0,5,.4448f,3.6f,2600,684)==684)
        check(QQPanelPolicy.height(true,16750,10,0,5,.4448f,3.6f,2600,684)==684)
        check(QQPanelPolicy.height(true,16750,0,1,5,.4448f,3.6f,2600,684)==684)
        for(mode in listOf(1,2,6))
            check(QQPanelPolicy.height(true,16750,0,0,mode,.4448f,3.6f,2600,684)==684)
        check(QQPanelPolicy.height(true,16750,0,0,5,1f,3.6f,2600,684)==684)
        check(QQPanelPolicy.height(true,16750,0,0,5,Float.NaN,3.6f,2600,684)==684)
        check(QQPanelPolicy.height(true,16750,0,0,5,.4448f,Float.NaN,2600,684)==684)
        check(QQPanelPolicy.height(true,16750,0,0,5,.4448f,3.6f,0,684)==684)
        check(QQPanelPolicy.layoutWidth(1094,.58346665f)==1875)
        check(QQPanelPolicy.layoutWidth(834,.4448f)==1875)
        check(QQPanelPolicy.layoutWidth(1094,1f)==1094)
        check(QQPanelPolicy.layoutWidth(1094,Float.NaN)==1094)
        check(io.github.s24u.popupscale.ScalePolicy.captionOffset(true,5,1805,600,.58346665f,150,0,130)==74)
        check(io.github.s24u.popupscale.ScalePolicy.captionOffset(true,5,1805,600,.58346665f,60,0,130)==0)
        check(io.github.s24u.popupscale.ScalePolicy.captionOffset(true,5,1805,600,.58346665f,150,150,130)==0)
        check(io.github.s24u.popupscale.ScalePolicy.captionOffset(false,5,1805,600,.58346665f,150,0,130)==0)
        check(io.github.s24u.popupscale.ScalePolicy.captionOffset(true,1,3120,600,1f,150,0,130)==0)
        println("PASS: QQ panel virtual dimensions grow once, reserve message space, preserve larger native heights and exclude disabled/unknown/fullscreen/invalid cases.")
        check(QQDensityPolicy.windowChange(16750,true,0,600,600,350,600,5,false))
        check(QQDensityPolicy.windowChange(16750,true,0,600,600,303,350,5,true))
        check(QQDensityPolicy.windowChange(16750,true,0,600,600,600,350,1,true))
        check(!QQDensityPolicy.windowChange(16750,true,0,600,640,350,600,5,true))
        check(QQDensityPolicy.windowChange(16368,true,0,480,480,262,480,5,false))
        check(!QQDensityPolicy.windowChange(15899,true,0,600,600,350,600,5,true))
        check(!QQDensityPolicy.windowChange(16750,false,0,600,600,350,600,5,true))
        check(!QQDensityPolicy.windowChange(16750,true,10,600,600,350,600,5,true))
        check(!QQDensityPolicy.windowChange(16750,true,0,600,600,350,350,5,true))
        check(!QQDensityPolicy.windowChange(16750,true,0,600,600,600,350,1,false))
        check(!QQDensityPolicy.windowChange(16750,true,0,600,600,350,600,6,true))
        check(!QQDensityPolicy.windowChange(16750,true,0,0,0,350,600,5,true))
        check(!QQDensityPolicy.windowChange(16750,true,0,600,600,0,600,5,true))
        check(!QQDensityPolicy.windowChange(16750,true,0,600,600,350,600,5,true,1))
        println("PASS: QQ window-only density transitions avoid display restart; real display changes, other versions/users/modes and disabled scaling retain native behavior.")
        val legacyQq=checkNotNull(QQDensityPolicy.restartTarget("com.tencent.mobileqq",3350))
        val tim=checkNotNull(QQDensityPolicy.restartTarget("com.tencent.tim",4050))
        check(legacyQq.manager=="com.tencent.mobileqq.app.utils.f"&&!legacyQq.contextArgument)
        check(tim.contextArgument&&QQDensityPolicy.restartTarget("com.tencent.tim",16750)==null)
        for(target in listOf(legacyQq,tim)){
            check(QQDensityPolicy.densityCallbacks(target.version,target.pkg,
                "com.tencent.mobileqq.activity.SplashActivity",true,true,0,0,1,5,0xda4)==0x1da4)
            check(QQDensityPolicy.densityCallbacks(target.version,target.pkg,
                "com.tencent.mobileqq.activity.LoginActivity",true,true,0,0,1,5,0xa0)==0x1da0)
            check(QQDensityPolicy.densityCallbacks(target.version,target.pkg,
                "com.tencent.mobileqq.activity.LoginActivity",false,true,0,0,1,5,0xa0)==0xa0)
            check(QQDensityPolicy.densityCallbacks(target.version,target.pkg,
                "com.tencent.mobileqq.activity.LoginActivity",true,true,0,0,1,5,0x20)==0x20)
            check(QQDensityPolicy.windowChange(target,target.version,true,0,600,600,350,600,5,false,0))
            check(QQDensityPolicy.windowChange(target,target.version,true,0,600,600,600,350,5,false,0))
            check(QQDensityPolicy.windowChange(target,target.version,true,0,600,600,600,350,1,true,0))
            check(!QQDensityPolicy.windowChange(target,target.version,true,0,600,640,350,600,5,true,0))
            check(!QQDensityPolicy.windowChange(target,target.version+1,true,0,600,600,350,600,5,true,0))
            check(!QQDensityPolicy.windowChange(target,target.version,false,0,600,600,350,600,5,true,0))
            check(!QQDensityPolicy.windowChange(target,target.version,true,10,600,600,350,600,5,true,0))
            check(!QQDensityPolicy.windowChange(target,target.version,true,0,600,600,350,600,5,true,1))
            check(!QQDensityPolicy.windowChange(target,target.version,true,0,600,600,350,350,5,true,0))
            check(!QQDensityPolicy.windowChange(target,target.version,true,0,600,600,600,350,1,false,0))
            check(!QQDensityPolicy.windowChange(target,target.version,true,0,600,600,350,600,6,true,0))
        }
        println("PASS: verified legacy QQ and TIM entries cover window-DPI restoration; real display changes and unsupported versions/users/modes remain native.")
        check(io.github.s24u.popupscale.AppCanvasPolicy.usesCanvas("com.tencent.mobileqq",true))
        check(io.github.s24u.popupscale.AppCanvasPolicy.requiresConsistentDensity("com.tencent.mobileqq"))
        check(io.github.s24u.popupscale.AppCanvasPolicy.requiresConsistentDensity("com.tencent.tim"))
        check(io.github.s24u.popupscale.AppCanvasPolicy.ownsCanvas("com.tencent.tim",false,false))
        check(!io.github.s24u.popupscale.AppCanvasPolicy.ownsCanvas("com.tencent.tim",true,false))
        check(io.github.s24u.popupscale.AppCanvasPolicy.rewriteActivityResources("com.tencent.mobileqq",true))
        check(io.github.s24u.popupscale.AppCanvasPolicy.processDensity("com.tencent.mobileqq",true,false,true,0,350,600)==600)
        check(io.github.s24u.popupscale.AppCanvasPolicy.processDensity("com.tencent.mobileqq",true,true,true,0,350,600)==0)
        check(io.github.s24u.popupscale.AppCanvasPolicy.processDensity("com.tencent.mobileqq",false,false,true,0,350,600)==0)
        check(io.github.s24u.popupscale.AppCanvasPolicy.processDensity("com.tencent.mobileqq",true,false,false,0,350,600)==0)
        check(io.github.s24u.popupscale.AppCanvasPolicy.processDensity("com.tencent.mobileqq",true,false,true,10,350,600)==0)
        check(!io.github.s24u.popupscale.AppCanvasPolicy.ownsCanvas("com.tencent.mobileqq",true,false))
        val flip6Wechat=checkNotNull(io.github.s24u.popupscale.ExternalScalePolicy.calculate(
            "com.tencent.mm",true,true,false,true,0,0,1,5,942,1637,241,500))
        check(flip6Wechat.densityDpi==301&&flip6Wechat.widthDp in 499..501&&flip6Wechat.widthDp<600)
        val flip6Narrow=checkNotNull(io.github.s24u.popupscale.ExternalScalePolicy.calculate(
            "com.tencent.mm",true,true,false,true,0,0,1,5,831,1131,241,500))
        check(flip6Narrow.densityDpi==266&&flip6Narrow.widthDp==500&&flip6Narrow.heightDp==680)
        check(io.github.s24u.popupscale.WechatScalePolicy.logicalHeightDp(942,1637,500,1087)==869)
        check(io.github.s24u.popupscale.WechatScalePolicy.logicalHeightDp(831,1131,500,751)==681)
        check(io.github.s24u.popupscale.WechatScalePolicy.logicalHeightDp(0,1637,500,1087)==1087)
        val s23uWechat=checkNotNull(io.github.s24u.popupscale.ExternalScalePolicy.calculate(
            "com.tencent.mm",true,true,false,true,0,0,1,5,1094,1805,600,500))
        check(s23uWechat.densityDpi==350&&s23uWechat.widthDp in 499..501)
        val s23uShrunk=checkNotNull(io.github.s24u.popupscale.ExternalScalePolicy.calculate(
            "com.tencent.mm",true,true,false,true,0,0,1,5,825,1361,600,500))
        check(s23uShrunk.densityDpi==264&&s23uShrunk.widthDp in 499..501)
        check(io.github.s24u.popupscale.WechatScalePolicy.logicalHeightDp(1094,1805,500,825)==825)
        check(io.github.s24u.popupscale.WechatScalePolicy.logicalHeightDp(825,1361,500,825)==825)
        check(io.github.s24u.popupscale.WechatScalePolicy.isLauncherUI("com.tencent.mm.ui.LauncherUI"))
        check(!io.github.s24u.popupscale.WechatScalePolicy.isLauncherUI("com.tencent.mm.ui.chatting.ChattingUI"))
        check(io.github.s24u.popupscale.WechatScalePolicy.shouldScaleDialog(5, true, false))
        check(!io.github.s24u.popupscale.WechatScalePolicy.shouldScaleDialog(1, true, false))
        check(!io.github.s24u.popupscale.WechatScalePolicy.shouldScaleDialog(5, false, false))
        check(!io.github.s24u.popupscale.WechatScalePolicy.shouldScaleDialog(5, true, true))
        check(MiniPlaybackPolicy.eligible("com.ss.android.ugc.aweme",true,true,true,5,false,false))
        check(!MiniPlaybackPolicy.eligible("tv.danmaku.bili",true,true,true,5,false,false))
        check(!MiniPlaybackPolicy.eligible("com.ss.android.ugc.aweme",false,true,true,5,false,false))
        check(!MiniPlaybackPolicy.eligible("com.ss.android.ugc.aweme",true,false,true,5,false,false))
        check(!MiniPlaybackPolicy.eligible("com.ss.android.ugc.aweme",true,true,false,5,false,false))
        check(!MiniPlaybackPolicy.eligible("com.ss.android.ugc.aweme",true,true,true,1,false,false))
        check(!MiniPlaybackPolicy.eligible("com.ss.android.ugc.aweme",true,true,true,5,true,false))
        check(!MiniPlaybackPolicy.eligible("com.ss.android.ugc.aweme",true,true,true,5,false,true))
        check(MiniPlaybackPolicy.trusted(10042,10042,112,112,2000,1900))
        check(!MiniPlaybackPolicy.trusted(2000,10042,112,112,2000,1900))
        check(!MiniPlaybackPolicy.trusted(10042,10042,111,112,2000,1900))
        check(!MiniPlaybackPolicy.trusted(10042,10042,112,112,2000,2001))
        check(!MiniPlaybackPolicy.trusted(10042,10042,112,112,3000,1499))
        check(MiniPlaybackPolicy.eligible("com.ss.android.yumme.video",false,true,true,true,5,false,false))
        check(!MiniPlaybackPolicy.eligible("com.ss.android.yumme.video",true,false,true,true,5,false,false))
        check(MiniPlaybackPolicy.needsAppObserver("com.ss.android.ugc.aweme"))
        check(!MiniPlaybackPolicy.needsAppObserver("com.ss.android.yumme.video"))
        check(MiniPlaybackPolicy.isObserverUnavailableReason("timeout-check-app-scope-and-restart"))
        check(!MiniPlaybackPolicy.isObserverUnavailableReason("not-playing"))
        check(MiniPlaybackPolicy.allowWithoutObserver(true,true))
        check(!MiniPlaybackPolicy.allowWithoutObserver(false,true))
        check(!MiniPlaybackPolicy.allowWithoutObserver(true,false))
        check(io.github.s24u.popupscale.SettingsDefaults.flag("mini_keep_playback",false))
        check(!io.github.s24u.popupscale.SettingsDefaults.flag("mini_keep_playback_all",true))
        println("PASS: mini playback requires selected visible owned freeform, excludes dock/lock/other apps, and authenticates UID/version/age.")
        val playback=MiniPlaybackSnapshot()
        playback.created(81,"Main")
        check(!playback.decision(81,"Main",1000).allowed)
        playback.foreground(81,"Main")
        playback.capture(81,"Main",true,"feed",1000)
        check(playback.decision(81,"Main",1100).allowed)
        playback.created(81,"Main") // Same task/Activity recreated for density: don't overwrite.
        playback.capture(81,"Main",false,"feed",1200)
        check(playback.decision(81,"Main",1300).allowed)
        playback.foreground(81,"Main") // A new actual foreground visit permits manual pause.
        playback.capture(81,"Main",false,"feed",1500)
        check(playback.decision(81,"Main",1600).reason=="not-playing")
        check(!playback.decision(81,"Main",1600).allowed)
        playback.created(81,"Live") // Main feed state cannot authorize a different Activity.
        check(!playback.decision(81,"Live",1700).allowed)
        playback.capture(81,"Live",true,"live-visible",1800)
        check(playback.decision(81,"Live",2000).allowed)
        check(!playback.decision(82,"Live",2000).allowed)
        check(!playback.decision(81,"Live",1799).allowed)
        check(!playback.decision(81,"Live",16801).allowed)
        playback.foreground(81,"Live");playback.capture(81,"Live",null,"unavailable",2000)
        check(playback.decision(81,"Live",2100).reason=="state-unknown")
        playback.forget(81);check(playback.decision(81,"Live",2200).reason=="no-snapshot")
        println("PASS: feed/live pre-pause snapshots preserve manual pause, survive same-component recreation, and reject unknown/stale/wrong-task/Activity states.")
        val resizeA=SyncResizeRequest(1000)
        val resizeB=SyncResizeRequest(1000)
        check(resizeA.next(1000,false,true)==SyncResizeRequest.Step.SUBMIT)
        // A's draw owns the native queue. B must not attach its callback to A's transaction.
        check(resizeB.next(1001,true,true)==SyncResizeRequest.Step.WAIT)
        check(resizeB.next(1017,true,true)==SyncResizeRequest.Step.WAIT)
        check(resizeA.finish()&&!resizeA.finish())
        check(resizeB.next(1033,false,true)==SyncResizeRequest.Step.SUBMIT)
        check(resizeB.next(1034,false,true)==SyncResizeRequest.Step.IGNORE)
        check(resizeB.finish()&&!resizeB.finish())
        val rotatedAgain=SyncResizeRequest(2000)
        check(rotatedAgain.next(2001,true,true)==SyncResizeRequest.Step.WAIT)
        check(rotatedAgain.next(2017,false,false)==SyncResizeRequest.Step.CANCEL)
        check(rotatedAgain.finish())
        check(rotatedAgain.next(2033,false,true)==SyncResizeRequest.Step.IGNORE)
        val timedOut=SyncResizeRequest(3000)
        check(timedOut.next(5999,true,true)==SyncResizeRequest.Step.WAIT)
        check(timedOut.next(6000,true,true)==SyncResizeRequest.Step.TIMEOUT)
        check(timedOut.finish()&&!timedOut.finish())
        val vanished=SyncResizeRequest(4000)
        check(vanished.finish())
        check(vanished.next(4001,false,true)==SyncResizeRequest.Step.IGNORE)
        println("PASS: concurrent mini rotation resizes wait for their own sync draw; stale geometry, cancellation, deadlines and duplicate callbacks cannot submit twice.")
        check(CompatibilityPolicy.readiness(1000,990,true,107,107)==21000L)
        check(CompatibilityPolicy.readiness(1000,990,false,107,107)==0L)
        check(CompatibilityPolicy.readiness(1000,990,true,106,107)==null)
        check(CompatibilityPolicy.readiness(1000,1001,true,107,107)==null)
        check(CompatibilityPolicy.readiness(1000,-1,true,107,107)==null)
        check(CompatibilityPolicy.readiness(5000,1000,true,107,107)==null)
        check(CompatibilityPolicy.platform(35,"samsung","Samsung"))
        check(CompatibilityPolicy.platform(36,"SAMSUNG","unknown"))
        check(CompatibilityPolicy.platform(37,"unknown","samsung"))
        check(!CompatibilityPolicy.platform(34,"samsung","samsung"))
        check(!CompatibilityPolicy.platform(38,"samsung","samsung"))
        check(!CompatibilityPolicy.platform(35,"google","google"))
        check(CompatibilityPolicy.dragCoordinates(arrayOf(1,2,3,100f,200f))==(100f to 200f))
        check(CompatibilityPolicy.dragCoordinates(arrayOf(1,100f,200f))==(100f to 200f))
        check(CompatibilityPolicy.dragCoordinates(arrayOf(1,100.0,200f))==null)
        check(CompatibilityPolicy.dragCoordinates(arrayOf(1,Float.NaN,200f))==null)
        val reflect=CompatibilityProbe({name->when(name){
            "child"->CompatibilityChildFixture::class.java
            "broken"->throw NoClassDefFoundError("missing optional ROM type")
            else->null
        }})
        check(reflect.field("child","oldField","caption"))
        check(reflect.field("child","displayId","deviceState"))
        check(listOf(listOf("int","boolean"),listOf("int","int","boolean")).firstNotNullOfOrNull{reflect.exact("child","minimizeAllTasksLocked",it,"void")}!=null)
        check(!reflect.field("child","absent"))
        check(reflect.method("child","configure",listOf("int","boolean"),"boolean"))
        check(!reflect.method("child","configure",listOf("int"),"boolean"))
        check(!reflect.method("child","configure",listOf("int","boolean"),"int"))
        check(reflect.first(listOf("missing","broken","child")){reflect.field(it,"caption")}=="child")
        check(!reflect.method("broken","configure"))
        val coldName=CompatibilityColdFixture::class.java.name
        val cold=CompatibilityProbe({name->Class.forName(name,false,CompatibilityColdFixture::class.java.classLoader)})
        check(cold.type(coldName)!=null&&!CompatibilityInitCounter.initialized)
        println("PASS: Samsung15/16/17 device admission is model-independent; exact overload/return/inherited-field probes, missing classes, noninitializing lookup and old/new drag descriptors are isolated.")
        val separateDocks=MiniStackPolicy.Order()
        check(!MiniStackPolicy.participates(795,true,false)) // first docked icon
        check(!MiniStackPolicy.participates(796,true,false)) // second docking/settled icon
        check(!MiniStackPolicy.participates(-1,false,false))
        check(!MiniStackPolicy.participates(795,false,true)) // restore/close cannot rejoin
        check(MiniStackPolicy.participates(797,false,false))
        separateDocks.add(listOf(796,795)) // even an old/unfiltered enrollment is reconciled away
        check(separateDocks.reconcile(emptyList())&&separateDocks.ids().isEmpty())
        check(separateDocks.reconcile(listOf(797))&&separateDocks.ids()==listOf(797))
        check(!separateDocks.reconcile(listOf(797))) // adding a dock does not touch the ordinary mini
        separateDocks.reconcile(listOf(798,797))
        check(separateDocks.ids()==listOf(798,797))
        separateDocks.switch(1)
        check(separateDocks.ids()==listOf(797,798))
        check(!separateDocks.reconcile(listOf(798,797))) // refresh preserves the user's card selection
        separateDocks.reconcile(listOf(798)) // 797 docked or restored; 798 stays an ordinary mini
        check(separateDocks.ids()==listOf(798))
        separateDocks.reconcile(listOf(797,798)) // explicitly revealing a dock as a mini can join again
        check(separateDocks.ids()==listOf(797,798))
        println("PASS: side-docked icons never join card membership/count/order; ordinary minis still join, switch, leave and explicitly rejoin without disturbing selection.")
        val following=HandleFollowState()
        check(HandleFollowState.focused(790,true,null)&&!HandleFollowState.focused(790,false,null))
        check(HandleFollowState.focused(791,false,791)) // focus transition can precede the TaskInfo flag
        check(!HandleFollowState.focused(790,true,791)) // old focused flag cannot revive another task's bar
        check(!HandleFollowState.focused(791,true,-1))
        val leftWindow=LivePlacement.Bounds(200,100,834,1209)
        val rightWindow=leftWindow.copy(x=1700)
        val drag790=following.begin(790,leftWindow)
        val drag791=following.begin(791,rightWindow)
        repeat(60){check(following.offer(drag790,leftWindow.copy(x=200+it)))}
        check(following.offer(drag791,rightWindow.copy(y=180)))
        val followFrame=following.drain().associateBy{it.session.task}
        check(followFrame.size==2&&followFrame.getValue(790).bounds.x==259)
        check(followFrame.getValue(791).bounds==rightWindow.copy(y=180))
        check(following.positionOnly(790)&&following.positionOnly(791))
        check(following.stats(drag790)==HandleFollowState.Stats(60,1)) // one latest layout per display frame
        check(following.drain().isEmpty())
        check(!following.acknowledge(790,leftWindow)) // watchdog cannot replace an active preview
        val final790=leftWindow.copy(x=500)
        check(following.offer(drag790,leftWindow.copy(x=480)))
        check(following.end(drag790,final790))
        check(!following.offer(drag790,leftWindow)) // queued MOVE arriving after END is stale
        check(following.drain().isEmpty()&&following.current(790)==final790)
        check(!following.acknowledge(790,leftWindow)) // old TaskInfo cannot snap the handle back
        check(following.acknowledge(790,final790)&&!following.contains(790))
        check(following.current(791)==rightWindow.copy(y=180)) // other task is independent
        val old790=following.begin(790,final790)
        check(following.end(old790,final790.copy(y=140)))
        val new790=following.begin(790,final790)
        check(!following.finish(old790)&&!following.end(old790,leftWindow))
        following.discard(old790)
        check(following.matches(new790)) // delayed cleanup from previous drag cannot clear a new drag
        check(!following.offer(old790,leftWindow))
        check(following.offer(new790,final790.copy(x=650)))
        check(following.end(new790,null,cancelled=true)&&following.current(790)==final790)
        check(following.finish(new790)&&!following.contains(790)) // bounded no-ack fallback
        following.forget(791)
        check(!following.offer(drag791,rightWindow)&&following.drain().isEmpty()) // vanished task never revives
        val cornerResize=following.begin(790,leftWindow)
        check(following.offer(cornerResize,leftWindow.copy(width=900)))
        check(!following.positionOnly(790)) // size changes still require native surface settlement
        following.forget(790)
        println("PASS: caption samples coalesce per frame and task; focus-switch drags, stale MOVE/END/timeouts, final TaskInfo acknowledgement, cancellation and task removal stay isolated.")
        val homeTask=setOf(779)
        val unrelatedHome=listOf(HomeTransitionPolicy.Transition(setOf(100,101)))
        check(HomeTransitionPolicy.decide(homeTask,unrelatedHome,10,2000)==HomeTransitionPolicy.Decision.START)
        check(HomeTransitionPolicy.decide(homeTask,emptyList(),10,2000)==HomeTransitionPolicy.Decision.START)
        check(HomeTransitionPolicy.decide(homeTask,listOf(HomeTransitionPolicy.Transition(setOf(779))),10,2000)==HomeTransitionPolicy.Decision.WAIT)
        check(HomeTransitionPolicy.decide(setOf(779,780),listOf(HomeTransitionPolicy.Transition(setOf(780))),10,2000)==HomeTransitionPolicy.Decision.WAIT)
        check(HomeTransitionPolicy.decide(homeTask,unrelatedHome+HomeTransitionPolicy.Transition(emptySet(),uncertain=true),10,2000)==HomeTransitionPolicy.Decision.WAIT)
        check(HomeTransitionPolicy.decide(homeTask,listOf(HomeTransitionPolicy.Transition(setOf(100),displayChange=true)),10,2000)==HomeTransitionPolicy.Decision.WAIT)
        check(HomeTransitionPolicy.decide(homeTask,unrelatedHome,2000,2000)==HomeTransitionPolicy.Decision.EXPIRED)
        check(HomeTransitionPolicy.decide(emptySet(),unrelatedHome,10,2000)==HomeTransitionPolicy.Decision.EXPIRED)
        // Pending HOME becomes known, unrelated HOME need not finish; a target transition must finish.
        val pending=listOf(HomeTransitionPolicy.Transition(emptySet(),uncertain=true))
        check(HomeTransitionPolicy.decide(homeTask,pending,10,2000)==HomeTransitionPolicy.Decision.WAIT)
        check(HomeTransitionPolicy.decide(homeTask,unrelatedHome,42,2000)==HomeTransitionPolicy.Decision.START)
        check(HomeTransitionPolicy.decide(homeTask,unrelatedHome+HomeTransitionPolicy.Transition(homeTask),42,2000)==HomeTransitionPolicy.Decision.WAIT)
        println("PASS: HOME popup animation runs alongside unrelated tasks; pending, target, rotation and expired transitions remain guarded.")
        check(HomeTransitionPolicy.preservePopup(true,"recents",false,0,0,1,5,6,1,true,true))
        check(HomeTransitionPolicy.preservePopup(true,"default",true,0,0,1,5,6,2,true,true))
        // Captured #27 -> mini settles -> #28 standalone force-show: only the still-owned
        // popup skips the second animation. A restored popup must regain native animation.
        check(!HomeTransitionPolicy.preservePopup(true,"default",false,0,0,1,5,6,2,true,true))
        check(!HomeTransitionPolicy.preservePopup(true,"default",true,0,0,1,5,6,2,false,true))
        check(!HomeTransitionPolicy.preservePopup(true,"default",true,0,0,1,5,6,2,true,false))
        check(!HomeTransitionPolicy.preservePopup(true,"default",true,0,0,1,5,6,3,true,true))
        check(!HomeTransitionPolicy.preservePopup(false,"recents",false,0,0,1,5,6,1,true,true))
        check(!HomeTransitionPolicy.preservePopup(true,"default",false,0,0,1,5,6,1,true,true))
        check(!HomeTransitionPolicy.preservePopup(true,"recents",false,10,0,1,5,6,1,true,true))
        check(!HomeTransitionPolicy.preservePopup(true,"recents",false,0,1,1,5,6,1,true,true))
        check(!HomeTransitionPolicy.preservePopup(true,"recents",false,0,0,2,5,6,1,true,true))
        check(!HomeTransitionPolicy.preservePopup(true,"recents",false,0,0,1,1,6,1,true,true))
        check(!HomeTransitionPolicy.preservePopup(true,"recents",false,0,0,1,5,1,1,true,true))
        check(!HomeTransitionPolicy.preservePopup(true,"recents",false,0,0,1,5,6,0,true,true))
        check(!HomeTransitionPolicy.preservePopup(true,"recents",false,0,0,1,5,6,1,false,true))
        check(!HomeTransitionPolicy.preservePopup(true,"recents",false,0,0,1,5,6,1,true,false))
        println("PASS: only unchanged selected freeform Recents/owned-mini force-hide changes skip the competing animator; actual resize/rotation/open/fullscreen and other users/displays stay native.")
        check(!FrameworkLayoutPolicy.popupEntryRequest(true,5,0,true)) // BACK/new Activity in manually resized popup
        check(FrameworkLayoutPolicy.popupEntryRequest(true,5,5,true)) // explicit new popup request
        check(FrameworkLayoutPolicy.popupEntryRequest(true,1,0,true)) // fullscreen -> freeform
        check(FrameworkLayoutPolicy.popupEntryRequest(false,0,0,false))
        check(FrameworkLayoutPolicy.popupEntryRequest(true,5,0,false)) // malformed remembered landscape shape
        val defaultEntry=LivePlacement.Bounds(1035,180,360,594)
        val movedDeck=LivePlacement.Bounds(120,900,360,594)
        check(MiniStackPolicy.entryTarget(defaultEntry,movedDeck,1440,3120)==defaultEntry.copy(x=120,y=900))
        check(MiniStackPolicy.entryTarget(defaultEntry,null,1440,3120)==defaultEntry)
        val wideEntry=LivePlacement.Bounds(1035,180,360,203)
        check(MiniStackPolicy.entryTarget(wideEntry,movedDeck,1440,3120)==wideEntry.copy(x=120,y=900))
        val clampedEntry=MiniStackPolicy.entryTarget(defaultEntry,movedDeck.copy(x=1400,y=3100),1440,3120)
        check(clampedEntry.x+clampedEntry.width==1440&&clampedEntry.y+clampedEntry.height==3120)
        val cachedCards=listOf(
            MiniStackPolicy.Frame(1,movedDeck,1f,true,10),
            MiniStackPolicy.Frame(2,movedDeck.copy(x=83,y=934,width=340,height=562),1f,false,9),
            MiniStackPolicy.Frame(3,movedDeck.copy(x=45,y=968,width=320,height=529),0f,false,8))
        val surfaceCache=MiniStackPolicy.FrameCache()
        check(surfaceCache.changes(cachedCards).size==3)
        check(surfaceCache.changes(cachedCards).size==3) // an uncommitted/failed transaction must retry
        surfaceCache.committed(cachedCards)
        check(surfaceCache.changes(cachedCards).isEmpty())
        val previewCards=cachedCards.map{if(it.front)it.copy(bounds=it.bounds.copy(x=170)) else it}
        check(surfaceCache.changes(previewCards).map{it.task}==listOf(1)) // static/hidden back cards do no work
        surfaceCache.committed(previewCards)
        check(surfaceCache.changes(previewCards).isEmpty())
        check(surfaceCache.changes(previewCards.map{if(it.task==2)it.copy(layer=11) else it}).single().task==2)
        check(surfaceCache.changes(previewCards.map{if(it.task==3)it.copy(alpha=1f) else it}).single().task==3)
        surfaceCache.invalidate(2);check(surfaceCache.changes(previewCards).single().task==2)
        println("PASS: new mini targets moved deck directly with source aspect and screen fitting; unchanged surface frames skip work while layers/alpha/reflow and failed commits remain visible.")
        val nativeCardLayers=mapOf<Int,Int?>(665 to 5,666 to 6,667 to 7)
        val layersBefore=MiniStackPolicy.layers(listOf(667,666,665),nativeCardLayers)
        val layersAfter=MiniStackPolicy.layers(listOf(666,665,667),nativeCardLayers)
        check(layersBefore.getValue(667)>layersBefore.getValue(666))
        check(layersAfter.getValue(666)>layersAfter.getValue(667)) // actual visible surface follows selection
        check(layersAfter.values.toSet().size==3&&layersAfter.values.min()>nativeCardLayers.values.filterNotNull().max())
        check(nativeCardLayers[665]==5&&nativeCardLayers[666]==6&&nativeCardLayers[667]==7) // restore baseline is untouched
        check(MiniStackPolicy.layers(listOf(665,666),mapOf(665 to 5,666 to null)).isEmpty()) // no guessed native layer
        println("PASS: selected stack surface is front despite fixed native task layers; original layer baseline remains available for restore.")
        // A short, deliberate swipe that crossed slop at 300ms was misclassified as drag.
        val shortSwipe=MiniStackPolicy.Gesture(100f,100f,1000,30,500)
        check(shortSwipe.move(140f,106f,1300)==MiniStackPolicy.Motion.SWIPE)
        check(shortSwipe.move(182.5f,108f,1420)==MiniStackPolicy.Motion.SWIPE)
        check(MiniStackPolicy.swipe(82.5f,8f,0f,3.75f,360)==-1) // 22dp, no fast-fling requirement
        val leftSwipe=MiniStackPolicy.Gesture(200f,100f,0,30,500)
        check(leftSwipe.move(120f,104f,240)==MiniStackPolicy.Motion.SWIPE) // DOWN followed directly by UP
        check(MiniStackPolicy.swipe(-80f,4f,0f,3.75f,360)==1)
        val held=MiniStackPolicy.Gesture(100f,100f,0,30,500)
        check(held.move(140f,104f,600)==MiniStackPolicy.Motion.DRAG)
        val vertical=MiniStackPolicy.Gesture(100f,100f,0,30,500)
        check(vertical.move(105f,150f,100)==MiniStackPolicy.Motion.DRAG)
        check(vertical.move(200f,155f,200)==MiniStackPolicy.Motion.DRAG) // locked gesture cannot switch midway
        val tapCard=MiniStackPolicy.Gesture(100f,100f,0,30,500)
        check(tapCard.move(109f,108f,200)==MiniStackPolicy.Motion.TAP)
        check(MiniStackPolicy.swipe(40f,3f,0f,3.75f,360)==0) // under threshold returns to the captured slot
        println("PASS: stack short swipe at300ms/22dp, sparse UP events, both directions, deliberate hold, vertical lock and tap isolation.")
        val stackOrder=MiniStackPolicy.Order()
        stackOrder.add(listOf(3,4,3,-1));check(stackOrder.ids()==listOf(3,4))
        stackOrder.add(listOf(5,3));check(stackOrder.ids()==listOf(5,3,4))
        stackOrder.switch(1);check(stackOrder.ids()==listOf(3,4,5))
        stackOrder.switch(-1);check(stackOrder.ids()==listOf(5,3,4))
        stackOrder.remove(5);stackOrder.retain(setOf(4));check(stackOrder.ids()==listOf(4))
        stackOrder.switch(1);check(stackOrder.ids()==listOf(4))
        val sizes=mapOf(1 to MiniStackPolicy.Size(360,594),2 to MiniStackPolicy.Size(360,203),
            3 to MiniStackPolicy.Size(360,594),4 to MiniStackPolicy.Size(360,594))
        for((sw,sh) in listOf(1440 to 3120,3120 to 1440))for((x,y) in listOf(-100 to -100,0 to 0,sw to sh)){
            val frames=MiniStackPolicy.layout(listOf(1,2,3,4),sizes,x,y,sw,sh,3.75f)
            check(frames.size==4&&frames.count{it.front}==1&&frames.first().front)
            check(frames.last().alpha==0f&&frames.take(3).all{it.alpha==1f})
            for(frame in frames){
                val b=frame.bounds;val source=sizes.getValue(frame.task)
                check(b.x>=0&&b.y>=0&&b.x+b.width<=sw&&b.y+b.height<=sh)
                check(kotlin.math.abs(b.width.toFloat()/b.height-source.width.toFloat()/source.height)<.02f)
            }
        }
        check(MiniStackPolicy.swipe(-160f,5f,-1800f,3.75f)==1)
        check(MiniStackPolicy.swipe(160f,5f,1800f,3.75f)==-1)
        check(MiniStackPolicy.swipe(80f,5f,3000f,3.75f)==-1)
        check(MiniStackPolicy.swipe(8f,5f,3000f,3.75f)==0)
        check(MiniStackPolicy.swipe(160f,220f,3000f,3.75f)==0)
        check(MiniStackPolicy.swipe(160f,5f,3000f,Float.NaN)==0)
        fun homeMini(enabled:Boolean=true,observe:Boolean=false,live:Boolean=true,hang:Boolean=true,
                     selected:Boolean=true,user:Int=0,currentUser:Int=0,display:Int=0,type:Int=1,mode:Int=5,
                     visible:Boolean=true,unlocked:Boolean=true)=MiniLeasePolicy.homeEligible(enabled,observe,live,hang,
                         selected,user,currentUser,display,type,mode,visible,unlocked)
        check(homeMini())
        check(!homeMini(enabled=false));check(!homeMini(observe=true));check(!homeMini(live=false))
        check(!homeMini(hang=false));check(!homeMini(selected=false));check(!homeMini(user=10))
        check(!homeMini(currentUser=10));check(!homeMini(display=1));check(!homeMini(type=2))
        check(!homeMini(mode=1));check(!homeMini(visible=false));check(!homeMini(unlocked=false))
        println("PASS: mini stack order/round-trip switching/removal, portrait-wide card geometry, bounds/hidden-card isolation and HOME eligibility.")
        check(FrameworkLayoutPolicy.launchMode(1,5,5)==1) // native fullscreen result wins over stale freeform
        check(FrameworkLayoutPolicy.launchMode(0,1,5)==1) // explicit maximize must stay fullscreen
        check(FrameworkLayoutPolicy.launchMode(5,0,1)==5)
        check(FrameworkLayoutPolicy.launchMode(0,5,1)==5)
        check(FrameworkLayoutPolicy.launchMode(0,0,5)==5)
        fun firstFrame(enabled:Boolean=true,observe:Boolean=false,handles:Boolean=true,selected:Boolean=true,
                       user:Int=0,display:Int=0,type:Int=1,mode:Int=5,width:Int=1440,height:Int=3120,
                       newEntry:Boolean=true,wide:Boolean=false,owned:Boolean=false)=
            FrameworkLayoutPolicy.portraitEntry(enabled,observe,handles,selected,user,display,type,mode,
                width,height,newEntry,wide,owned)
        check(firstFrame())
        check(!firstFrame(enabled=false));check(!firstFrame(observe=true))
        check(!firstFrame(handles=false));check(!firstFrame(selected=false))
        check(!firstFrame(user=10));check(!firstFrame(display=1))
        for(type in listOf(0,2,3))check(!firstFrame(type=type))
        for(mode in listOf(0,1,2,6))check(!firstFrame(mode=mode))
        check(!firstFrame(width=3120,height=1440));check(!firstFrame(width=0))
        check(!firstFrame(newEntry=false));check(!firstFrame(wide=true));check(!firstFrame(owned=true))
        println("PASS: native portrait first-frame layout only applies to new eligible popups; fullscreen/manual/mini/wide and other users/displays stay native.")
        val portraitMini=LivePlacement.miniTransform(LivePlacement.Bounds(173,657,1094,1805),173,657,
            LivePlacement.Bounds(1035,180,360,594))
        check(portraitMini.cropWidth==1094&&portraitMini.cropHeight==1805)
        check(portraitMini.x==1035f&&portraitMini.y==180f)
        check(kotlin.math.abs(portraitMini.scale-360f/1094)<0.00001f)
        val landscapeMini=LivePlacement.miniTransform(LivePlacement.Bounds(1143,116,834,1209),1133,96,
            LivePlacement.Bounds(2745,45,318,460))
        check(landscapeMini.cropWidth==834&&landscapeMini.cropHeight==1209)
        check(landscapeMini.x==2735f&&landscapeMini.y==25f)
        check(kotlin.math.abs(landscapeMini.scale-318f/834)<0.00001f)
        check(portraitMini.cropHeight!=landscapeMini.cropHeight)
        val stdMini=checkNotNull(LivePlacement.hangTarget(1440,3120,3.75f,true))
        val tallPlace=checkNotNull(LivePlacement.miniPlace(1440,3120,825,2057,3.75f))
        val shortPlace=checkNotNull(LivePlacement.miniPlace(1440,3120,1139,1225,3.75f))
        val widePlace=checkNotNull(LivePlacement.miniPlace(1440,3120,1200,675,3.75f))
        check(tallPlace.width==stdMini.width&&tallPlace.height==stdMini.height)
        check(shortPlace.width==stdMini.width&&shortPlace.height==stdMini.height)
        check(widePlace.width>widePlace.height)
        val tallTx=LivePlacement.miniTransform(LivePlacement.Bounds(173,657,825,2057),173,657,
            LivePlacement.Bounds(1035,180,360,594))
        check(tallTx.cropLeft==0&&tallTx.cropTop==0&&tallTx.cropWidth==825&&tallTx.cropHeight<2057)
        check(kotlin.math.abs(tallTx.cropHeight*tallTx.scale-594f)<=1.5f)
        val shortTx=LivePlacement.miniTransform(LivePlacement.Bounds(173,657,1139,1225),173,657,
            LivePlacement.Bounds(1035,180,360,594))
        check(shortTx.cropTop==0&&shortTx.cropHeight==1225&&shortTx.cropLeft>0&&shortTx.cropWidth<1139)
        check(kotlin.math.abs((shortTx.x+shortTx.cropLeft*shortTx.scale)-1035f)<=1.5f)
        check(kotlin.math.abs(shortTx.cropWidth*shortTx.scale-360f)<=1.5f)
        val tallFrameless=LivePlacement.miniTransform(LivePlacement.Bounds(173,657,825,1987),173,657,
            LivePlacement.Bounds(1035,180,360,594),60)
        check(tallFrameless.cropLeft==0&&tallFrameless.cropTop==60&&tallFrameless.cropRight==825&&tallFrameless.cropBottom==1421)
        check(kotlin.math.abs((tallFrameless.y+tallFrameless.cropTop*tallFrameless.scale)-180f)<=0.5f)
        check(kotlin.math.abs(tallFrameless.cropHeight*tallFrameless.scale-594f)<=1.5f)
        val standardFrameless=LivePlacement.miniTransform(LivePlacement.Bounds(173,657,1094,1805),173,657,
            LivePlacement.Bounds(1035,180,360,594),150)
        check(standardFrameless.cropLeft==0&&standardFrameless.cropWidth==1094&&standardFrameless.cropTop==150&&standardFrameless.cropBottom==1805)
        check(kotlin.math.abs((standardFrameless.y+standardFrameless.cropTop*standardFrameless.scale)-180f)<=0.5f)
        val restoreFrameless=LivePlacement.miniTransform(LivePlacement.Bounds(173,657,1094,1805),173,657,
            LivePlacement.Bounds(173,657,1094,1805),150,topInsetWeight=0f)
        check(restoreFrameless.cropTop==150&&restoreFrameless.cropBottom==1805&&restoreFrameless.y==657f)
        val shortFrameless=LivePlacement.miniTransform(LivePlacement.Bounds(173,657,1139,1225),173,657,
            LivePlacement.Bounds(1035,180,360,594),60)
        check(shortFrameless.cropTop==60&&shortFrameless.cropBottom==1225&&shortFrameless.cropLeft>0)
        check(kotlin.math.abs((shortFrameless.y+shortFrameless.cropTop*shortFrameless.scale)-180f)<=0.5f)
        check(kotlin.math.abs(shortFrameless.cropHeight*shortFrameless.scale-594f)<=1.5f)
        val ignoredFrameless=LivePlacement.miniTransform(LivePlacement.Bounds(0,0,100,150),0,0,
            LivePlacement.Bounds(0,0,50,80),60)
        check(ignoredFrameless.cropTop==0)
        val miniRadius=checkNotNull(LivePlacement.scaledCornerRadius(53f,standardFrameless.scale,standardFrameless.cropWidth,standardFrameless.cropHeight))
        check(kotlin.math.abs(miniRadius*standardFrameless.scale-53f)<=0.5f)
        check(LivePlacement.scaledCornerRadius(53f,0.1f,200,300)==100f)
        println("PASS: mini rotation uses applied source crop, uniform scale and parent origin in the same geometry for both display orientations.")
        check(FrameworkLayoutPolicy.allowEdgeTop(true,false,true,0,0,1,5,3120,1440))
        check(!FrameworkLayoutPolicy.allowEdgeTop(false,false,true,0,0,1,5,3120,1440))
        check(!FrameworkLayoutPolicy.allowEdgeTop(true,true,true,0,0,1,5,3120,1440))
        check(!FrameworkLayoutPolicy.allowEdgeTop(true,false,false,0,0,1,5,3120,1440))
        check(!FrameworkLayoutPolicy.allowEdgeTop(true,false,true,10,0,1,5,3120,1440))
        check(!FrameworkLayoutPolicy.allowEdgeTop(true,false,true,0,1,1,5,3120,1440))
        for(type in listOf(0,2,3))check(!FrameworkLayoutPolicy.allowEdgeTop(true,false,true,0,0,type,5,3120,1440))
        for(mode in listOf(0,1,2,6))check(!FrameworkLayoutPolicy.allowEdgeTop(true,false,true,0,0,1,mode,3120,1440))
        check(!FrameworkLayoutPolicy.allowEdgeTop(true,false,true,0,0,1,5,1440,3120))
        println("PASS: framework top-inset exception only enabled selected landscape freeform user0/display0 ordinary tasks; diagnostic/fullscreen/split/home/secondary/work/portrait retain native fitting.")
        val oldMotion=LivePlacement.MotionBounds(
            LivePlacement.Bounds(0,113,3120,1271),
            LivePlacement.Bounds(39,152,3042,2481),
            LivePlacement.Bounds(-800,113,4720,2598))
        val edgeMotion=LivePlacement.edgeMotionBounds(3120,1440,oldMotion)
        check(edgeMotion.stable==LivePlacement.Bounds(0,0,3120,1440))
        check(edgeMotion.safe.y==0&&edgeMotion.maximum.y==0)
        check(edgeMotion.safe.y+edgeMotion.safe.height==oldMotion.safe.y+oldMotion.safe.height+56)
        check(edgeMotion.maximum.y+edgeMotion.maximum.height==oldMotion.maximum.y+oldMotion.maximum.height+56)
        check(edgeMotion.safe.x==oldMotion.safe.x&&edgeMotion.safe.width==oldMotion.safe.width)
        check(edgeMotion.maximum.x==oldMotion.maximum.x&&edgeMotion.maximum.width==oldMotion.maximum.width)
        check(LivePlacement.edgeMotionBounds(3120,1440,edgeMotion)==edgeMotion) // no accumulating expansion per MOVE
        check(LivePlacement.edgeMotionBounds(1440,3120,oldMotion)==oldMotion) // portrait untouched
        println("PASS: landscape drag and release share physical top0; original113/152px walls removed, bottom native visibility margin/horizontal stash limits preserved, updates idempotent and portrait unchanged.")
        val oldCrop=LivePlacement.Bounds(118,1142,1209,834)
        val requestedFrame=LivePlacement.Bounds(173,657,1094,1805)
        // Actual 0540 rotation failure: a new portrait canvas was clipped to the old short frame.
        check(LivePlacement.resizeFrame(requestedFrame,requestedFrame,oldCrop,118,1142)==requestedFrame)
        check(LivePlacement.resizeFrame(requestedFrame,null,oldCrop,118,1142)==requestedFrame)
        check(LivePlacement.resizeFrame(requestedFrame,oldCrop.copy(width=0),oldCrop,118,1142)==requestedFrame)
        val nativeMinimum=LivePlacement.Bounds(173,1252,1094,825)
        check(LivePlacement.resizeFrame(nativeMinimum.copy(height=615),nativeMinimum,oldCrop,118,1142)==nativeMinimum)
        val nested=LivePlacement.resizeFrame(requestedFrame,requestedFrame,oldCrop,68,1042)
        check(nested==requestedFrame.copy(x=123,y=557)) // preserve parent origin, not stale task coordinates
        println("PASS: synchronized resize uses applied geometry or requested fallback, preserves native minimum dimensions and parent origin; stale rotated crop cannot replace the new frame.")
        val bili="tv.danmaku.bili"
        val detail="com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"
        val nativeFlags=0x0db0
        val densityFlags=PlayerCompatPolicy.densityConfigChanges(bili,detail,true,true,0,0,1,5,nativeFlags)
        check(densityFlags==0x1db0)
        check(0x1d80 and densityFlags.inv()==0) // observed density+orientation+size relaunch mask
        check((0x1d80 or 0x200) and densityFlags.inv()==0x200) // other changes still relaunch
        check(PlayerCompatPolicy.densityConfigChanges(bili,detail,false,true,0,0,1,5,nativeFlags)==nativeFlags)
        check(PlayerCompatPolicy.densityConfigChanges(bili,detail,true,false,0,0,1,5,nativeFlags)==nativeFlags)
        for(mode in listOf(1,6))check(PlayerCompatPolicy.densityConfigChanges(bili,detail,true,true,0,0,1,mode,nativeFlags)==nativeFlags)
        check(PlayerCompatPolicy.densityConfigChanges(bili,detail,true,true,10,0,1,5,nativeFlags)==nativeFlags)
        check(PlayerCompatPolicy.densityConfigChanges(bili,detail,true,true,0,1,1,5,nativeFlags)==nativeFlags)
        check(PlayerCompatPolicy.densityConfigChanges(bili,detail,true,true,0,0,2,5,nativeFlags)==nativeFlags)
        check(PlayerCompatPolicy.densityConfigChanges(bili,"SearchActivity",true,true,0,0,1,5,nativeFlags)==nativeFlags)
        check(PlayerCompatPolicy.densityConfigChanges("other.app",detail,true,true,0,0,1,5,nativeFlags)==nativeFlags)
        check(PlayerCompatPolicy.densityConfigChanges(bili,detail,true,true,0,0,1,5,0)==0)
        val dy="com.ss.android.ugc.aweme"
        for((page,flags,mask) in listOf(
            Triple("com.ss.android.ugc.aweme.live.LivePlayActivity",0xfa0,0x1d00),
            Triple("com.ss.android.ugc.aweme.main.MainActivity",0xfb0,0x1500),
            Triple("com.ss.android.ugc.aweme.splash.SplashActivity",0xfb0,0x1500))){
            val fixed=DouyinDensityPolicy.densityCallbacks(dy,page,true,true,0,0,1,5,flags)
            check(fixed==flags or 0x1000)
            check(fixed xor flags==0x1000)
            check(mask and fixed.inv()==0) // Actual relaunch masks now handled by callbacks.
            check((mask or 4) and fixed.inv()==4) // Locale still requires native recreation.
            check(DouyinDensityPolicy.densityCallbacks(dy,page,false,true,0,0,1,5,flags)==flags)
            check(DouyinDensityPolicy.densityCallbacks(dy,page,true,false,0,0,1,5,flags)==flags)
            for(mode in listOf(1,6))check(DouyinDensityPolicy.densityCallbacks(dy,page,true,true,0,0,1,mode,flags)==flags)
            check(DouyinDensityPolicy.densityCallbacks(dy,page,true,true,10,0,1,5,flags)==flags)
            check(DouyinDensityPolicy.densityCallbacks(dy,page,true,true,0,1,1,5,flags)==flags)
            check(DouyinDensityPolicy.densityCallbacks(dy,page,true,true,0,0,2,5,flags)==flags)
            check(DouyinDensityPolicy.densityCallbacks(dy,page,true,true,0,0,1,5,0xc00)==0xc00)
            check(DouyinDensityPolicy.densityCallbacks("other.app",page,true,true,0,0,1,5,flags)==flags)
            check(DouyinDensityPolicy.densityCallbacks(dy,"unknown.Activity",true,true,0,0,1,5,flags)==flags)
        }
        val previousClones=io.github.s24u.popupscale.CloneUserPolicy.cloneIds().toSet()
        try{
            io.github.s24u.popupscale.CloneUserPolicy.replaceVerifiedClones(setOf(95))
            check(DouyinDensityPolicy.densityCallbacks(dy,"com.ss.android.ugc.aweme.live.LivePlayActivity",true,true,95,0,1,5,0xfa0)==0x1fa0)
        }finally{io.github.s24u.popupscale.CloneUserPolicy.replaceVerifiedClones(previousClones)}
        println("PASS: Douyin live/feed observed relaunch masks handled by density-only callbacks; unknown/native/fullscreen/split/other display/unsupported profile/locale changes stay native.")
        check(PlayerCompatPolicy.eligible(bili,true,false,true,5))
        check(!PlayerCompatPolicy.eligible(bili,true,false,true,6)) // real split-screen remains blocked
        check(!PlayerCompatPolicy.eligible(bili,true,false,true,1))
        check(!PlayerCompatPolicy.eligible(bili,true,true,true,5))
        check(!PlayerCompatPolicy.eligible(bili,false,false,true,5))
        check(!PlayerCompatPolicy.eligible(bili,true,false,false,5))
        check(!PlayerCompatPolicy.eligible("other.player",true,false,true,5))
        check(PlayerCompatPolicy.playerCaller("com.bilibili.app.gemini.player.widget.story.GeminiPlayerFullscreenWidget","n"))
        check(PlayerCompatPolicy.playerCaller("jv0.d","a"))
        check(PlayerCompatPolicy.playerCaller("com.bilibili.playerbizcommon.features.hardware.HardwareService","onConfigurationChanged"))
        check(!PlayerCompatPolicy.playerCaller("sw1.h","then")) // clipboard foreground guard
        check(!PlayerCompatPolicy.playerCaller("com.bilibili.search2.main.BiliMainSearchActivity","ga"))
        check(!PlayerCompatPolicy.playerCaller("com.bilibili.playerbizcommon.features.background.BackgroundPlayService","access\$isInMultiWindowMode"))
        for(o in listOf(0,6,8,11))check(PlayerCompatPolicy.explicitWide(bili,detail,5,o)==true)
        for(o in listOf(-1,1,7,9,12))check(PlayerCompatPolicy.explicitWide(bili,detail,5,o)==false)
        check(PlayerCompatPolicy.explicitWide(bili,detail,5,4)==null) // sensor: retain source heuristic
        check(PlayerCompatPolicy.explicitWide(bili,detail,1,0)==null)
        check(PlayerCompatPolicy.explicitWide(bili,"SearchActivity",5,0)==null)
        check(PlayerCompatPolicy.explicitWide("other.player",detail,5,0)==null)
        println("PASS: Bili player-only multiwindow compatibility; selection/diagnostic/split-screen isolation; explicit player enter/exit orientation overrides stale window shape.")
        fun edge(mask:Int,x:Float,y:Float)=GesturePolicy.bottomResizeDirection(mask,x,y,400,600,2f)
        check(edge(8,96f,598f)==9 && edge(8,304f,598f)==10)
        check(edge(8,97f,598f)==null && edge(8,303f,598f)==null)
        check(edge(1,0f,504f)==9 && edge(2,400f,504f)==10 && edge(1,0f,503f)==1)
        check(edge(0,200f,1f)==0 && edge(4,200f,1f)==4 && edge(10,350f,588f)==10)
        for(width in listOf(825,1000,1200)){
            val end=GesturePolicy.followScale(100f,3.75f,maxScale=1296f/width,windowWidth=width)*width
            check(kotlin.math.abs(end-width-60f)<0.01f)
        }
        check(GesturePolicy.followScale(100f,3.75f,maxScale=1296f/1280,windowWidth=1280)*1280<=1296.01f)
        check(GesturePolicy.handle(0f,200f,0f,3.75f,windowWidth=1280,screenWidth=1440)==GesturePolicy.Result.RESIZE)
        check(GesturePolicy.handle(0f,100f,0f,3.75f,windowWidth=1280,screenWidth=1440)==GesturePolicy.Result.RESIZE)
        for(d in listOf(1f,2f,3.75f)){
            check(GesturePolicy.handle(0f,-70*d,0f,d,windowWidth=(220*d).toInt(),screenWidth=1440)!=GesturePolicy.Result.HANG_MINI)
            check(GesturePolicy.handle(0f,-220*d,0f,d,atMiniSize=false)!=GesturePolicy.Result.HANG_MINI)
            check(GesturePolicy.handle(0f,-220*d,0f,d,atMiniSize=true)==GesturePolicy.Result.HANG_MINI)
            for(minimum in listOf(.7f,1f)){
                var last=1f
                for(distance in 0..220){
                    val scale=GesturePolicy.shrinkScale(-distance*d,d,.3f,minimum)
                    check(scale<=last+.00001f && scale>=.3f-.00001f)
                    last=scale
                }
                check(GesturePolicy.shrinkScale(-220*d,d,.3f,minimum)==.3f)
                check(GesturePolicy.shrinkScale(-70*d,d,.3f,minimum)>.3f)
            }
        }
        val R=GesturePolicy.Result.RESTORE;val F=GesturePolicy.Result.FULLSCREEN;val C=GesturePolicy.Result.CLOSE;val H=GesturePolicy.Result.HANG_MINI
        for(d in listOf(1f,2f,3.75f)){
            check(GesturePolicy.handle(0f,-109*d,0f,d,sideHandle=true,atMiniSize=true)!=H)
            check(GesturePolicy.handle(0f,-110*d,0f,d,sideHandle=true,atMiniSize=true)==H)
            check(GesturePolicy.handle(0f,-110*d,0f,d,sideHandle=true,atMiniSize=false)!=H)
            check(GesturePolicy.handle(0f,-110*d,0f,d,sideHandle=false,atMiniSize=true)!=H)
            check(GesturePolicy.handle(0f,-220*d,0f,d,sideHandle=false,atMiniSize=true)==H)
            for(minimum in listOf(.7f,1f)){
                for(distance in 0..110){
                    val shortened=GesturePolicy.shrinkScale(-distance*d,d,.3f,minimum,sideHandle=true)
                    val original=GesturePolicy.shrinkScale(-distance*2*d,d,.3f,minimum)
                    check(kotlin.math.abs(shortened-original)<.00001f)
                }
                check(GesturePolicy.shrinkScale(-110*d,d,.3f,minimum,sideHandle=true)==.3f)
            }
        }
        println("PASS: side inward travel halves to110dp including visual shrink, mini-size gate stays required, portrait220dp unchanged.")
        for(d in listOf(1f,2f,3.75f)){
            check(GesturePolicy.handle(0f,39*d,0f,d)==GesturePolicy.Result.RESIZE)
            check(GesturePolicy.handle(0f,40*d,0f,d)==GesturePolicy.Result.RESIZE)
            check(GesturePolicy.handle(0f,-40*d,0f,d)==GesturePolicy.Result.RESIZE)
            check(GesturePolicy.handle(0f,-63*d,0f,d)==GesturePolicy.Result.RESIZE)
            check(GesturePolicy.handle(0f,-64*d,0f,d)==GesturePolicy.Result.RESIZE)
            check(GesturePolicy.handle(0f,-219*d,0f,d)==GesturePolicy.Result.RESIZE)
            check(GesturePolicy.handle(0f,-220*d,0f,d)==H)
            check(GesturePolicy.handle(0f,-220*d,-1200*d,d)==C)
            check(GesturePolicy.handle(0f,-220*d,0f,d,true)==R)
            for(end in listOf(.2f,.3f,.45f)){
                var previous=1f
                for(distance in 0..220){
                    val scale=GesturePolicy.followScale(-distance*d,d,end)
                    check(scale<=previous+0.00001f&&scale>=end-0.00001f)
                    previous=scale
                }
                check(GesturePolicy.followScale(-220*d,d,end)==end)
                check(GesturePolicy.followScale(-400*d,d,end)==end)
                check(GesturePolicy.closeTargetScale(end)<end)
            }
            check(GesturePolicy.handle(0f,12*d,901*d,d)==F)
            check(GesturePolicy.handle(0f,-12*d,-901*d,d)==C)
            check(GesturePolicy.handle(0f,-80*d,-1200*d,d)==C)
            check(GesturePolicy.handle(0f,1*d,5000*d,d)==R)
            check(GesturePolicy.handle(100*d,45*d,0f,d)==R)
            check(GesturePolicy.handle(0f,80*d,0f,d,true)==R)
            check(GesturePolicy.handle(0f,-80*d,0f,d,true)==R)
            check(GesturePolicy.handle(0f,15*d,-1000*d,d)==GesturePolicy.Result.RESIZE)
        }
        check(GesturePolicy.handleBarWidth(1094)==410)
        check(GesturePolicy.handleBarWidth(800)==300)
        for(d in listOf(2.5f,3.75f)){
            val bottom=GesturePolicy.handleLayout(100,200,825,1361,1440,3120,d)
            check(bottom.edge==0&&bottom.y==1561&&bottom.barWidth>bottom.barHeight)
            val right=GesturePolicy.handleLayout(100,80,834,1209,3120,1440,d)
            check(right.edge==2&&right.x==934&&right.barHeight>right.barWidth)
            check(right.x+right.barX>934&&right.y+right.height<=1440-24*d+1)
            val left=GesturePolicy.handleLayout(2186,80,834,1209,3120,1440,d)
            check(left.edge==1&&left.x+left.width==2186&&left.barHeight>left.barWidth)
            check(left.x+left.barX+left.barWidth<2186)
            val held=GesturePolicy.handleLayout(2186,80,834,1209,3120,1440,d,previousEdge=2,lockSide=true)
            check(held.edge==2)
            val middleLeft=(3120-834)/2
            check(GesturePolicy.handleLayout(middleLeft+1,80,834,1209,3120,1440,d,previousEdge=2).edge==2)
            check(GesturePolicy.handleLayout(middleLeft+200,80,834,1209,3120,1440,d,previousEdge=2).edge==1)
            check(GesturePolicy.handleLayout(100,200,825,1361,1440,3120,d,previousEdge=1).edge==0)
        }
        println("PASS: landscape handle faces screen centre on either side, stays above HOME zone, locks during touch and switches with centre hysteresis; portrait stays bottom.")
        for(edge in 1..2)for(d in listOf(2.5f,3.75f)){
            val outward=if(edge==2)1f else -1f
            val inward=GesturePolicy.handleMotion(edge,-outward*220*d,0f)
            check(inward.main==-220*d&&inward.cross==0f)
            check(GesturePolicy.handle(inward.cross,inward.main,inward.velocity,d,atMiniSize=true)==H)
            check(GesturePolicy.handle(inward.cross,inward.main,inward.velocity,d,atMiniSize=false)!=H)
            val grow=GesturePolicy.handleMotion(edge,outward*140*d,0f)
            val initial=(240*d).toInt()
            check(grow.main==140*d)
            check(GesturePolicy.handle(grow.cross,grow.main,grow.velocity,d,windowWidth=initial,
                sideHandle=true)==GesturePolicy.Result.RESIZE)
            check(GesturePolicy.handle(0f,139*d,0f,d,windowWidth=initial,
                sideHandle=true)==GesturePolicy.Result.RESIZE)
            for(travel in listOf(140f,220f,1000f,10000f)){
                check(GesturePolicy.handle(0f,travel*d,899*d,d,windowWidth=initial,
                    sideHandle=true)==GesturePolicy.Result.RESIZE)
            }
            val flick=GesturePolicy.handleMotion(edge,outward*12*d,0f,outward*1000*d,0f)
            check(GesturePolicy.handle(flick.cross,flick.main,flick.velocity,d)==F)
            val close=GesturePolicy.handleMotion(edge,-outward*12*d,0f,-outward*1000*d,0f)
            check(GesturePolicy.handle(close.cross,close.main,close.velocity,d)==C)
            val vertical=GesturePolicy.handleMotion(edge,0f,100*d,0f,1000*d)
            check(GesturePolicy.handle(vertical.cross,vertical.main,vertical.velocity,d)==R)
            check(GesturePolicy.handle(0f,140*d,0f,d,windowWidth=initial,
                sideHandle=false)!=F)
        }
        check(GesturePolicy.handleMotion(0,12f,-55f,300f,-800f)==GesturePolicy.HandleMotion(12f,-55f,-800f))
        println("PASS: both side handles mirror inward shrink/hang/close; slow outward growth never fullscreens at any travel, fast outward flick still fullscreens; off-axis and portrait rules retained.")
        check(GesturePolicy.followScale(-220f,1f)==0.3f)
        check(GesturePolicy.followScale(220f,1f)==1.08f)
        for(d in listOf(1f,2f,3.75f)){
            val sw=1440
            val alreadyMaxW=1300
            check(GesturePolicy.handle(0f,39*d,0f,d,windowWidth=alreadyMaxW,screenWidth=sw)==GesturePolicy.Result.RESIZE)
            check(GesturePolicy.handle(0f,40*d,0f,d,windowWidth=alreadyMaxW,screenWidth=sw)==GesturePolicy.Result.RESIZE)
            check(GesturePolicy.handle(0f,12*d,901*d,d,windowWidth=alreadyMaxW,screenWidth=sw)==F)
            check(GesturePolicy.handle(0f,-40*d,0f,d,windowWidth=alreadyMaxW,screenWidth=sw)==GesturePolicy.Result.RESIZE)
            check(GesturePolicy.handle(0f,-220*d,0f,d,windowWidth=alreadyMaxW,screenWidth=sw)==H)
            check(GesturePolicy.handle(0f,-12*d,-901*d,d,windowWidth=alreadyMaxW,screenWidth=sw)==C)

            val normalW=1000
            check(GesturePolicy.handle(0f,3*d,0f,d,windowWidth=normalW,screenWidth=sw)==R)
            check(GesturePolicy.handle(0f,4*d,0f,d,windowWidth=normalW,screenWidth=sw)==GesturePolicy.Result.RESIZE)
            check(GesturePolicy.handle(0f,40*d,0f,d,windowWidth=normalW,screenWidth=sw)==GesturePolicy.Result.RESIZE)
            val boundary=minOf(220*d,(1296-normalW)/GesturePolicy.GROWTH_PER_PIXEL+40*d)
            check(GesturePolicy.handle(0f,boundary-1,0f,d,windowWidth=normalW,screenWidth=sw)==GesturePolicy.Result.RESIZE)
            check(GesturePolicy.handle(0f,boundary,0f,d,windowWidth=normalW,screenWidth=sw)==GesturePolicy.Result.RESIZE)
            check(GesturePolicy.handle(0f,220*d,0f,d,windowWidth=normalW,screenWidth=sw)==GesturePolicy.Result.RESIZE)
            check(GesturePolicy.handle(0f,300*d,0f,d,windowWidth=normalW,screenWidth=sw)==GesturePolicy.Result.RESIZE)
            check(GesturePolicy.handle(0f,12*d,901*d,d,windowWidth=normalW,screenWidth=sw)==F)
            check(GesturePolicy.handle(0f,-40*d,0f,d,windowWidth=normalW,screenWidth=sw)==GesturePolicy.Result.RESIZE)
            check(GesturePolicy.handle(0f,-220*d,0f,d,windowWidth=normalW,screenWidth=sw)==H)
            check(GesturePolicy.handle(0f,-12*d,-901*d,d,windowWidth=normalW,screenWidth=sw)==C)

            val minW = (220 * d).toInt()
            check(GesturePolicy.handle(0f,-40*d,0f,d,windowWidth=minW,screenWidth=sw)==R)
            check(GesturePolicy.handle(0f,-220*d,0f,d,windowWidth=minW,screenWidth=sw)==H)
            check(GesturePolicy.handle(0f,-12*d,-901*d,d,windowWidth=minW,screenWidth=sw)==C)
            check(GesturePolicy.handle(0f,40*d,0f,d,windowWidth=minW,screenWidth=sw)==GesturePolicy.Result.RESIZE)

            val maxScale=1.296f
            var previousDown=1f
            for(distance in 0..220){
                val scale=GesturePolicy.followScale(distance*d,d,maxScale=maxScale)
                check(scale>=previousDown-0.00001f&&scale<=maxScale+0.00001f)
                previousDown=scale
            }
            check(kotlin.math.abs(GesturePolicy.followScale(220*d,d,maxScale=maxScale)-maxScale)<0.0001f)
            check(kotlin.math.abs(GesturePolicy.followScale(300*d,d,maxScale=maxScale)-maxScale)<0.0001f)
        }
        check(GesturePolicy.closeTargetScale(1f)==0.72f)
        check(GesturePolicy.closeTargetScale(0.7f) in 0.45f..0.72f)
        check(GesturePolicy.handle(0f,Float.NaN,0f,3f)==R)
        check(GesturePolicy.handle(0f,100f,0f,0f)==R)
        check(GesturePolicy.arm(2350f,100f,0f,1080,2400,3f,true,false))
        check(!GesturePolicy.arm(1200f,100f,0f,1080,2400,3f,true,false))
        check(!GesturePolicy.arm(2350f,100f,0f,1080,2400,3f,false,false))
        check(!GesturePolicy.arm(2350f,100f,700f,1080,2400,3f,true,false))
        check(!GesturePolicy.arm(2350f,350f,0f,1080,2400,3f,true,false))
        check(GesturePolicy.arm(2350f,350f,0f,1080,2400,3f,true,true))
        check(!GesturePolicy.arm(2350f,500f,0f,1080,2400,3f,true,true))
        check(GesturePolicy.progress(-10000f,3f)==-1f)
        check(GesturePolicy.progress(10000f,3f)==1f)
        check(GesturePolicy.progress(1f,0f)==0f)
        check(LivePlacement.place(1440,3120,0,1805,3.75f)==null)
        check(LivePlacement.place(1440,3120,1094,1805,Float.NaN)==null)
        check(LivePlacement.place(100,100,100,100,100f)==null)
        for(screen in listOf(1080 to 2400,1440 to 3120,3120 to 1440)){
            for(source in listOf(1094 to 1805,825 to 1500,640 to 360,1000 to 9000,100 to 100)){
                val p=checkNotNull(LivePlacement.place(screen.first,screen.second,source.first,source.second,3.75f))
                check(p.x>=0&&p.y>=0&&p.x+p.width<=screen.first&&p.y+p.height<=screen.second)
                check(p.width<=screen.first*.25f+1&&p.height<=screen.second*.32f+1)
                check(p.scale>0&&p.scale<=1&&p.width<=source.first&&p.height<=source.second)
                check(kotlin.math.abs(p.width.toFloat()/source.first-p.height.toFloat()/source.second)<=2f/minOf(source.first,source.second))
            }
        }
        println("PASS: handle directions/velocity/cancel, overview stage, top zone and hysteresis.")
        println("PASS: live placement remains on screen, preserves aspect ratio and never enlarges the source.")
        check(LivePlacement.preserveLandscape(3120,1440,true,false,false)) // landscape game
        check(LivePlacement.preserveLandscape(3120,1440,false,true,false)) // immersive video / content
        check(!LivePlacement.preserveLandscape(3120,1440,false,false,true)) // landscape ordinary activity
        check(!LivePlacement.preserveLandscape(1440,3120,true,false,false)) // portrait game stays portrait
        check(LivePlacement.preserveLandscape(834,1209,true,false,true)) // recover a wrongly portrait-constrained landscape game
        check(!LivePlacement.preserveLandscape(1440,3120,false,false,false))
        for((w,h) in listOf(3120 to 1440,1440 to 3120))for(aspect in listOf(16f/9f,21f/9f,3120f/1440f)){
            val wide=checkNotNull(LivePlacement.landscapeContentWindow(w,h,aspect))
            check(wide.width>wide.height&&wide.width<=w*.76f+1&&wide.height<=h*.7f+1)
            check(wide.x>=0&&wide.y>=0&&wide.x+wide.width<=w&&wide.y+wide.height<=h)
            check(kotlin.math.abs(wide.width.toFloat()/wide.height-aspect)<.01f)
            val mini=checkNotNull(LivePlacement.place(w,h,wide.width,wide.height,3.75f))
            check(mini.width>mini.height&&kotlin.math.abs(mini.width.toFloat()/mini.height-aspect)<.02f)
        }
        check(LivePlacement.landscapeContentWindow(1440,3120,Float.NaN)==null)
        println("PASS: landscape games/immersive content keep wide aspect on both displays; ordinary and portrait games retain portrait policy.")
        val rotation=LivePlacement.RotationLayoutState()
        val flat=LivePlacement.Bounds(300,400,1209,834)
        val portraitDefault=checkNotNull(LivePlacement.standardWindow(1440,3120))
        rotation.observe(41,3120,1440)
        rotation.observe(41,1440,3120)
        check(rotation.awaitingPortrait(41))
        check(rotation.portraitDefault(41,1440,3120,flat)==portraitDefault)
        check(rotation.portraitDefault(41,1440,3120,flat)==portraitDefault) // failed/busy submission must not consume it
        check(rotation.awaitingPortrait(41))
        check(portraitDefault.height>portraitDefault.width&&portraitDefault.height.toFloat()/portraitDefault.width>1.6f)
        check(rotation.portraitDefault(42,1440,3120,flat)==null) // unrelated task never rotated
        check(rotation.portraitDefault(41,1440,3120,portraitDefault)==null)
        check(!rotation.awaitingPortrait(41))
        check(rotation.portraitDefault(41,1440,3120,portraitDefault.copy(width=900,height=1500))==null) // later user resize stays
        rotation.observe(41,3120,1440);rotation.observe(41,1440,3120)
        check(rotation.awaitingPortrait(41))
        rotation.observe(41,3120,1440)
        check(!rotation.awaitingPortrait(41)) // rapid rotation back cancels the obsolete target
        rotation.observe(41,1440,3120);rotation.forget(41)
        check(rotation.portraitDefault(41,1440,3120,flat)==null) // a reused task id has no stale rotation
        println("PASS: landscape-to-portrait restores default shape, survives delayed native resize, keeps other tasks/user resizing and cancels stale rotation requests.")
        val entry=LivePlacement.EntryLayoutState()
        entry.observe(51,5,true,1440,3120) // first popup can inherit remembered landscape bounds
        check(entry.portraitDefault(51,1440,3120,flat)==portraitDefault)
        entry.observe(51,5,true,1440,3120) // relayout callbacks do not consume a delayed request
        check(entry.portraitDefault(51,1440,3120,flat)==portraitDefault)
        check(entry.portraitDefault(51,1440,3120,portraitDefault)==null)
        val manual=portraitDefault.copy(width=900,height=1500)
        entry.observe(51,5,true,1440,3120)
        check(entry.portraitDefault(51,1440,3120,manual)==null) // same session: manual resize stays
        entry.observe(51,5,false,1440,3120)
        entry.observe(51,5,true,1440,3120)
        check(entry.portraitDefault(51,1440,3120,manual)==null) // Activity BACK visibility flap is not a new session
        entry.observe(51,1,true,1440,3120)
        check(!entry.awaiting(51))
        entry.observe(51,5,true,1440,3120)
        check(entry.awaiting(51)) // fullscreen -> popup is another entry
        entry.observe(52,5,true,3120,1440)
        entry.observe(52,5,false,3120,1440)
        entry.observe(52,5,true,1440,3120)
        check(entry.portraitDefault(52,1440,3120,flat)==null) // physical rotation is handled by RotationLayoutState
        entry.cancel(52) // wide game/video profile owns its geometry
        check(entry.portraitDefault(52,1440,3120,flat)==null)
        entry.observe(53,5,true,1440,3120,owned=true)
        entry.observe(53,5,true,1440,3120)
        check(entry.portraitDefault(53,1440,3120,manual)==null) // mini/gesture ownership is not a launch
        check(entry.portraitDefault(54,1440,3120,flat)==null) // unrelated task
        entry.observe(51,5,true,3120,1440)
        check(!entry.awaiting(51)) // rotating again cancels the portrait target
        entry.forget(51)
        check(entry.portraitDefault(51,1440,3120,flat)==null)
        entry.observe(51,5,true,1440,3120)
        check(entry.awaiting(51)) // reused task id starts a fresh session
        println("PASS: portrait popup entry resets remembered bounds, retries until acknowledged, preserves in-session user resizing, and excludes owned/wide content.")
        for((w,h) in listOf(3120 to 1440,2400 to 1080)){
            val portrait=checkNotNull(LivePlacement.standardWindow(w,h))
            check(portrait.width<portrait.height&&portrait.height<h)
            val fixed=LivePlacement.portraitInLandscape(w,h,LivePlacement.Bounds(0,0,w,h),2.5f)
            check(fixed.width<fixed.height&&fixed.x>=0&&fixed.y>=0&&fixed.y+fixed.height<=h)
            check(LivePlacement.portraitInLandscape(w,h,fixed,2.5f)==fixed)
            val aspect=fixed.height.toFloat()/fixed.width
            val max=LivePlacement.maxResizeWidth(w,h,aspect,2.5f)
            check(max*aspect<=h+2&&max<=w*.9f+1)
            check(max*aspect>h-3) // no longer reserve bottom-bar space for the side handle
            val enlarged=fixed.copy(y=0,width=max,height=(max*aspect).toInt())
            check(LivePlacement.portraitInLandscape(w,h,enlarged,2.5f)==enlarged)
            val oversized=enlarged.copy(width=max*2,height=enlarged.height*2)
            val fitted=LivePlacement.portraitInLandscape(w,h,oversized,2.5f)
            check(fitted.height<=h&&fitted.height>h-3)
            check(kotlin.math.abs(fitted.height.toFloat()/fitted.width-aspect)<.01f)
            for(dy in listOf(40f,220f,1000f)){
                check(GesturePolicy.handle(0f,dy,0f,2.5f,windowWidth=max,screenWidth=w)!=F)
                check(GesturePolicy.handle(0f,dy,1000f*2.5f,2.5f,windowWidth=max,screenWidth=w)==F)
            }
        }
        val videoBounds=LivePlacement.Bounds(402,113,834,1322)
        check(LivePlacement.portraitInLandscape(3120,1440,videoBounds,3.75f)==videoBounds)
        println("PASS: enlarged upright landscape windows keep manual size up to physical height; oversized windows fit proportionally instead of resetting to default; portrait bottom reserve and fast flicks retained.")
        for(screen in listOf(1080 to 2400,1440 to 3120,2400 to 1080,3120 to 1440))for(d in listOf(2.5f,3.75f)) {
            val (w,h)=screen;val target=checkNotNull(LivePlacement.hangTarget(w,h,d))
            val standard=checkNotNull(LivePlacement.standardWindow(w,h))
            check(target==LivePlacement.place(w,h,standard.width,standard.height,d))
            val guide=checkNotNull(LivePlacement.guideBounds(w,h,d))
            check(guide.x+guide.width==target.x+target.width&&guide.y==target.y)
            check(guide.width.toFloat()/target.width>=1.4f)
            check(guide.x>=w*.58f&&guide.y+guide.height<=h)
            val x=target.x+target.width/2f;val y=target.y+target.height/2f
            fun state(xx:Float,yy:Float,was:Boolean=false,moving:Boolean=true,start:Float=h-10f,startX:Float=Float.NaN)=
                HangTargetPolicy.evaluate(start,xx,yy,w,h,d,moving,was,startX=startX)
            check(state(x,y).armed)
            check(!state(w/2f,10f).armed) // old top-centre trigger must no longer fire
            check(!state(x,y,moving=false).visible) // no live app-card animation yet
            check(!state(x,y,start=h/2f).visible) // gestures that did not begin at the bottom
            check(!state(x,h-10-h*.20f).visible)
            check(!state(w*.2f,h*.6f).visible) // left/centre Home swipe never flashes the corner guide
            if(h>w){
                check(state(x,h-10-h*.36f).visible)
                check(!state(x,h-10-h*.36f).armed) // normal 36% portrait Home flick does not arm hang
                check(state(x,h-10-h*.48f).armed)
                val gx=(guide.x+12*d).coerceAtMost(w*.70f)
                check(!state(gx,h-10-h*.48f,startX=gx).armed) // straight upward swipe without rightward move does not arm
                check(state(gx,h-10-h*.48f,startX=gx-30*d).armed) // deliberate diagonal move to the right arms
            }else{
                check(state(x,h-10-h*.30f).visible)
                check(state(x,h-10-h*.34f).armed)
            }
            check(!state(w/2f,h-10-h*.50f).armed) // centre HOME remains normal
            val bottom=(guide.y+guide.height).toFloat()
            val edge=if(w>h)guide.y.toFloat() else bottom
            val sign=if(w>h)-1 else 1
            check(!state(x,edge+sign*16*d).armed)
            check(state(x,edge+sign*16*d,was=true).armed) // exit hysteresis
            check(!state(x,edge+sign*30*d,was=true).armed)
            check(!state(Float.NaN,y).visible)
            val far=state(w*.56f,h*.64f);val near=state(x,edge+sign*20*d)
            check(far.visible&&!far.armed&&near.progress>far.progress)
        }
        check(!HangTargetPolicy.allowRelease(19L,300f)) // fast pass-through Home flick rejected
        check(!HangTargetPolicy.allowRelease(120L,900f)) // high upward fling velocity rejected
        check(!HangTargetPolicy.allowRelease(120L,400f,1200f)) // high diagonal fling speed rejected
        check(HangTargetPolicy.allowRelease(110L,220f,300f)) // deliberate corner hold/deceleration accepted
        check(LivePlacement.standardWindow(0,3120)==null)
        check(LivePlacement.hangTarget(1440,3120,Float.NaN)==null)
        println("PASS: portrait/landscape corner hang zone, Home flick rejection, rightward intent and exit hysteresis.")
        val cards=TaskListDeferral()
        check(!cards.intercept(true));check(cards.takeDeferred()==null)
        cards.blocking=true;check(cards.intercept(false));check(cards.hasDeferred)
        check(cards.takeDeferred()==null)
        cards.blocking=false;check(cards.takeDeferred()==false);check(cards.takeDeferred()==null)
        cards.blocking=true;check(cards.intercept(true));cards.discard()
        check(cards.takeDeferred()==null&&!cards.blocking)
        println("PASS: task-list requests pass normally, replay once on leaving, preserve false flag and discard on cancel.")
        // Native preparation can finish before or after the early visual animation.
        for(nativeFirst in listOf(false,true)) {
            val gate=FrameAnimationGate();val delivered=mutableListOf<Boolean>()
            check(gate.begin());check(!gate.begin())
            if(nativeFirst)gate.await{delivered.add(it)}
            gate.complete(true)
            if(!nativeFirst)gate.await{delivered.add(it)}
            gate.complete(false)
            check(delivered==listOf(true));check(!gate.begin())
        }
        val cancelled=FrameAnimationGate();var cancelCount=0
        check(cancelled.begin());cancelled.await{check(!it);cancelCount++}
        cancelled.complete(false);cancelled.complete(true)
        cancelled.await{check(!it);cancelCount++}
        check(cancelCount==2);check(!cancelled.begin())
        val fresh=FrameAnimationGate();check(fresh.begin()) // same task's next gesture gets a fresh gate
        fresh.complete(true);fresh.await{check(it)}
        println("PASS: early frame/native rendezvous in both orders, single completion, cancellation and repeated sessions.")
        for(task in listOf(-1,40,41,42))for(user in listOf(0,10))for(display in listOf(0,1))
            for(mode in listOf(1,2,5,6))for(batch in listOf(false,true)) {
                val keep=MiniLeasePolicy.protects(41,0,6000,1000,task,user,0,display,mode,batch)
                if(keep)check(task==41&&user==0&&display==0&&mode==5&&batch)
            }
        check(MiniLeasePolicy.protects(41,0,6000,1000,41,0,0,0,5,true))
        check(!MiniLeasePolicy.protects(41,0,1000,1000,41,0,0,0,5,true))
        check(!MiniLeasePolicy.protects(41,0,6001,1000,41,0,0,0,5,true))
        check(!MiniLeasePolicy.protects(41,0,6000,1000,41,0,10,0,5,true))
        check(!MiniLeasePolicy.protects(-1,0,6000,1000,-1,0,0,0,5,true))
        println("PASS: mini lease cannot protect other tasks/users/displays/modes, direct minimize or stale sessions.")
        check(MiniLeasePolicy.selected(41,0,6000,1000,41,10057,10090,10057))
        check(MiniLeasePolicy.selected(41,0,6000,1000,41,10090,10090,10057))
        check(!MiniLeasePolicy.selected(41,0,6000,1000,42,10090,10090,10057))
        check(!MiniLeasePolicy.selected(41,10,6000,1000,41,10090,10090,10057))
        check(!MiniLeasePolicy.selected(41,0,1000,1000,41,10090,10090,10057))
        check(!MiniLeasePolicy.selected(41,0,6000,1000,41,10123,10090,10057))
        check(!MiniLeasePolicy.selected(41,0,6000,1000,41,-1,-1,-1))
        println("PASS: Recents selection only matches the live task, current lease and trusted Launcher/SystemUI UID.")
        fun iconSelected(task:Int=41,user:Int=0,display:Int=0,mode:Int=5,caller:Int=10090,
                         result:Int=2,main:Boolean=true,expires:Long=6000)=
            MiniLeasePolicy.launcherSelected(41,0,expires,1000,task,user,display,mode,caller,10090,result,main)
        for(result in -3..6)check(iconSelected(result=result)==(result==0||result==2||result==3))
        check(!iconSelected(task=42)&&!iconSelected(task=-1))
        check(!iconSelected(user=10)&&!iconSelected(display=1)&&!iconSelected(mode=1))
        check(!iconSelected(caller=10057)&&!iconSelected(caller=-1)&&!iconSelected(caller=1000))
        check(!iconSelected(main=false)&&!iconSelected(expires=1000)&&!iconSelected(expires=6001))
        check(!MiniLeasePolicy.launcherSelected(41,0,6000,1000,41,0,0,5,-1,-1,2,true))
        println("PASS: launcher icon expands only the successfully selected leased freeform task for its user/display and trusted Launcher UID.")
        val floatBox=MiniDragPolicy.Box(500,600,360,594)
        check(MiniDragPolicy.release(floatBox,1440,3120,3.75f)==MiniDragPolicy.Action.FLOAT)
        check(MiniDragPolicy.release(floatBox.copy(x=0),1440,3120,3.75f)==MiniDragPolicy.Action.DOCK_LEFT)
        check(MiniDragPolicy.release(floatBox.copy(x=1080),1440,3120,3.75f)==MiniDragPolicy.Action.DOCK_RIGHT)
        check(MiniDragPolicy.release(floatBox.copy(y=-296),1440,3120,3.75f)==MiniDragPolicy.Action.FLOAT)
        check(MiniDragPolicy.release(floatBox.copy(y=-297),1440,3120,3.75f)==MiniDragPolicy.Action.CLOSE_TOP)
        check(MiniDragPolicy.release(floatBox.copy(y=2822),1440,3120,3.75f)==MiniDragPolicy.Action.FLOAT)
        check(MiniDragPolicy.release(floatBox.copy(y=2823),1440,3120,3.75f)==MiniDragPolicy.Action.CLOSE_BOTTOM)
        check(MiniDragPolicy.release(floatBox.copy(x=-100,y=-400),1440,3120,3.75f)==MiniDragPolicy.Action.CLOSE_TOP)
        for((w,h) in listOf(1080 to 2400,1440 to 3120))for(d in listOf(2.5f,3.75f))
            for(left in listOf(false,true))for(cy in listOf(-100,0,h/2,h,h+100)) {
                val mini=checkNotNull(LivePlacement.hangTarget(w,h,d))
                val dock=MiniDragPolicy.dock(left,cy,mini.width,mini.height,w,h,d)
                check(if(left)dock.right<=0 else dock.x>=w)
                check(dock.y>=0&&dock.bottom<=h&&dock.width<=mini.width)
                val tab=MiniDragPolicy.tab(left,cy,w,h,d)
                check(tab.x>=0&&tab.right<=w&&tab.y>=0&&tab.bottom<=h)
                val revealed=MiniDragPolicy.reveal(left,cy,mini.width,mini.height,w,h,d)
                check(revealed.x>=0&&revealed.right<=w&&revealed.y>=0&&revealed.bottom<=h)
                check(revealed.width==mini.width&&revealed.height==mini.height)
                check(MiniDragPolicy.release(revealed,w,h,d)==MiniDragPolicy.Action.FLOAT)
            }
        println("PASS: mini free move, both side docks/tabs/reveal, half-out vertical close and corner precedence.")
        for((w,h) in listOf(1080 to 2400,1440 to 3120))for(d in listOf(2.5f,3.75f)) {
            val mini=checkNotNull(LivePlacement.hangTarget(w,h,d))
            val centre=MiniDragPolicy.Box((w-mini.width)/2,h/3,mini.width,mini.height)
            fun plan(b:MiniDragPolicy.Box,vx:Float=0f,vy:Float=0f,dx:Float=0f,dy:Float=0f)=
                MiniDragPolicy.motion(b,w,h,d,vx*d,vy*d,dx*d,dy*d)
            check(plan(centre).action==MiniDragPolicy.Action.FLOAT)
            check(plan(centre.copy(x=(30*d).toInt()),dx=-100f).action==MiniDragPolicy.Action.FLOAT)
            check(plan(centre.copy(x=w-mini.width-(30*d).toInt()),dx=100f).action==MiniDragPolicy.Action.FLOAT)
            check(plan(centre.copy(x=0),dx=-100f).action==MiniDragPolicy.Action.DOCK_LEFT)
            check(plan(centre.copy(x=w-mini.width),dx=100f).action==MiniDragPolicy.Action.DOCK_RIGHT)
            check(plan(centre.copy(x=0),dy=100f).action==MiniDragPolicy.Action.FLOAT)
            check(plan(centre.copy(x=w-mini.width),dy=-100f).action==MiniDragPolicy.Action.FLOAT)
            val right=plan(centre.copy(x=0),1100f)
            val left=plan(centre.copy(x=w-mini.width),-1100f)
            check(right.action==MiniDragPolicy.Action.FLOAT&&right.destination.right<w)
            check(left.action==MiniDragPolicy.Action.FLOAT&&left.destination.x>0)
            check(right.impact?.right==w&&left.impact?.x==0)
            check(right.durationMs in 360..560&&left.durationMs in 360..560)
            for((start,motion) in listOf(centre.copy(x=0) to right,centre.copy(x=w-mini.width) to left)){
                check(MiniDragPolicy.sample(start,motion,0f)==start)
                check(MiniDragPolicy.sample(start,motion,1f)==motion.destination)
                for(i in 0..100){
                    val frame=MiniDragPolicy.sample(start,motion,i/100f)
                    check(frame.width==mini.width&&frame.height==mini.height)
                    check(frame.x>=0&&frame.right<=w&&frame.y>=0&&frame.bottom<=h)
                }
                // Most travel happens early, then the miniature hits the edge and rebounds inward.
                val early=MiniDragPolicy.sample(start,motion,.36f)
                check(kotlin.math.abs(early.x-start.x)>kotlin.math.abs(checkNotNull(motion.impact).x-start.x)/2)
                val hit=MiniDragPolicy.sample(start,motion,.72f)
                check(kotlin.math.abs(hit.x-checkNotNull(motion.impact).x)<=1)
                var previous=hit
                for(i in 73..100){
                    val next=MiniDragPolicy.sample(start,motion,i/100f)
                    check(next.x in minOf(hit.x,motion.destination.x)..maxOf(hit.x,motion.destination.x))
                    check(kotlin.math.abs(next.x-motion.destination.x)<=kotlin.math.abs(previous.x-motion.destination.x))
                    previous=next
                }
            }
            // Moving away from a magnet must not snap back to that same edge.
            check(plan(centre.copy(x=0),250f).action==MiniDragPolicy.Action.FLOAT)
            check(plan(centre.copy(x=w-mini.width),-250f).action==MiniDragPolicy.Action.FLOAT)
            check(plan(centre,800f,1800f).action==MiniDragPolicy.Action.FLOAT)
            val vertical=plan(centre,0f,-6000f)
            check(vertical.action==MiniDragPolicy.Action.FLOAT&&vertical.destination.y>=0)
            check(vertical.impact==null)
            check(kotlin.math.abs(vertical.destination.y-centre.y)<=112*d+1)
            val nearTop=centre.copy(y=(30*d).toInt())
            val topHit=plan(nearTop,0f,-6000f)
            check(topHit.impact?.y==0)
            var previousTop=0
            for(i in 72..100){
                val y=MiniDragPolicy.sample(nearTop,topHit,i/100f).y
                check(y in previousTop..topHit.destination.y)
                previousTop=y
            }
            for(vx in listOf(-6000f,-1500f,1500f,6000f))for(vy in listOf(-6000f,-1500f,1500f,6000f)){
                if(kotlin.math.abs(vx)<=kotlin.math.abs(vy)*2f){
                    val diagonal=plan(centre,vx,vy)
                    check(diagonal.action==MiniDragPolicy.Action.FLOAT)
                    val travel=kotlin.math.hypot((diagonal.destination.x-centre.x).toFloat(),(diagonal.destination.y-centre.y).toFloat())
                    check(travel<=minOf(96*d,w*.28f,h*.15f)+2)
                }
            }
            for(x in listOf(0,w-mini.width))for(vy in listOf(-2000f,2000f)){
                check(plan(centre.copy(x=x),vy=vy,dx=5f,dy=200f).action==MiniDragPolicy.Action.FLOAT)
            }
            check(plan(centre.copy(x=0),-1200f,dx=-100f).action==MiniDragPolicy.Action.FLOAT)
            check(plan(centre.copy(x=w-mini.width),1200f,dx=100f).action==MiniDragPolicy.Action.FLOAT)
            check(plan(centre.copy(y=-centre.height),1200f).action==MiniDragPolicy.Action.CLOSE_TOP)
            check(plan(centre,Float.NaN,Float.POSITIVE_INFINITY).action==MiniDragPolicy.Action.FLOAT)
            check(MiniDragPolicy.stashLeft(-420,674,w))
            check(!MiniDragPolicy.stashLeft(w-674,w+420,w))
            check(MiniDragPolicy.stashLeft(300,800,w,-12000f))
            check(!MiniDragPolicy.stashLeft(300,800,w,12000f))
            check(MiniDragPolicy.stashLeft(100,500,w,5000f))
            check(!MiniDragPolicy.stashLeft(w-500,w-100,w,-5000f))
            check(MiniDragPolicy.stashLeft(100,500,w,Float.NaN))
        }
        check(MiniDragPolicy.dockTapRestoresStandard(true))
        check(!MiniDragPolicy.dockTapRestoresStandard(false))
        check(MiniDragPolicy.keepStandardDockOrigin(true,true))
        check(!MiniDragPolicy.keepStandardDockOrigin(true,false))
        check(!MiniDragPolicy.keepStandardDockOrigin(false,true))
        check(MiniDragPolicy.DOCK_IDLE_DELAY_MS==2000L)
        check(MiniDragPolicy.dockBadgeAlpha(0L)==1f)
        check(MiniDragPolicy.dockBadgeAlpha(1999L)==1f)
        check(MiniDragPolicy.dockBadgeAlpha(2000L)==0.5f)
        check(MiniDragPolicy.dockBadgeAlpha(5000L)==0.5f)
        check(MiniDragPolicy.dockedSurfaceAlpha(true)==0f)
        check(MiniDragPolicy.dockedSurfaceAlpha(false)==1f)
        check(MiniDragPolicy.resizeHangLeft(1 or 8,MiniDragPolicy.Box(800,600,200,200),1440))
        check(!MiniDragPolicy.resizeHangLeft(2 or 8,MiniDragPolicy.Box(200,600,200,200),1440))
        check(MiniDragPolicy.resizeHangLeft(8,MiniDragPolicy.Box(200,600,200,200),1440))
        check(!MiniDragPolicy.resizeHangLeft(8,MiniDragPolicy.Box(1000,600,200,200),1440))
        val rightHang=checkNotNull(MiniDragPolicy.resizeMiniTarget(false,1094,1805,1440,3120,3.75f))
        val leftHang=checkNotNull(MiniDragPolicy.resizeMiniTarget(true,1094,1805,1440,3120,3.75f))
        val defaultHang=checkNotNull(LivePlacement.hangTarget(1440,3120,3.75f))
        check(rightHang.x==defaultHang.x&&rightHang.y==defaultHang.y&&rightHang.width==defaultHang.width&&rightHang.height==defaultHang.height)
        check(leftHang.x==1440-rightHang.right&&leftHang.y==rightHang.y&&leftHang.width==rightHang.width&&leftHang.height==rightHang.height)
        println("PASS: manual-only docking, throws stay mini, single monotonic rebound, vertical/diagonal damping, constant size and no velocity-only close.")
        println("PASS: top-bar freeform stash side selection, corner resize-to-mini left/right top hang targets, direct standard-window restore, docked off-screen alpha=0f and 2s 50% idle badge opacity.")
        check(io.github.s24u.popupscale.SideGesturePolicy.PACKAGE_NAME == "com.samsung.android.sidegesturepad")
        check(io.github.s24u.popupscale.AppScopePolicy.isBlocked(io.github.s24u.popupscale.SideGesturePolicy.PACKAGE_NAME))
        check(io.github.s24u.popupscale.SettingsDefaults.flag(io.github.s24u.popupscale.SideGesturePolicy.PREF_KEY_FAST_ACTION_POPUP, false))
        val fastActionStack = arrayOf(
            StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", 1500),
            StackTraceElement("io.github.s24u.popupscale.SideGestureHook", "isCalledFromFastAction", "SideGestureHook.java", 80),
            StackTraceElement("n6.y", "H1", "y.java", 100),
            StackTraceElement("v5.v", "c", "v.java", 50),
            StackTraceElement("r5.d", "d", "d.java", 20)
        )
        check(io.github.s24u.popupscale.SideGesturePolicy.isCalledFromFastAction(fastActionStack, null))
        val gestureStack = arrayOf(
            StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", 1500),
            StackTraceElement("io.github.s24u.popupscale.SideGestureHook", "isCalledFromFastAction", "SideGestureHook.java", 80),
            StackTraceElement("n6.y", "H1", "y.java", 100),
            StackTraceElement("r5.f", "V", "f.java", 200)
        )
        check(!io.github.s24u.popupscale.SideGesturePolicy.isCalledFromFastAction(gestureStack, null))
        check(!io.github.s24u.popupscale.SideGesturePolicy.isCalledFromFastAction(null, null))
        check(io.github.s24u.popupscale.SideGesturePolicy.shouldRedirectTarget("com.coolapk.market"))
        check(io.github.s24u.popupscale.SideGesturePolicy.shouldRedirectTarget("com.tencent.mm"))
        check(!io.github.s24u.popupscale.SideGesturePolicy.shouldRedirectTarget(io.github.s24u.popupscale.SideGesturePolicy.PACKAGE_NAME))
        check(!io.github.s24u.popupscale.SideGesturePolicy.shouldRedirectTarget("android"))
        check(!io.github.s24u.popupscale.SideGesturePolicy.shouldRedirectTarget("com.android.systemui"))
        check(!io.github.s24u.popupscale.SideGesturePolicy.shouldRedirectTarget("com.android.permissioncontroller"))
        check(!io.github.s24u.popupscale.SideGesturePolicy.shouldRedirectTarget(null))
        check(!io.github.s24u.popupscale.SideGesturePolicy.shouldRedirectTarget(""))
        val fastActionViewStack = arrayOf(
            StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", 1500),
            StackTraceElement("com.samsung.android.sidegesturepad.fastaction.SGPFastActionView", "dispatchTouchEvent", "SGPFastActionView.java", 120)
        )
        check(io.github.s24u.popupscale.SideGesturePolicy.isCalledFromFastAction(fastActionViewStack, null))
        val ohoSelected = io.github.s24u.popupscale.SideGesturePolicy.parsePackageNames("com.sec.android.app.popupcalculator\ncom.tencent.mm")
        val ohoExcluded = io.github.s24u.popupscale.SideGesturePolicy.parsePackageNames("com.samsung.knox.securefolder")
        check(ohoSelected == setOf("com.sec.android.app.popupcalculator", "com.tencent.mm"))
        check(io.github.s24u.popupscale.SideGesturePolicy.shouldRedirectTarget("com.samsung.knox.securefolder", true, true, true, true, true, ohoSelected, emptySet()))
        check(!io.github.s24u.popupscale.SideGesturePolicy.shouldRedirectTarget("com.samsung.knox.securefolder", false, true, true, true, true, ohoSelected, emptySet()))
        check("settings-unavailable" == io.github.s24u.popupscale.SideGesturePolicy.launchDecisionReason("com.samsung.knox.securefolder", false, true, true, true, true, ohoSelected, emptySet(), true))
        check(!io.github.s24u.popupscale.SideGesturePolicy.shouldRedirectTarget("com.samsung.knox.securefolder", true, false, true, true, true, ohoSelected, emptySet()))
        check(!io.github.s24u.popupscale.SideGesturePolicy.shouldRedirectTarget("com.samsung.knox.securefolder", true, true, false, true, true, ohoSelected, emptySet()))
        check("disabled" == io.github.s24u.popupscale.SideGesturePolicy.launchDecisionReason("com.samsung.knox.securefolder", true, true, false, true, true, ohoSelected, emptySet(), true))
        check(!io.github.s24u.popupscale.SideGesturePolicy.shouldRedirectTarget("com.samsung.knox.securefolder", true, true, true, true, true, ohoSelected, ohoExcluded))
        check("excluded" == io.github.s24u.popupscale.SideGesturePolicy.launchDecisionReason("com.samsung.knox.securefolder", true, true, true, true, true, ohoSelected, ohoExcluded, true))
        check(io.github.s24u.popupscale.SideGesturePolicy.shouldRedirectTarget("com.sec.android.app.popupcalculator", true, true, true, false, true, ohoSelected, emptySet()))
        check(!io.github.s24u.popupscale.SideGesturePolicy.shouldRedirectTarget("com.samsung.knox.securefolder", true, true, true, false, true, ohoSelected, emptySet()))
        check(!io.github.s24u.popupscale.SideGesturePolicy.shouldRedirectTarget(io.github.s24u.popupscale.AppScopePolicy.MODULE_PACKAGE, true, true, true, true, false, ohoSelected, emptySet()))
        check("fast-action" == io.github.s24u.popupscale.SideGesturePolicy.launchDecisionReason("com.sec.android.app.popupcalculator", true, true, true, true, true, ohoSelected, emptySet(), true))
        println("PASS: Good Lock OHO+ FastAction popup redirection policy, stack filtering and preferences defaults.")
        io.github.s24u.popupscale.CloneUserPolicy.replaceVerifiedClones(setOf(95))
        try {
            check(MiniLeasePolicy.homeEligible(true,false,true,true,true,95,0,0,1,5,true,true))
            check(!MiniLeasePolicy.homeEligible(true,false,true,true,true,95,10,0,1,5,true,true))
            check(!MiniLeasePolicy.homeEligible(true,false,true,true,true,150,0,0,1,5,true,true))
            check(MiniLeasePolicy.selected(41,95,6000,2000,41,10167,10167,10057))
            check(MiniLeasePolicy.protects(41,95,6000,2000,41,95,0,0,5,true))
            check(!MiniLeasePolicy.protects(41,0,6000,2000,41,95,0,0,5,true))
            check(!MiniLeasePolicy.protects(41,95,6000,2000,41,0,0,0,5,true))
            check(!MiniLeasePolicy.protects(41,95,6000,2000,42,95,0,0,5,true))
            check(!MiniLeasePolicy.protects(41,95,6000,2000,41,95,10,0,5,true))
            check(!MiniLeasePolicy.protects(41,95,6000,2000,41,95,0,0,5,false))
            check(FrameworkLayoutPolicy.portraitEntry(true,false,true,true,95,0,1,5,825,1361,true,false,false))
            check(FrameworkLayoutPolicy.allowEdgeTop(true,false,true,95,0,1,5,1200,600))
            check(!FrameworkLayoutPolicy.allowEdgeTop(true,false,true,150,0,1,5,1200,600))
            check(HomeTransitionPolicy.preservePopup(true,"recents",true,95,0,1,5,6,1,true,true))
            check(!HomeTransitionPolicy.preservePopup(true,"recents",true,150,0,1,5,6,1,true,true))
        } finally {io.github.s24u.popupscale.CloneUserPolicy.replaceVerifiedClones(emptySet())}
        check(!io.github.s24u.popupscale.CloneUserPolicy.supported(95))
        println("PASS: verified owner clone layout, mini acquisition/selection/HOME protection; wrong task-user, secondary/work/private users and stale identities remain excluded.")
    }
}
