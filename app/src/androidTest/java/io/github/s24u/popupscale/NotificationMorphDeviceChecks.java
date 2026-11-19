package io.github.s24u.popupscale;

import android.app.*;
import android.content.Context;
import android.graphics.Rect;
import android.os.*;
import io.github.s24u.popupscale.gestures.*;
import java.lang.reflect.*;
import java.util.concurrent.*;

/** Real native signatures/options and async callback transport, without launching a real notification. */
@android.annotation.SuppressLint({"BlockedPrivateApi","SoonBlockedPrivateApi"})
final class NotificationMorphDeviceChecks {
  static void run(Instrumentation runner){
    Bundle result=new Bundle();int code=Activity.RESULT_OK;
    ExecutorService worker=Executors.newSingleThreadExecutor();
    try{
      Context system=runner.getTargetContext().createPackageContext("com.android.systemui",Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);
      ClassLoader loader=system.getClassLoader();
      Class<?> animator=loader.loadClass("com.android.systemui.animation.ActivityTransitionAnimator");
      if(NotificationMorphContract.starters(animator).isEmpty())throw new Exception("Native animator callback contract missing");
      Class<?> starter=loader.loadClass("com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter");
      if(NotificationMorphContract.keyguardCallbacks(starter).isEmpty())throw new Exception("Native deferred-unlock callback contract missing");
      Class<?> row=loader.loadClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow");
      Class<?> entry=row.getDeclaredField("mEntryAdapter").getType();
      if(NotificationMorphContract.entryClick(entry,row)==null)throw new Exception("Native notification routing contract missing");
      Class<?> controller=loader.loadClass("com.android.systemui.statusbar.notification.NotificationTransitionAnimatorController");
      if(controller.getDeclaredField("notification").getType()!=row)throw new Exception("Native source controller contract missing");
      result.putString("nativeMorphAbi","PASS production starter detection, original entry routing and native notification-source controller contracts");

      NotificationCallbackScope<String> scope=new NotificationCallbackScope<>();
      Class<?> callback=NotificationMorphContract.starters(animator).get(0).getParameterTypes()[NotificationMorphContract.callbackIndex(NotificationMorphContract.starters(animator).get(0))];
      Method invoke=callback.getMethod("invoke",Object.class);
      Object original=Proxy.newProxyInstance(callback.getClassLoader(),new Class<?>[]{callback},(self,method,args)->{
        if(!"owned".equals(scope.current()))throw new AssertionError("Native callback lost owned scope");
        if("throw".equals(args[0]))throw new IllegalStateException("fixture");
        return ((Number)args[0]).intValue()+7;
      });
      Object wrapped=scope.wrap("owned",callback,original);
      Object async=worker.submit(()->{Object value=invoke.invoke(wrapped,35);if(scope.current()!=null)throw new AssertionError("Scope leaked to worker");return value;}).get(4,TimeUnit.SECONDS);
      if(!Integer.valueOf(42).equals(async)||scope.current()!=null)throw new Exception("Async return code or caller scope changed");
      scope.within("outer",()->{
        try{invoke.invoke(wrapped,35);}catch(Exception error){throw new RuntimeException(error);}
        if(!"outer".equals(scope.current()))throw new AssertionError("Parent callback scope not restored");
        return kotlin.Unit.INSTANCE;
      });
      try{invoke.invoke(wrapped,"throw");throw new Exception("Native callback exception swallowed");}
      catch(InvocationTargetException expected){if(!(expected.getCause() instanceof IllegalStateException))throw expected;}
      if(scope.current()!=null)throw new Exception("Failed callback leaked scope");
      Method gate=NotificationMorphContract.keyguardCallbacks(starter).get(0);
      Class<?> gateType=gate.getParameterTypes()[NotificationMorphContract.keyguardIndex(gate)];
      java.util.concurrent.atomic.AtomicBoolean gateScoped=new java.util.concurrent.atomic.AtomicBoolean();
      Object gateOriginal=Proxy.newProxyInstance(gateType.getClassLoader(),new Class<?>[]{gateType},(self,method,args)->{
        gateScoped.set("deferred".equals(scope.current()));return null;
      });
      Object gateWrapped=scope.wrap("deferred",gateType,gateOriginal);
      Method dismiss=gateType.getMethods()[0];
      Object[] gateArgs=new Object[dismiss.getParameterCount()];
      for(int n=0;n<gateArgs.length;n++)if(dismiss.getParameterTypes()[n]==boolean.class)gateArgs[n]=false;
      worker.submit(()->{dismiss.invoke(gateWrapped,gateArgs);if(scope.current()!=null)throw new AssertionError("Unlock scope leaked");return null;}).get(4,TimeUnit.SECONDS);
      if(!gateScoped.get())throw new Exception("Deferred unlock callback lost ownership");
      result.putString("asyncScope","PASS native callback interface across worker thread, native return value, nested restoration and exception propagation; no PendingIntent sent");

      Class<?> remoteRunner=Class.forName("android.view.IRemoteAnimationRunner"),adapter=Class.forName("android.view.RemoteAnimationAdapter");
      Binder binder=new Binder();
      Object fake=Proxy.newProxyInstance(remoteRunner.getClassLoader(),new Class<?>[]{remoteRunner},(self,method,args)->method.getName().equals("asBinder")?binder:null);
      Object remote=adapter.getConstructor(remoteRunner,long.class,long.class).newInstance(fake,400L,100L);
      Method make=ActivityOptions.class.getDeclaredMethod("makeRemoteAnimation",adapter);make.setAccessible(true);
      ActivityOptions nativeOptions=(ActivityOptions)make.invoke(null,remote);
      Bundle originalOptions=nativeOptions.toBundle();originalOptions.putString("test_native_morph","retained");
      Rect destination=new Rect(120,400,1000,1900);
      Bundle patched=NotificationLaunchOptions.freeform(originalOptions,destination);
      Method from=ActivityOptions.class.getDeclaredMethod("fromBundle",Bundle.class),mode=ActivityOptions.class.getDeclaredMethod("getLaunchWindowingMode");from.setAccessible(true);mode.setAccessible(true);
      ActivityOptions parsed=(ActivityOptions)from.invoke(null,patched);
      if(!NotificationLaunchOptions.hasRemoteAnimation(patched)||((Integer)mode.invoke(parsed))!=5||!destination.equals(parsed.getLaunchBounds())||!"retained".equals(patched.getString("test_native_morph")))
        throw new Exception("Native remote morph or destination lost during freeform adaptation");
      if(((Integer)mode.invoke(nativeOptions))==5)throw new Exception("Original native options mutated");
      result.putString("nativeRemoteOptions","PASS actual RemoteAnimationAdapter retained with mode5/destination/unknown options, original untouched; dummy runner never started");
      NotificationMorphTargetDeviceChecks.run(runner,result,loader);
    }catch(Throwable error){code=Activity.RESULT_CANCELED;result.putString("error",error.toString()+" cause="+error.getCause());}
    finally{worker.shutdownNow();}
    runner.finish(code,result);
  }
}
