package io.github.s24u.popupscale.gestures

/** HOME can animate an existing popup concurrently with unrelated fullscreen tasks. */
object HomeTransitionPolicy {
    data class Transition(val tasks:Set<Int>, val uncertain:Boolean=false, val displayChange:Boolean=false)
    enum class Decision { START, WAIT, EXPIRED }

    fun preservePopup(enabled:Boolean,reason:String,ownedMini:Boolean,user:Int,display:Int,type:Int,
                      windowMode:Int,changeMode:Int,forceHide:Int,sameBounds:Boolean,sameRotation:Boolean)=
        enabled&&(reason=="recents"||ownedMini)&&io.github.s24u.popupscale.CloneUserPolicy.supported(user)&&display==0&&type==1&&windowMode==5&&
            changeMode==6&&forceHide in 1..2&&sameBounds&&sameRotation

    fun decide(targets:Set<Int>, transitions:List<Transition>, now:Long, deadline:Long):Decision {
        if(now>=deadline||targets.isEmpty()||targets.any{it<0})return Decision.EXPIRED
        if(transitions.any{it.uncertain||it.displayChange||it.tasks.any(targets::contains)})return Decision.WAIT
        return Decision.START
    }
}
