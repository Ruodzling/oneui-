package io.github.s24u.popupscale.gestures

import android.app.ActivityOptions
import android.app.PendingIntent
import android.graphics.Rect
import android.service.notification.StatusBarNotification
import android.view.View
import android.widget.FrameLayout
import de.robv.android.xposed.XposedHelpers as X

/** Reads routing metadata only. Notification text, images and Intent payloads are never read. */
@android.annotation.TargetApi(34)
class HeadsUpNotificationAdapter(val type:Class<*>) {
    private val entryField=sequenceOf("mEntryAdapter","mEntry").mapNotNull{n->
        runCatching{X.findField(type,n)}.getOrNull()
    }.first()
    private val height=X.findField(type,"mActualHeight")
    private val pinned=type.getMethod("isPinned")
    private fun sbn(owner:View):StatusBarNotification? {
        val entry=entryField.get(owner)?:return null
        return (runCatching{X.callMethod(entry,"getSbn")}.getOrNull()
            ?:Guard.field(entry,"mSbn")) as? StatusBarNotification
    }
    fun pending(owner:View)=sbn(owner)?.notification?.contentIntent
    fun owner(owner:View)=sbn(owner)?.let{it.packageName to it.uid}
    fun visible(owner:View)=pinned.invoke(owner)==true&&Guard.field(owner,"mIsHeadsUp")!=false
    fun bounds(owner:View,out:Rect):Boolean {
        if(!owner.getGlobalVisibleRect(out))return false
        // Row.height may include content cropped by its native actual-height property.
        val actual=height.getInt(owner)
        if(actual<=0)return false
        out.bottom=minOf(out.bottom,out.top+actual)
        return !out.isEmpty
    }
    fun launch(owner:View,host:FrameLayout,pending:PendingIntent,morph:NotificationMorphLaunchBridge?,
               validate:(View,PendingIntent)->Boolean,unsent:()->Unit):Boolean {
        val current=sbn(owner)?:return false
        if(current.notification.contentIntent!==pending)return false
        val identity=NotificationRouteIdentity(current.packageName,current.uid,android.os.SystemClock.uptimeMillis(),
            java.util.UUID.randomUUID().toString().replace("-",""))
        val b=LivePlacement.standardWindow(host.width,host.height)?:return false
        val destination=Rect(b.x,b.y,b.x+b.width,b.y+b.height)
        val entry=entryField.get(owner)
        val entryClick=entry?.let{NotificationMorphContract.entryClick(it.javaClass,owner.javaClass)}
        val starter=Guard.field(owner,"mNotificationActivityStarter")
        val click=starter?.javaClass?.methods?.firstOrNull{it.name=="onNotificationClicked"&&
            it.parameterTypes.size==2&&it.parameterTypes[1].isInstance(owner)&&
                (it.parameterTypes[0].isInstance(entry)||it.parameterTypes[0].isInstance(current))}
        val bubble=entry?.let{runCatching{X.callMethod(it,"isBubble") as? Boolean}.getOrNull()}==true
        val submit:(()->Unit)?=when{
            bubble->null // Native bubble clicks need not send an activity PendingIntent.
            entryClick!=null&&entry!=null->{ {entryClick.invoke(entry,owner);Unit} }
            click!=null&&starter!=null->{ {click.invoke(starter,if(click.parameterTypes[0].isInstance(entry))entry else current,owner);Unit} }
            else->null
        }
        val visible=Rect()
        if(!bounds(owner,visible))return false
        val origin=IntArray(2)
        owner.rootView.getLocationOnScreen(origin)
        visible.offset(origin[0],origin[1])
        if(submit!=null&&morph?.launch(owner,pending,destination,visible,identity,validate,unsent,submit)==true)return true
        val options=ActivityOptions.makeScaleUpAnimation(owner,0,0,visible.width(),visible.height()).apply{
            launchBounds=destination
            setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        // A user gesture on SystemUI sends the original conversation PendingIntent exactly once.
        pending.send(owner.context,0,null,null,null,null,
            NotificationLaunchOptions.notification(options.toBundle(),destination,identity))
        // Remove the banner through its manager, without dismissing the notification itself.
        val manager=Guard.field(owner,"mHeadsUpManager")
        if(manager!=null)Guard.call("notification-pull-unpin"){
            val remove=manager.javaClass.methods.firstOrNull{it.name=="removeNotification"&&
                it.parameterTypes.contentEquals(arrayOf(String::class.java,String::class.java,java.lang.Boolean.TYPE))}
            if(remove!=null)remove.invoke(manager,current.key,"PopupNotificationPull",true)
            else X.callMethod(manager,"removeNotification",current.key,true)
        }
        return true
    }
}
