package io.github.s24u.popupscale.gestures

/** Only the local freeform-task configuration pass may omit the display's top reserve. */
object FrameworkLayoutPolicy {
    fun organizerEntry(current:Int,requested:Int)=current==1&&requested==5
    fun popupEntryRequest(taskExists:Boolean,currentMode:Int,requestedMode:Int,upright:Boolean)=
        !taskExists||currentMode!=5||requestedMode==5||!upright
    fun launchMode(resolved:Int,requested:Int,current:Int)=
        if(resolved!=0)resolved else if(requested!=0)requested else current
    fun portraitEntry(enabled:Boolean,observe:Boolean,handles:Boolean,selected:Boolean,user:Int,display:Int,
                      activityType:Int,mode:Int,width:Int,height:Int,newEntry:Boolean,wide:Boolean,owned:Boolean)=
        enabled&&!observe&&handles&&selected&&io.github.s24u.popupscale.CloneUserPolicy.supported(user)&&display==0&&activityType==1&&mode==5&&
            width>0&&height>width&&newEntry&&!wide&&!owned
    fun allowEdgeTop(enabled:Boolean,observe:Boolean,selected:Boolean,user:Int,display:Int,
                     activityType:Int,mode:Int,width:Int,height:Int)=
        enabled&&!observe&&selected&&io.github.s24u.popupscale.CloneUserPolicy.supported(user)&&display==0&&activityType==1&&mode==5&&
            width>height&&height>0
}
