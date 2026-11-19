package io.github.s24u.popupscale.gestures

/** Offline regressions drawn from the One UI7 diagnostic descriptors/ownership layout. */
object LegacyHandleChecks {
    fun run(){
        val old=LegacyDragFixture();val modern=ModernDragFixture()
        val probe=CompatibilityProbe({when(it){"old"->old.javaClass;"modern"->modern.javaClass;else->null}})
        for((name,fixture,args) in listOf(
            Triple("old",old,arrayOf<Any>(30f,44f,8)),
            Triple("modern",modern,arrayOf<Any>(8,0,7,30f,44f)))){
            val method=CompatibilityPolicy.dragStartSignatures.firstNotNullOfOrNull{
                probe.exact(name,"onDragPositioningStart",it,"java.lang.String")
            } ?: error("reported legacy/new START must match")
            val start=checkNotNull(CompatibilityPolicy.dragStart(args))
            check(start.mask==8&&start.x==30f&&start.y==44f)
            args[start.maskIndex]=9 // bottom-left corner expansion must change the MASK only
            val returned=method.invoke(fixture,*args) as String
            check(returned.startsWith("30.0/44.0/9"))
            check(CompatibilityPolicy.dragCoordinates(args)==(30f to 44f))
        }
        check(CompatibilityPolicy.dragStart(arrayOf(8,30f,44f))?.maskIndex==0)
        check(CompatibilityPolicy.dragStart(arrayOf(30f,44f,8))?.maskIndex==2)
        check(CompatibilityPolicy.dragStart(arrayOf(30.0,44f,8))==null)
        check(CompatibilityPolicy.dragStart(arrayOf(Float.POSITIVE_INFINITY,44f,8))==null)
        check(CompatibilityPolicy.dragStart(arrayOf(1,2,30f,44f))==null)

        fun read(owner:Any,name:String):Any?=generateSequence(owner.javaClass){it.superclass}
            .firstNotNullOfOrNull{type->runCatching{type.getDeclaredField(name).apply{isAccessible=true}.get(owner)}.getOrNull()}
        fun values(value:Any?):List<Any> = (value as? Map<*,*>)?.values?.filterNotNull().orEmpty()
        val first=UnknownDecorationFixture(10);val equalButDifferent=UnknownDecorationFixture(10)
        check(first==equalButDifferent&&first!==equalButDifferent)
        val model=LegacyModelFixture(linkedMapOf(10 to first,11 to first,12 to equalButDifferent))
        val listener=LegacyListenerFixture(java.util.Optional.of(model))
        val found=DecorationDiscovery.collect(listOf(listener,listener),::read,::values,{true})
        check(found.models.single()===model)
        check(found.decorations.size==2&&found.decorations[0]===first&&found.decorations[1]===equalButDifferent)
        check(DecorationDiscovery.collect(listOf(LegacyListenerFixture(java.util.Optional.empty<Any>())),::read,::values,{true}).decorations.isEmpty())
        check(DecorationDiscovery.collect(listOf(listener),::read,::values,{it!==model}).decorations.isEmpty())
        check(DecorationDiscovery.collect(listOf(UnrelatedGraphFixture(listOf(listener))),::read,::values,{true}).decorations.isEmpty())
        val many=LegacyModelFixture((0..100).associateWith{UnknownDecorationFixture(it)})
        check(DecorationDiscovery.collect(listOf(LegacyListenerFixture(many)),::read,::values,{true}).decorations.size==64)
        println("PASS: reported One UI7 x/y/mask START invokes correctly after mask rewrite; modern START preserved; existing/optional/inherited ViewModel discovers unknown decorations by identity; unrelated/empty/untrusted graphs excluded, bounded at64.")
    }
}
