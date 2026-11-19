package io.github.s24u.popupscale.gestures

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import io.github.s24u.popupscale.R

/** Synthetic, silent SurfaceView video. No user media, account, network or background service. */
class LiveProbeActivity:Activity() {
    private val main=Handler(Looper.getMainLooper())
    private lateinit var video:VideoView
    private lateinit var status:TextView
    private lateinit var body:LinearLayout
    private lateinit var videoFrame:FrameLayout
    private lateinit var counter:Button
    private val textSizes=mutableListOf<Pair<TextView,Float>>()
    private var started=false
    private var prepared=false
    private var position=0
    private var clicks=0
    private var lastLog=0L
    private val ticker=object:Runnable {
        override fun run(){
            if(isFinishing||isDestroyed)return
            val p=runCatching{video.currentPosition}.getOrDefault(0)
            val playing=runCatching{video.isPlaying}.getOrDefault(false)
            if(started&&playing)position=p
            status.text="视频位置 ${p/1000.0} 秒\n计时 ${SystemClock.uptimeMillis()/100} · 点击 $clicks 次"
            if(SystemClock.uptimeMillis()-lastLog>=1000){
                lastLog=SystemClock.uptimeMillis()
                Log.i("PopupLiveProbe","started=$started playing=$playing position=$p multi=$isInMultiWindowMode focus=${hasWindowFocus()}")
            }
            main.postDelayed(this,200)
        }
    }
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        position=state?.getInt("position")?:0;clicks=state?.getInt("clicks")?:0
        val d=resources.displayMetrics.density
        fun dp(v:Int)=(v*d).toInt()
        body=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(20))
            setBackgroundColor(Color.rgb(245,247,250))
        }
        fun text(value:String,size:Float)=TextView(this).apply{
            text=value;textSize=size;setTextColor(Color.BLACK)
            textSizes.add(this to size)
        }
        body.addView(text("实时小窗视频测试",24f))
        body.addView(text("此视频是本机生成的静音动态图案。观察图案、播放位置和计时是否持续变化。",16f))
        val frame=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)}
        videoFrame=frame
        video=VideoView(this).apply{
            setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
            setOnPreparedListener{player->
                prepared=true;player.isLooping=true;player.setVolume(0f,0f)
                player.seekTo(position.toLong(),MediaPlayer.SEEK_CLOSEST)
                if(started)start()
            }
            setOnErrorListener{_,what,extra->Log.e("PopupLiveProbe","video error=$what extra=$extra");true}
        }
        frame.addView(video,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        body.addView(frame,LinearLayout.LayoutParams(-1,dp(220)))
        status=text("准备视频",18f);body.addView(status)
        counter=Button(this).apply{
            text="点击计数"
            setOnClickListener{clicks++;Log.i("PopupLiveProbe","button clicks=$clicks")}
        }
        body.addView(counter)
        body.addView(text("挂起后只点击迷你窗恢复。测试结束返回设置，可关闭实时模式或开启仅诊断。",16f))
        val scroll=ScrollView(this).apply{
            addView(body)
            setOnApplyWindowInsetsListener{view,insets->
                view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,insets.systemWindowInsetBottom);insets
            }
        }
        setContentView(scroll)
        applyContentMetrics()
        video.setVideoURI(Uri.parse("android.resource://$packageName/${R.raw.live_probe}"))
        main.post(ticker)
    }
    override fun onStart(){super.onStart();started=true;if(prepared)video.start();Log.i("PopupLiveProbe","onStart")}
    override fun onStop(){
        val current=runCatching{video.currentPosition}.getOrDefault(0)
        if(current>0)position=current
        started=false;video.pause();Log.i("PopupLiveProbe","onStop position=$position");super.onStop()
    }
    override fun onPause(){
        val current=runCatching{video.currentPosition}.getOrDefault(0)
        if(current>0)position=current
        super.onPause();Log.i("PopupLiveProbe","onPause multi=$isInMultiWindowMode")
    }
    override fun onResume(){super.onResume();Log.i("PopupLiveProbe","onResume multi=$isInMultiWindowMode")}
    override fun onWindowFocusChanged(focus:Boolean){super.onWindowFocusChanged(focus);Log.i("PopupLiveProbe","focus=$focus")}
    private fun applyContentMetrics(){
        // This Activity handles density changes to keep the SurfaceView/player alive. Android
        // updates Resources, but existing TextViews and pixel LayoutParams keep their old sizes.
        // Reapply their original SP/DP values; never multiply already-scaled pixel dimensions.
        val metrics=resources.displayMetrics
        fun dp(value:Int)=(value*metrics.density+0.5f).toInt()
        body.setPadding(dp(16),dp(12),dp(16),dp(20))
        textSizes.forEach{(view,sp)->view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,sp)}
        videoFrame.layoutParams=videoFrame.layoutParams.apply{height=dp(220)}
        counter.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,14f)
        counter.minimumWidth=dp(64);counter.minimumHeight=dp(48)
        counter.setPadding(dp(16),dp(8),dp(16),dp(8))
        Log.i("PopupLiveProbe","content metrics dpi=${metrics.densityDpi} titlePx=${textSizes.first().first.textSize} videoHeight=${videoFrame.layoutParams.height}")
    }
    override fun onConfigurationChanged(config:Configuration){
        super.onConfigurationChanged(config)
        applyContentMetrics()
        Log.i("PopupLiveProbe","configuration multi=$isInMultiWindowMode")
    }
    override fun onSaveInstanceState(state:Bundle){
        val current=runCatching{video.currentPosition}.getOrDefault(0)
        state.putInt("position",if(current>0)current else position);state.putInt("clicks",clicks)
        super.onSaveInstanceState(state)
    }
    override fun onDestroy(){main.removeCallbacks(ticker);video.stopPlayback();super.onDestroy()}
}
