package io.github.s24u.popupscale.gestures

/** Only a normal freeform handle caption can expose its backing color as a header. */
object CaptionFillPolicy {
    private val host=Regex("^Additional window of Task=(\\d+) \\(Freeform Outline\\)$")
    @JvmStatic fun taskFromHostTitle(title:String?)=title?.let{host.matchEntire(it)}?.groupValues?.get(1)?.toIntOrNull()
    fun clear(enabled:Boolean,observe:Boolean,frameless:Boolean,selected:Boolean,mode:Int,handleCaption:Boolean)=
        enabled&&!observe&&frameless&&selected&&mode==5&&handleCaption
}
