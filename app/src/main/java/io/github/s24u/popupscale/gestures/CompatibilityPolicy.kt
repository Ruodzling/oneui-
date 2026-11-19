package io.github.s24u.popupscale.gestures

/** Device admission is independent of model/build; each system feature has its own ABI probe. */
object CompatibilityPolicy {
    fun readiness(now:Long,sent:Long,ready:Boolean,version:Int,current:Int):Long? {
        if(version!=current||sent<0||now<sent||now-sent>3000)return null
        return if(ready)now+20000 else 0
    }
    fun platform(sdk:Int,manufacturer:String,brand:String)=sdk in 35..37&&
        (manufacturer.equals("samsung",true)||brand.equals("samsung",true))
    // Samsung 15 uses (x,y,ctrlType); newer Shell uses (ctrlType,...,x,y).
    // Keep probing, reading AND writing the mask on the same descriptor.
    val dragStartSignatures=listOf(listOf("float","float","int"),listOf("int","float","float"),
        listOf("int","int","int","float","float"))
    data class DragStart(val mask:Int,val x:Float,val y:Float,val maskIndex:Int)
    fun dragStart(args:Array<out Any?>):DragStart? {
        val indexes=when {
            args.size==3&&args[0] is Float&&args[1] is Float&&args[2] is Int->Triple(2,0,1)
            args.size==3&&args[0] is Int&&args[1] is Float&&args[2] is Float->Triple(0,1,2)
            args.size==5&&args.take(3).all{it is Int}&&args[3] is Float&&args[4] is Float->Triple(0,3,4)
            else->return null
        }
        val x=args[indexes.second] as Float;val y=args[indexes.third] as Float
        return if(x.isFinite()&&y.isFinite())DragStart(args[indexes.first] as Int,x,y,indexes.first) else null
    }
    fun dragCoordinates(args:Array<out Any?>):Pair<Float,Float>?=dragStart(args)?.let{it.x to it.y}
}

/** Exact method descriptors and inherited fields; lookup never initializes a candidate class. */
class CompatibilityProbe(private val resolve:(String)->Class<*>?,
                         private val exactFinder:((Class<*>,String,List<String>)->java.lang.reflect.Method?)?=null,
                         private val fieldFinder:((Class<*>,String)->Boolean)?=null) {
    fun type(name:String)=runCatching{resolve(name)}.getOrNull()
    private fun hierarchy(type:Class<*>)=generateSequence(type){it.superclass}
    fun field(owner:String,vararg names:String):Boolean {
        val c=type(owner) ?: return false
        return hierarchy(c).any{t->names.any{name->runCatching{
            fieldFinder?.invoke(t,name) ?: run{t.getDeclaredField(name);true}
        }.getOrDefault(false)}}
    }
    fun exact(owner:String,name:String,params:List<String>,returns:String?=null):java.lang.reflect.Method? {
        val c=type(owner) ?: return null
        return hierarchy(c).firstNotNullOfOrNull{t->runCatching{
            val m=if(exactFinder!=null)exactFinder.invoke(t,name,params) else t.declaredMethods.firstOrNull{
                it.name==name&&it.parameterTypes.map{p->p.name}==params
            }
            m?.takeIf{returns==null||it.returnType.name==returns}
        }.getOrNull()}
    }
    fun method(owner:String,name:String,params:List<String>?=null,returns:String?=null):Boolean {
        val c=type(owner) ?: return false
        if(params!=null)return exact(owner,name,params,returns)!=null
        return hierarchy(c).any{t->runCatching{t.declaredMethods.any{
            it.name==name&&(params==null||it.parameterTypes.map{p->p.name}==params)&&
                (returns==null||it.returnType.name==returns)
        }}.getOrDefault(false)}
    }
    fun first(candidates:List<String>,accept:(String)->Boolean)=candidates.firstOrNull{type(it)!=null&&accept(it)}
}
