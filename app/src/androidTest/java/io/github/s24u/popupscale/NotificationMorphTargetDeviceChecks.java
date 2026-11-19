package io.github.s24u.popupscale;

import android.app.*;
import android.content.ComponentName;
import android.graphics.*;
import android.os.Bundle;
import android.view.SurfaceControl;
import io.github.s24u.popupscale.gestures.NotificationMorphTargetAdapter;
import io.github.s24u.popupscale.gestures.NotificationMorphExtractionGate;
import java.lang.reflect.*;

/** A hidden, detached surface and synthetic own-task metadata; never shows a layer or alters real tasks. */
@android.annotation.SuppressLint({"BlockedPrivateApi","SoonBlockedPrivateApi"})
@android.annotation.TargetApi(29)
final class NotificationMorphTargetDeviceChecks {
  static void run(Instrumentation runner,Bundle result,ClassLoader loader)throws Exception{
    SurfaceControl leash=null;
    try{
      Object builder=Class.forName("android.view.SurfaceControl$Builder").getConstructor().newInstance();
      builder.getClass().getMethod("setName",String.class).invoke(builder,"PopupScaleMorphFixture");
      builder.getClass().getMethod("setHidden",boolean.class).invoke(builder,true);
      builder.getClass().getMethod("setContainerLayer").invoke(builder);
      leash=(SurfaceControl)builder.getClass().getMethod("build").invoke(builder);
      ActivityManager.RunningTaskInfo info=new ActivityManager.RunningTaskInfo();
      String pkg=runner.getTargetContext().getPackageName();
      set(info,"taskId",910001);set(info,"userId",0);set(info,"displayId",0);
      set(info,"topActivity",new ComponentName(pkg,DemoActivity.class.getName()));
      Object config=get(info,"configuration"),wc=get(config,"windowConfiguration");
      wc.getClass().getMethod("setWindowingMode",int.class).invoke(wc,5);
      wc.getClass().getMethod("setActivityType",int.class).invoke(wc,1);
      Class<?> target=Class.forName("android.view.RemoteAnimationTarget"),wcType=Class.forName("android.app.WindowConfiguration");
      Constructor<?> constructor=target.getConstructor(int.class,int.class,SurfaceControl.class,boolean.class,Rect.class,Rect.class,
          int.class,Point.class,Rect.class,Rect.class,wcType,boolean.class,SurfaceControl.class,Rect.class,ActivityManager.RunningTaskInfo.class,boolean.class);
      Rect end=new Rect(120,400,1000,1800);
      Object own=constructor.newInstance(910001,2,leash,true,new Rect(0,0,880,1400),new Rect(),0,new Point(120,400),
          new Rect(0,0,880,1400),end,wc,false,null,new Rect(end),info,false);
      NotificationMorphTargetAdapter adapter=new NotificationMorphTargetAdapter(loader);
      NotificationMorphTargetAdapter.Selection selected=adapter.legacy(new Object[]{own},null,pkg,0);
      if(selected==null||selected.getTask()!=910001||selected.getMode()!=2||!selected.getTranslucent())throw new Exception("Own translucent change target not recovered");
      if(!end.equals(get(selected.getSurface(),"screenSpaceBounds"))||get(selected.getSurface(),"leash")!=leash)throw new Exception("Native surface conversion lost original leash/bounds");
      if(((Integer)get(own,"mode"))!=2||!Boolean.TRUE.equals(get(own,"isTranslucent")))throw new Exception("Native target metadata was rewritten");
      if(adapter.legacy(new Object[]{own,own},null,pkg,0)!=null)throw new Exception("Ambiguous target accepted");
      if(adapter.legacy(new Object[]{own},null,"other.package",0)!=null||adapter.legacy(new Object[]{own},null,pkg,95)!=null)throw new Exception("Other app/profile target accepted");
      Class<?> changeType=Class.forName("android.window.TransitionInfo$Change"),token=Class.forName("android.window.WindowContainerToken"),transition=Class.forName("android.window.TransitionInfo");
      Object change=changeType.getConstructor(token,SurfaceControl.class).newInstance(null,leash);
      changeType.getMethod("setTaskInfo",ActivityManager.RunningTaskInfo.class).invoke(change,info);
      changeType.getMethod("setMode",int.class).invoke(change,6);
      changeType.getMethod("setFlags",int.class).invoke(change,0x2100000);
      changeType.getMethod("setStartAbsBounds",Rect.class).invoke(change,new Rect(end));
      changeType.getMethod("setEndAbsBounds",Rect.class).invoke(change,new Rect(end));
      changeType.getMethod("setEndRelOffset",int.class,int.class).invoke(change,120,400);
      wc.getClass().getMethod("setAlwaysOnTop",boolean.class).invoke(wc,false);
      changeType.getMethod("setConfiguration",android.content.res.Configuration.class).invoke(change,config);
      Object transitionInfo=transition.getConstructor(int.class,int.class).newInstance(6,0);
      transition.getMethod("addChange",changeType).invoke(transitionInfo,change);
      Class<?> util=loader.loadClass("com.android.wm.shell.shared.TransitionUtil");
      boolean stationary=(Boolean)util.getMethod("isStationary",changeType).invoke(null,change);
      if(!stationary)throw new Exception("Equal-bounds native stationary fixture not reproduced");
      Class<?> transaction=Class.forName("android.view.SurfaceControl$Transaction");
      Object unusedTransaction=transaction.getConstructor().newInstance();
      try{
        Class<?> compat=loader.loadClass("com.android.systemui.animation.RemoteAnimationTargetCompat");
        Object[] wrapped=(Object[])compat.getMethod("wrap",transition,transaction,android.util.ArrayMap.class,java.util.function.Predicate.class)
            .invoke(null,transitionInfo,unusedTransaction,new android.util.ArrayMap<>(),(java.util.function.Predicate<Object>)v->true);
        if(wrapped.length!=0)throw new Exception("Native compatibility conversion did not reproduce empty legacy target list");
      }finally{transaction.getMethod("close").invoke(unusedTransaction);}
      NotificationMorphTargetAdapter.Selection shell=adapter.shell(transitionInfo,null,pkg,0);
      if(shell==null||shell.getMode()!=6||shell.getTask()!=910001||!end.equals(get(shell.getSurface(),"screenSpaceBounds")))throw new Exception("Original Shell CHANGE target not recovered when legacy list is empty");
      if(((Integer)changeType.getMethod("getMode").invoke(change))!=6)throw new Exception("Original Shell mode rewritten");
      // Match the actual failed event: translucent, always-on-top TO_FRONT, not a synthetic CHANGE.
      changeType.getMethod("setMode",int.class).invoke(change,3);
      changeType.getMethod("setFlags",int.class).invoke(change,4);
      wc.getClass().getMethod("setAlwaysOnTop",boolean.class).invoke(wc,true);
      changeType.getMethod("setConfiguration",android.content.res.Configuration.class).invoke(change,config);
      if(adapter.shellChange(transitionInfo,pkg,0)!=change)throw new Exception("Native TO_FRONT notification change not identified before extraction");
      Rect nativeBounds=(Rect)changeType.getMethod("getEndAbsBounds").invoke(change);
      if(changeType.getMethod("getEndAbsBounds").invoke(change)!=nativeBounds)throw new Exception("Native Change bounds identity is unstable");
      Class<?> remoteHandler=loader.loadClass("com.android.wm.shell.transition.RemoteTransitionHandler");
      Class<?> nativeProvider=loader.loadClass("com.android.wm.shell.transition.MultiTaskingTransitionProvider");
      if(nativeProvider.getDeclaredMethod("loadAnimationFromResources",int.class,Rect.class).getReturnType()!=android.view.animation.Animation.class)
        throw new Exception("Native resource animation skip contract missing");
      remoteHandler.getDeclaredField("mRequestedRemotes");remoteHandler.getDeclaredField("mMultiTaskingTransitions");
      remoteHandler.getDeclaredMethod("shouldExcludeFromRemoteTargets",changeType);
      Object provider=new Object();NotificationMorphExtractionGate gate=new NotificationMorphExtractionGate();
      transition.getMethod("addRootLeash",int.class,SurfaceControl.class,int.class,int.class).invoke(transitionInfo,0,leash,53,81);
      changeType.getMethod("setDisplayId",int.class,int.class).invoke(change,0,0);
      if(!new Point(53,81).equals(adapter.rootOffset(transitionInfo,pkg,0)))throw new Exception("Native transition-root offset not selected");
      NotificationMorphExtractionGate.Lease lease=gate.begin(provider,nativeBounds);
      try{
        if(!gate.owns(provider,changeType.getMethod("getEndAbsBounds").invoke(change))||gate.owns(provider,new Rect(nativeBounds))||gate.owns(new Object(),nativeBounds))
          throw new Exception("Extraction guard accepted another provider/bounds object");
        NotificationMorphTargetAdapter.Selection front=adapter.shell(transitionInfo,null,pkg,0);
        if(front==null||front.getMode()!=3||!Boolean.TRUE.equals(get(front.getSurface(),"isTranslucent")))throw new Exception("Original translucent TO_FRONT native surface lost");
        if(!adapter.ownsSurface(front.getSurface(),pkg,0)||adapter.ownsSurface(front.getSurface(),"other.package",0)||adapter.ownsSurface(front.getSurface(),pkg,95))
          throw new Exception("Drawing surface ownership check failed");
        if(adapter.shellChange(transitionInfo,"other.package",0)!=null||adapter.shellChange(transitionInfo,pkg,95)!=null)
          throw new Exception("Extraction guard selected another app/profile");
      }finally{gate.end(lease);}
      if(gate.owns(provider,nativeBounds))throw new Exception("Extraction guard leaked beyond native handler invocation");
      result.putString("nativePreExtraction","PASS actual native resource/exclusion contracts and stable Change bounds identity; production guard retains own translucent always-on-top TO_FRONT surface and excludes another provider/copy/app/profile; no native animation or real task launched");
      result.putString("nativeStationaryRecovery","PASS actual native compatibility conversion produces EMPTY targets for equal-bounds CHANGE; production adapter recovers real native AnimatedSurface from original TransitionInfo with unchanged mode/bounds; unused transaction never applied");
      result.putString("nativeTargetRecovery","PASS actual hidden-surface RemoteAnimationTarget→native AnimatedSurface, translucent CHANGE metadata preserved, own app/profile only, ambiguity rejected; layer never shown and no task/transaction modified");
    }finally{if(leash!=null)leash.release();}
  }
  private static Field field(Object value,String name)throws Exception{
    for(Class<?> c=value.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}
    throw new NoSuchFieldException(name);
  }
  private static Object get(Object value,String name)throws Exception{return field(value,name).get(value);}
  private static void set(Object value,String name,Object data)throws Exception{field(value,name).set(value,data);}
}
