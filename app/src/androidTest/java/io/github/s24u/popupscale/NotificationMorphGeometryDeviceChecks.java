package io.github.s24u.popupscale;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import io.github.s24u.popupscale.gestures.NotificationMorphFrameAdapter;
import java.lang.reflect.*;

/** Execute the firmware's actual frame math, but disable its transaction applier before calling.
 * The fixture surface stays hidden and detached; only a separate own demo Activity is opened. */
@android.annotation.SuppressLint({"BlockedPrivateApi","SoonBlockedPrivateApi"})
@android.annotation.TargetApi(29)
final class NotificationMorphGeometryDeviceChecks {
  static void run(Instrumentation runner){
    Bundle result=new Bundle();Activity page=null;int code=Activity.RESULT_OK;
    try{
      Context system=runner.getTargetContext().createPackageContext("com.android.systemui",Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);
      ClassLoader loader=system.getClassLoader();
      page=runner.startActivitySync(new Intent(runner.getTargetContext(),DemoActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
      runner.waitForIdleSync();Activity own=page;
      final Throwable[] failure={null};
      runner.runOnMainSync(()->{try{check(own,loader,result);}catch(Throwable error){failure[0]=error;}});
      if(failure[0]!=null)throw new Exception("Native frame geometry failed",failure[0]);
    }catch(Throwable error){code=Activity.RESULT_CANCELED;result.putString("error",error+" cause="+error.getCause());}
    finally{if(page!=null){Activity own=page;runner.runOnMainSync(own::finishAndRemoveTask);}}
    runner.finish(code,result);
  }
  private static void check(Activity page,ClassLoader loader,Bundle result)throws Exception{
    SurfaceControl leash=null;
    try{
      Object builder=Class.forName("android.view.SurfaceControl$Builder").getConstructor().newInstance();
      builder.getClass().getMethod("setName",String.class).invoke(builder,"PopupScaleMorphGeometryFixture");
      builder.getClass().getMethod("setHidden",boolean.class).invoke(builder,true);
      builder.getClass().getMethod("setContainerLayer").invoke(builder);
      leash=(SurfaceControl)builder.getClass().getMethod("build").invoke(builder);
      ViewGroup container=(ViewGroup)page.getWindow().getDecorView();
      Class<?> controllerType=loader.loadClass("com.android.systemui.animation.ActivityTransitionAnimator$Controller");
      Object controller=Proxy.newProxyInstance(loader,new Class<?>[]{controllerType},(self,method,args)->{
        if(method.getName().equals("getTransitionContainer"))return container;
        if(method.getName().equals("getOpeningWindowSyncView"))return container;
        if(method.getName().equals("isLaunching"))return true;
        if(method.getReturnType()==boolean.class)return false;
        if(method.getReturnType()==int.class)return 0;
        return null;
      });
      Class<?> internal=loader.loadClass("com.android.systemui.animation.ActivityTransitionAnimator$AnimationDelegateInternal");
      Constructor<?> constructor=java.util.Arrays.stream(internal.getConstructors()).filter(c->c.getParameterCount()==7).findFirst().orElseThrow();
      Object delegate=constructor.newInstance((java.util.concurrent.Executor)Runnable::run,controller,null,null,null,true,false);
      // Native catches the deliberately-null applier after calculating matrix/crop. Nothing is scheduled/applied.
      set(delegate,"transactionApplier",null);
      Class<?> animated=loader.loadClass("com.android.wm.shell.shared.compat.AnimatedSurface");
      Object surface=animated.getConstructor().newInstance();
      Class<?> windowState=Class.forName("android.window.WindowAnimationState");
      Object end=windowState.getConstructor().newInstance();RectF destination=new RectF(173,657,1267,2462);
      set(end,"bounds",new RectF(destination));set(surface,"endState",end);set(surface,"leash",leash);
      set(surface,"screenSpaceBounds",new Rect(173,657,1267,2462));
      Class<?> stateType=loader.loadClass("com.android.systemui.animation.TransitionAnimator$State");
      Method apply=internal.getMethod("applyStateToWindow",animated,stateType,float.class,boolean.class,Class.forName("android.view.SurfaceControl$Transaction"));
      Object state=state(stateType,173,657,1267,2462);
      apply.invoke(delegate,surface,state,1f,false,null);
      RectF wrong=mapped(delegate,1094,1805);
      assertRect(wrong,new RectF(0,0,1094,1805),"Native baseline did not reproduce zero-origin displacement");
      Object local=NotificationMorphFrameAdapter.localSurface(surface);
      if(get(local,"leash")!=leash||get(local,"screenSpaceBounds")!=get(surface,"screenSpaceBounds")||get(local,"endState")==end)
        throw new Exception("Drawing copy lost native ownership or mutated destination state");
      assertRect((RectF)get(end,"bounds"),destination,"Original destination changed");
      apply.invoke(delegate,local,state,1f,false,null);
      assertRect(mapped(delegate,1094,1805),destination,"Final native frame does not land at destination");
      if(!new Rect(0,0,1094,1805).equals(get(delegate,"windowCrop")))throw new Exception("Final native crop is not the complete local window");
      RectF middle=new RectF(100,200,1000,1000);
      Object mid=state(stateType,100,200,1000,1000);
      apply.invoke(delegate,local,mid,0.5f,false,null);
      Matrix matrix=new Matrix((Matrix)get(delegate,"matrix"));Rect crop=new Rect((Rect)get(delegate,"windowCrop"));
      RectF visible=new RectF(crop);matrix.mapRect(visible);assertRect(visible,middle,"Middle native frame/crop is displaced");
      Point root=new Point(53,81);
      Object relative=NotificationMorphFrameAdapter.parentState(state,root);
      apply.invoke(delegate,local,relative,1f,false,null);
      RectF expected=new RectF(destination);expected.offset(-53,-81);
      assertRect(mapped(delegate,1094,1805),expected,"Nonzero transition-root offset counted incorrectly");
      Rect banner=new Rect(50,150,1390,424);
      io.github.s24u.popupscale.gestures.NotificationBannerMotion motion=
          new io.github.s24u.popupscale.gestures.NotificationBannerMotion(banner,destination,root,state);
      // Inspect the actual native crop after its matrix maps it into the parent.
      RectF previous=null;
      float lastStep=Float.POSITIVE_INFINITY;
      for(int i=0;i<=10;i++){
        float fraction=i/10f;
        Object frame=motion.frame(fraction,state);
        apply.invoke(delegate,local,frame,1f,false,null);
        RectF shown=new RectF((Rect)get(delegate,"windowCrop"));
        ((Matrix)get(delegate,"matrix")).mapRect(shown);
        if(i==0){RectF start=new RectF(banner);start.offset(-53,-81);assertRect(shown,start,"First frame missed banner");}
        if(i==5){
          float travel=(shown.height()-banner.height())/(destination.height()-banner.height());
          if(Math.abs(travel-.75f)>.01f)throw new Exception("Unexpected midpoint travel: "+travel);
        }
        if(i==10)assertRect(shown,expected,"Banner motion missed destination");
        if(previous!=null){
          float step=shown.height()-previous.height();
          if(step<=0||step>=lastStep)throw new Exception("Expected successively smaller expansion steps");
          lastStep=step;
        }
        previous=shown;
      }
      result.putString("bannerMotion","PASS eleven native crop samples: decreasing steps, 75% travel at half time, exact start and end with nonzero root offset");
      if(((Integer)get(state,"left"))!=173||((Integer)get(state,"top"))!=657)throw new Exception("Shared source animation state was mutated");
      result.putString("nativeFrameGeometry","PASS firmware applyStateToWindow reproduces original end-frame origin=(0,0); production drawing copy lands at (173,657), complete local crop, middle-frame clip mapping, nonzero root offset and unchanged source/destination. Native transactionApplier null before all invocations: no transaction scheduled/applied; hidden detached fixture surface only; own isolated demo task removed.");
    }finally{if(leash!=null)leash.release();}
  }
  private static Object state(Class<?> type,int left,int top,int right,int bottom)throws Exception{
    Object value=type.getConstructor().newInstance();set(value,"left",left);set(value,"top",top);set(value,"right",right);set(value,"bottom",bottom);
    set(value,"topCornerRadius",32f);set(value,"bottomCornerRadius",32f);return value;
  }
  private static RectF mapped(Object delegate,int width,int height)throws Exception{
    RectF r=new RectF(0,0,width,height);((Matrix)get(delegate,"matrix")).mapRect(r);return r;
  }
  private static void assertRect(RectF a,RectF b,String message)throws Exception{
    if(Math.abs(a.left-b.left)>2||Math.abs(a.top-b.top)>2||Math.abs(a.right-b.right)>2||Math.abs(a.bottom-b.bottom)>2)
      throw new Exception(message+": actual="+a+" expected="+b);
  }
  private static Field field(Object value,String name)throws Exception{
    for(Class<?> c=value.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}
    throw new NoSuchFieldException(name);
  }
  private static Object get(Object value,String name)throws Exception{return field(value,name).get(value);}
  private static void set(Object value,String name,Object data)throws Exception{field(value,name).set(value,data);}
}
