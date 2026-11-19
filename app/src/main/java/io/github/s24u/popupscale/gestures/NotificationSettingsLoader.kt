package io.github.s24u.popupscale.gestures

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executor

/** A missing boot-time provider is temporary. Never replace missing settings with enabled defaults. */
class NotificationSettingsLoader(private val main:Handler,private val worker:Executor,
    private val read:()->Map<String,Any>,private val accepted:(Map<String,Any>)->Unit,
    private val unavailable:(Int)->Unit,private val delays:LongArray=longArrayOf(500,1000,2000,5000,15000)) {
    private var generation=0
    private var busy=false
    private var rerun=false
    private var scheduled=false
    private var closed=false
    private var attempts=0
    private var last=-1000L
    private val retry=Runnable{scheduled=false;request()}
    fun request(force:Boolean=false){
        if(Looper.myLooper()!=main.looper){main.post{request(force)};return}
        if(closed)return
        if(force){generation++;attempts=0;main.removeCallbacks(retry);scheduled=false}
        if(busy){if(force)rerun=true;return}
        if(scheduled)return
        val wait=250-(SystemClock.elapsedRealtime()-last)
        if(wait>0){schedule(wait);return}
        busy=true;last=SystemClock.elapsedRealtime();val stamp=generation
        worker.execute{
            val fresh=runCatching{read()}.getOrDefault(emptyMap())
            main.post{
                busy=false
                if(closed)return@post
                if(stamp!=generation||rerun){rerun=false;request();return@post}
                if(fresh["enabled"] is Boolean){attempts=0;accepted(fresh)}else{
                    attempts++;unavailable(attempts)
                    if(attempts<=delays.size)schedule(delays[attempts-1])
                }
            }
        }
    }
    private fun schedule(delay:Long){scheduled=true;main.postDelayed(retry,delay.coerceAtLeast(1))}
    fun close(){
        if(Looper.myLooper()!=main.looper){main.post{close()};return}
        closed=true;generation++;main.removeCallbacks(retry);scheduled=false
    }
}
