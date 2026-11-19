package io.github.s24u.popupscale.gestures

object NotificationMorphTargetPolicy {
    fun accepts(expected:String,user:Int,pkg:String?,targetUser:Int,display:Int,activityType:Int,
        windowMode:Int,mode:Int,shell:Boolean,task:Int,width:Int,height:Int,validLeash:Boolean):Boolean =
        expected==pkg&&user==targetUser&&display==0&&activityType==1&&windowMode==5&&task>=0&&
        width>0&&height>0&&validLeash&&(if(shell)mode==1||mode==3||mode==6 else mode==0||mode==2)
}
