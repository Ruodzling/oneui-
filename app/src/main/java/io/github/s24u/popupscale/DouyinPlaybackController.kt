package io.github.s24u.popupscale

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.Log
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers as X
import io.github.s24u.popupscale.gestures.MiniPlaybackPolicy
import io.github.s24u.popupscale.gestures.MiniPlaybackSnapshot
import java.lang.ref.WeakReference

/** Read-only observation. Never suppresses lifecycle, pause, stop, or calls play/resume. */
object DouyinPlaybackController {
    private val FEED_ACTIVITIES=setOf(
        "com.ss.android.ugc.aweme.main.MainActivity",
        "com.ss.android.ugc.aweme.splash.SplashActivity"
    )
    private val snapshots=MiniPlaybackSnapshot()
    private var activity=WeakReference<Activity>(null)
    private var registeredApp=WeakReference<Application>(null)
    private var registered=false
    private var installed=false
    private var capability="not-installed"
    private var lastDecision="none"
    private var lastObservation="none"
    private var registrationStage="not-installed"
    private fun log(message:String){
        Log.i("PopupScale","[mini-playback] $message")
    }

    private class Reader(val kind:String,val type:Class<*>,val playing:java.lang.reflect.Method,
                         val render:java.lang.reflect.Method?=null,val selfView:java.lang.reflect.Method?=null){
        private val instances=java.util.concurrent.CopyOnWriteArrayList<WeakReference<Any>>()
        fun remember(value:Any){
            instances.removeAll{it.get()==null||it.get()===value}
            instances.add(WeakReference(value))
            while(instances.size>64)instances.removeAt(0)
        }
        fun sample(a:Activity):Boolean? {
            val players=instances.mapNotNull{it.get()}
            if(kind=="feed"){
                if(a.javaClass.name !in FEED_ACTIVITIES||players.size!=1)return null
                return playing.invoke(players.single()) as? Boolean
            }
            var found=false;var anyPlaying=false
            for(player in players){
                val state=runCatching{
                    val target=render?.invoke(player)?:return@runCatching null
                    val view=(if(target is View)target else selfView?.invoke(target)) as? View?:return@runCatching null
                    // Exclude preloaded/previous/background rooms, without reading room IDs,
                    // titles, URLs, screenshots, or application text. A released cached
                    // client throwing must not hide a different valid visible client.
                    if(!view.isAttachedToWindow||!view.isShown||view.width<=0||view.height<=0||
                        view.rootView!==a.window.decorView.rootView)return@runCatching null
                    playing.invoke(player) as? Boolean
                }.getOrNull()?:continue
                found=true;anyPlaying=anyPlaying||state
            }
            return if(found)anyPlaying else null
        }
    }
    private fun reader(loader:ClassLoader,name:String,kind:String):Reader?=runCatching {
        val type=Class.forName(name,false,loader)
        val playing=type.getMethod("isPlaying").takeIf{it.returnType==java.lang.Boolean.TYPE}?:return@runCatching null
        val render=if(kind=="live")type.getMethod("getRenderView") else null
        val self=render?.returnType?.let{if(View::class.java.isAssignableFrom(it))null else it.getMethod("getSelfView")}
        if(self!=null&&!View::class.java.isAssignableFrom(self.returnType))return@runCatching null
        Reader(kind,type,playing,render,self).also{value->
            XposedBridge.hookAllConstructors(type,object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){if(!p.hasThrowable())value.remember(p.thisObject)}
            })
        }
    }.getOrNull()

    @JvmStatic fun install(loader:ClassLoader,name:String){
        if(name!=MiniPlaybackPolicy.PACKAGE||android.os.Build.VERSION.SDK_INT<34||installed)return
        installed=true
        registrationStage="install-start"
        log("install-start process=${Application.getProcessName()} uid=${android.os.Process.myUid()} module=${BuildConfig.VERSION_CODE}")
        // Verify named getter contracts at runtime; no app-version or obfuscated-field binding.
        var live=reader(loader,"com.bytedance.android.livesdk.player.LivePlayerClient","live")
        var feed=reader(loader,"com.ss.android.ugc.aweme.feed.controller.FeedPlayerWrapper","feed")
        capability="feed=${feed!=null} live=${live!=null}"
        log("reader-contracts $capability")
        fun capture(a:Activity,stage:String="pause"){
            if(a.packageName!=name)return
            if(!snapshots.needsCapture(a.taskId,a.javaClass.name))return
            val liveState=runCatching{live?.sample(a)}.getOrNull()
            val feedState=if(liveState==null)runCatching{feed?.sample(a)}.getOrNull() else null
            val state=liveState?:feedState
            val source=if(liveState!=null)"live-visible" else if(feedState!=null)"feed" else "unavailable"
            snapshots.capture(a.taskId,a.javaClass.name,state,source,SystemClock.uptimeMillis())
            lastObservation="task=${a.taskId} stage=$stage source=$source playing=${state?:"unknown"}"
            log("pre-pause $lastObservation")
        }
        // Some feeds pause at top-resumed loss, before onPause. Observe before that
        // actual framework dispatch when present; onPause stays the older-ROM fallback.
        X.findMethodExactIfExists(Activity::class.java,"performTopResumedActivityChanged",
            java.lang.Boolean.TYPE,String::class.java)?.let{method->
            XposedBridge.hookMethod(method,object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    if(registered&&p.args[0]==false)runCatching{capture(p.thisObject as Activity,"top-resumed-lost")}
                        .onFailure{log("capture failed type=${it.javaClass.simpleName}")}
                }
            })
        }
        X.findAndHookMethod(android.app.Instrumentation::class.java,"callActivityOnPause",Activity::class.java,object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                if(!registered)return
                runCatching{capture(p.args[0] as Activity)}.onFailure{log("capture failed type=${it.javaClass.simpleName}")}
            }
        })
        fun refreshContracts(cl:ClassLoader?){
            if(cl==null)return
            if(live==null)live=reader(cl,"com.bytedance.android.livesdk.player.LivePlayerClient","live")
            if(feed==null)feed=reader(cl,"com.ss.android.ugc.aweme.feed.controller.FeedPlayerWrapper","feed")
            val next="feed=${feed!=null} live=${live!=null}"
            if(next!=capability){capability=next;log("reader-contracts $capability")}
        }
        fun registerApp(app:Application,origin:String){
            if(app.baseContext==null)return
            refreshContracts(app.classLoader)
            if(registered&&registeredApp.get()!=null)return
            registrationStage="application-callback"
            log("application-callback origin=$origin type=${app.javaClass.name} uid=${android.os.Process.myUid()}")
            if(app.packageName!=name||android.os.Process.myUid()/100000!=0){
                registrationStage="application-rejected";log("application-rejected wrong-package-or-user");return
            }
            runCatching{
                registrationStage="registering"
                app.registerActivityLifecycleCallbacks(object:Application.ActivityLifecycleCallbacks{
                    override fun onActivityCreated(a:Activity,state:Bundle?){
                        activity=WeakReference(a);snapshots.created(a.taskId,a.javaClass.name)
                        val weak=WeakReference(a)
                        a.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener{focused->
                            if(focused)weak.get()?.let{
                                activity=WeakReference(it);snapshots.foreground(it.taskId,it.javaClass.name)
                            }
                        }
                    }
                    override fun onActivityResumed(a:Activity){
                        activity=WeakReference(a)
                        if(a.hasWindowFocus())snapshots.foreground(a.taskId,a.javaClass.name)
                    }
                    override fun onActivityDestroyed(a:Activity){
                        if(a.isFinishing)snapshots.forget(a.taskId)
                        if(activity.get()===a)activity.clear()
                    }
                    override fun onActivityStarted(a:Activity){}
                    override fun onActivityPaused(a:Activity){}
                    override fun onActivityStopped(a:Activity){}
                    override fun onActivitySaveInstanceState(a:Activity,state:Bundle){}
                })
                val uid=app.packageManager.getApplicationInfo("com.android.systemui",0).uid
                app.registerReceiver(object:BroadcastReceiver(){
                    override fun onReceive(context:Context,intent:Intent){
                        val trusted=MiniPlaybackPolicy.trusted(sentFromUid,uid,intent.getIntExtra("version",-1),
                                BuildConfig.VERSION_CODE,SystemClock.uptimeMillis(),intent.getLongExtra("when",-10000))
                        if(!trusted)return
                        val reply=intent.getParcelableExtra("reply",ResultReceiver::class.java)?:return
                        val task=intent.getIntExtra("task",-1);val a=activity.get()
                        val owned=runCatching{
                            a!=null&&!a.isDestroyed&&!a.isFinishing&&a.taskId==task&&
                                a.getSystemService(ActivityManager::class.java).appTasks.any{it.taskInfo.taskId==task}&&
                                X.callMethod(X.getObjectField(a.resources.configuration,"windowConfiguration"),"getWindowingMode")==5&&
                                !a.getSystemService(KeyguardManager::class.java).isKeyguardLocked
                        }.getOrDefault(false)
                        if(owned&&a!=null)runCatching{capture(a)}
                        val decision=if(owned&&a!=null)snapshots.decision(task,a.javaClass.name,SystemClock.uptimeMillis()) else null
                        val allowed=owned&&decision?.allowed==true
                        val reason=decision?.reason?:"wrong-task-or-locked"
                        lastDecision="task=$task owned=$owned reason=$reason source=${decision?.source?:"none"} age=${decision?.age?:-1}"
                        reply.send(if(allowed)1 else 0,Bundle().apply{
                            putInt("task",task);putInt("version",BuildConfig.VERSION_CODE)
                            putString("reason",reason);putString("source",decision?.source?:"none")
                        })
                        log(lastDecision)
                    }
                },IntentFilter(MiniPlaybackPolicy.ACTION),"android.permission.MANAGE_ACTIVITY_TASKS",
                    Handler(Looper.getMainLooper()),Context.RECEIVER_EXPORTED)
                registeredApp=WeakReference(app)
                registered=true
                registrationStage="registered"
                log("ready origin=$origin appVersion=${app.packageManager.getPackageInfo(name,0).longVersionCode} $capability; read-only")
            }.onFailure{registrationStage="registration-failed";log("registration failed type=${it.javaClass.simpleName}")}
        }
        // Hook final Application.attach as well as callApplicationOnCreate so custom/wrapped
        // Instrumentation delegates cannot bypass receiver registration.
        XposedBridge.hookAllMethods(Application::class.java,"attach",object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                val app=p.thisObject as? Application ?: return
                registerApp(app,"application-attach")
            }
        })
        X.findAndHookMethod(android.app.Instrumentation::class.java,"callApplicationOnCreate",Application::class.java,object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                val app=p.args[0] as? Application ?: return
                registerApp(app,"application-create")
            }
        })
        registrationStage="awaiting-application"
        log("lifecycle-hooks-ready awaiting-application")
        runCatching{android.app.AndroidAppHelper.currentApplication()}.getOrNull()?.let{registerApp(it,"current-application")}
    }
}
