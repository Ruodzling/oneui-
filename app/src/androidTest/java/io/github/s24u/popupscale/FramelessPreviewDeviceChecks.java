package io.github.s24u.popupscale;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicReference;
import io.github.s24u.popupscale.gestures.LiveProbeActivity;

/** Visual prototype of caption-only clipping, on the module's own synthetic page only.
 * Does not pretend to exercise system_server hooks before the user's reboot.
 */
@android.annotation.TargetApi(30)
final class FramelessPreviewDeviceChecks {
  static void abi(Instrumentation r){
    Bundle b=new Bundle();
    try{
      Context app=r.getTargetContext(),system=app.createPackageContext("com.android.systemui",Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);
      StringBuilder s=new StringBuilder();
      for(String name:new String[]{"com.android.wm.shell.windowdecor.FreeformOutline","com.android.wm.shell.windowdecor.widget.OutlineView","android.view.InsetsState",
          "com.android.wm.shell.windowdecor.DragResizeInputListener$TaskResizeInputEventReceiver",
          "com.android.wm.shell.windowdecor.DragResizeWindowGeometry"}){
        Class<?> c=system.getClassLoader().loadClass(name);s.append(name).append(" loader=").append(c.getClassLoader()).append('\n');
        for(java.lang.reflect.Method m:c.getDeclaredMethods())if(name.contains("DragResize")||m.getName().equals("updateOutlineView")||m.getName().equals("draw")||m.getName().equals("calculateInsets"))s.append(m.toString()).append('\n');
        for(java.lang.reflect.Field f:c.getDeclaredFields())if(f.getName().contains("Paint"))s.append(f.toString()).append('\n');
      }
      b.putString("abi",s.toString());r.finish(Activity.RESULT_OK,b);
    }catch(Throwable e){b.putString("error",e.toString());r.finish(Activity.RESULT_CANCELED,b);}
  }
  interface MainWork { void run() throws Exception; }
  static void main(Instrumentation r,MainWork work) throws Exception {
    AtomicReference<Throwable> error=new AtomicReference<>();
    r.runOnMainSync(()->{try{work.run();}catch(Throwable t){error.set(t);}});
    if(error.get()!=null)throw new Exception("preview UI failed",error.get());
  }
  static void run(Instrumentation r){
    Bundle result=new Bundle();int code=Activity.RESULT_OK;Activity page=null;
    View[] root={null};ViewOutlineProvider[] oldOutline={null};Rect[] oldClip={null};boolean[] oldClipOutline={false};
    Object[] surface={null};
    try{
      Context app=r.getTargetContext();
      main(r,()->verifyNativeOutline(app,result));
      verifyNativeInsets(result);
      verifyNativeResize(app,result);
      main(r,()->verifyNativeGap(app,result));
      if(app.getSystemService(KeyguardManager.class).isKeyguardLocked())throw new Exception("Unlock phone first");
      ActivityOptions options=ActivityOptions.makeBasic();
      java.lang.reflect.Method launch=ActivityOptions.class.getDeclaredMethod("setLaunchWindowingMode",int.class);
      launch.setAccessible(true);launch.invoke(options,5);
      page=r.startActivitySync(new Intent(app,LiveProbeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),options.toBundle());
      Activity own=page;Thread.sleep(900);
      int[] caption={0};Rect[] rect={null};float[] radius={0};
      main(r,()->{
        if(!own.isInMultiWindowMode())throw new Exception("Own probe not freeform");
        View view=own.getWindow().getDecorView();root[0]=view;
        WindowInsets insets=view.getRootWindowInsets();
        if(insets==null)throw new Exception("Missing native Insets");
        caption[0]=insets.getInsetsIgnoringVisibility(WindowInsets.Type.captionBar()).top;
        if(caption[0]<=0||caption[0]*3>=view.getHeight())throw new Exception("Invalid native caption "+caption[0]);
        int[] location=new int[2];view.getLocationOnScreen(location);
        rect[0]=new Rect(location[0],location[1],location[0]+view.getWidth(),location[1]+Math.min(view.getHeight(),caption[0]+400));
        oldOutline[0]=view.getOutlineProvider();oldClip[0]=view.getClipBounds();oldClipOutline[0]=view.getClipToOutline();
        radius[0]=Math.min(caption[0],view.getWidth()/8f);
      });
      File dir=new File(app.getExternalFilesDir(null),"frameless-preview");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("preview directory");
      capture(r,rect[0],new File(dir,"before.png"));
      main(r,()->{
        View view=root[0];Object viewRoot=View.class.getMethod("getViewRootImpl").invoke(view);
        surface[0]=viewRoot.getClass().getMethod("getSurfaceControl").invoke(viewRoot);
        apply(surface[0],new Rect(0,caption[0],view.getWidth(),view.getHeight()),radius[0]);
      });
      Thread.sleep(700);capture(r,rect[0],new File(dir,"after.png"));
      result.putString("summary","Surface prototype applied to own app only; inspect before/after pixels. Native server hook still requires reboot.");
      result.putInt("captionPixels",caption[0]);result.putString("images",dir.getAbsolutePath());
    }catch(Throwable e){code=Activity.RESULT_CANCELED;result.putString("error",e.toString()+" cause="+(e.getCause()==null?"":e.getCause().toString()));}
    finally{
      Activity finish=page;
      try{main(r,()->{if(surface[0]!=null)apply(surface[0],null,0f);if(root[0]!=null){root[0].setClipBounds(oldClip[0]);root[0].setOutlineProvider(oldOutline[0]);root[0].setClipToOutline(oldClipOutline[0]);}if(finish!=null)finish.finish();});}
      catch(Exception e){code=Activity.RESULT_CANCELED;result.putString("cleanupError",e.toString());}
      r.finish(code,result);
    }
  }
  static void apply(Object surface,Rect clip,float radius) throws Exception {
    Class<?> sc=Class.forName("android.view.SurfaceControl"),type=Class.forName("android.view.SurfaceControl$Transaction");
    Object tx=type.getConstructor().newInstance();
    try{
      type.getMethod("setCrop",sc,Rect.class).invoke(tx,surface,clip);type.getMethod("setCornerRadius",sc,float.class).invoke(tx,surface,radius);
      if(clip!=null)io.github.s24u.popupscale.gestures.FramelessSurfaceStyle.apply(tx,surface);
      type.getMethod("apply").invoke(tx);
    }
    finally{type.getMethod("close").invoke(tx);}
  }
  static void verifyNativeOutline(Context app,Bundle result) throws Exception {
    Context system=app.createPackageContext("com.android.systemui",Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);
    Class<?> type=system.getClassLoader().loadClass("com.android.wm.shell.windowdecor.widget.OutlineView");
    View view=(View)type.getConstructor(Context.class,android.util.AttributeSet.class).newInstance(system,null);
    android.widget.FrameLayout root=new android.widget.FrameLayout(system);
    root.addView(view,new android.widget.FrameLayout.LayoutParams(300,240));
    root.measure(View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY));root.layout(0,0,300,240);
    type.getMethod("updateOutlineViewData",int.class,int.class,int.class,boolean.class,boolean.class).invoke(view,30,3,60,false,false);
    Bitmap before=Bitmap.createBitmap(300,240,Bitmap.Config.ARGB_8888),after=Bitmap.createBitmap(300,240,Bitmap.Config.ARGB_8888);
    try{
      root.draw(new Canvas(before));
      io.github.s24u.popupscale.gestures.FramelessOutlineVisibility visibility=new io.github.s24u.popupscale.gestures.FramelessOutlineVisibility();
      visibility.apply(view,true);
      // Native drag-end styling resets Paints and sometimes visibility. Reapply the actual
      // production node-state operation, not a test-only transparent Paint substitute.
      for(int i=0;i<4;i++){
        type.getMethod("updateOutlineViewData",int.class,int.class,int.class,boolean.class,boolean.class).invoke(view,30,3,60,i%2==0,false);
        view.setVisibility(View.VISIBLE);view.setAlpha(1f);visibility.apply(view,true);
        root.draw(new Canvas(after));
        if(Color.alpha(after.getPixel(150,20))!=0)throw new Exception("Native outline reappeared after style reset");
      }
      int oldAlpha=Color.alpha(before.getPixel(150,20)),newAlpha=Color.alpha(after.getPixel(150,20));
      if(oldAlpha<200||newAlpha!=0)throw new Exception("Native outline pixel mismatch "+oldAlpha+" -> "+newAlpha);
      visibility.apply(view,false);
      if(view.getVisibility()!=View.VISIBLE||view.getAlpha()!=1f)throw new Exception("Native outline state not restored");
      result.putString("nativeOutlinePixels","PASS actual Samsung OutlineView alpha "+oldAlpha+" -> "+newAlpha+" across 4 style/visibility resets; original state restored");
    }finally{before.recycle();after.recycle();}
  }
  static Object field(Object owner,String name) throws Exception {
    java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
  }
  static void resize(Instrumentation runner){
    Bundle result=new Bundle();int code=Activity.RESULT_OK;
    try{verifyNativeResize(runner.getTargetContext(),result);}
    catch(Throwable error){code=Activity.RESULT_CANCELED;result.putString("error",error.toString()+" cause="+error.getCause());}
    runner.finish(code,result);
  }
  static void verifyNativeResize(Context app,Bundle result) throws Exception {
    Context system=app.createPackageContext("com.android.systemui",Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);
    ClassLoader loader=system.getClassLoader();
    Class<?> type=loader.loadClass("com.android.wm.shell.windowdecor.DragResizeWindowGeometry"),disabled=loader.loadClass("com.android.wm.shell.windowdecor.DragResizeWindowGeometry$DisabledEdge");
    Object none=null;for(Object value:disabled.getEnumConstants())if(value.toString().equals("NONE"))none=value;
    if(none==null)throw new Exception("Native disabled-edge NONE unavailable");
    Object geometry=type.getConstructor(int.class,android.util.Size.class,int.class,int.class,int.class,int.class,disabled)
        .newInstance(30,new android.util.Size(800,1600),12,8,24,60,none);
    java.lang.reflect.Method hit=type.getMethod("calculateEdgeResizeCtrlType",float.class,float.class);
    Rect oldBottom=new Rect((Rect)field(field(geometry,"mLargeTaskCorners"),"mRightBottomCornerBounds"));
    Rect[] oldTop=new Rect[2];int index=0;
    for(String name:new String[]{"mFineTaskCorners","mLargeTaskCorners"})oldTop[index++]=new Rect((Rect)field(field(geometry,name),"mLeftTopCornerBounds"));
    if(((Integer)hit.invoke(geometry,400f,-6f))!=4)throw new Exception("Native top edge baseline unexpected");
    if(!io.github.s24u.popupscale.gestures.FramelessResizeGeometry.apply(geometry,60,3f))throw new Exception("Resize geometry did not change");
    for(boolean pointer:new boolean[]{false,true}){
      java.lang.reflect.Field mode=type.getDeclaredField("mIsPointerInput");mode.setAccessible(true);mode.setBoolean(geometry,pointer);
      if(((Integer)hit.invoke(geometry,400f,54f))!=0||((Integer)hit.invoke(geometry,400f,-6f))!=0)throw new Exception("Middle top edge still resizes; pointer="+pointer);
      if(((Integer)hit.invoke(geometry,-6f,800f))!=1||((Integer)hit.invoke(geometry,806f,800f))!=2)throw new Exception("Side edges changed; pointer="+pointer);
    }
    index=0;
    for(String name:new String[]{"mFineTaskCorners","mLargeTaskCorners"}){
      Object corners=field(geometry,name);Rect top=(Rect)field(corners,"mLeftTopCornerBounds"),right=(Rect)field(corners,"mRightTopCornerBounds");
      if(top.centerY()!=60)throw new Exception("Upper corner did not move: "+name+" "+top);
      if(top.width()<=oldTop[index].width()||top.height()<=oldTop[index].height())throw new Exception("Upper corner did not expand: "+name);
      float x=(oldTop[index].right+top.right)/2f;
      java.lang.reflect.Method cornerHit=corners.getClass().getMethod("calculateCornersCtrlType",float.class,float.class);
      if(((Integer)cornerHit.invoke(corners,x,60f))!=5||((Integer)cornerHit.invoke(corners,800f-x,60f))!=6)throw new Exception("New upper corner area does not hit diagonal resize: "+name);
      if(top.contains(400,60)||right.contains(400,60)||((Integer)cornerHit.invoke(corners,400f,60f))!=0)throw new Exception("Corners steal central caption lane");
      index++;
    }
    if(!oldBottom.equals(field(field(geometry,"mLargeTaskCorners"),"mRightBottomCornerBounds")))throw new Exception("Bottom corner moved");
    if(io.github.s24u.popupscale.gestures.FramelessResizeGeometry.apply(geometry,60,3f))throw new Exception("Repeated geometry offset accumulated");
    io.github.s24u.popupscale.gestures.FramelessResizeGeometry.apply(geometry,90,3f);
    if(((Rect)field(field(geometry,"mLargeTaskCorners"),"mLeftTopCornerBounds")).centerY()!=90)throw new Exception("Caption update accumulated");
    io.github.s24u.popupscale.gestures.FramelessResizeGeometry.apply(geometry,0);
    if(((Integer)hit.invoke(geometry,400f,-6f))!=4||((Integer)hit.invoke(geometry,400f,54f))!=0)throw new Exception("Native resize geometry restore failed");
    index=0;
    for(String name:new String[]{"mFineTaskCorners","mLargeTaskCorners"})if(!oldTop[index++].equals(field(field(geometry,name),"mLeftTopCornerBounds")))throw new Exception("Expanded corner not restored");
    Object narrow=type.getConstructor(int.class,android.util.Size.class,int.class,int.class,int.class,int.class,disabled)
        .newInstance(30,new android.util.Size(180,600),12,8,24,60,none);
    ((Rect)field(field(narrow,"mTaskEdges"),"mHandleRect")).set(45,0,135,90);
    io.github.s24u.popupscale.gestures.FramelessResizeGeometry.apply(narrow,60,3.75f);
    for(String name:new String[]{"mFineTaskCorners","mLargeTaskCorners"}){
      Object corners=field(narrow,name);Rect left=(Rect)field(corners,"mLeftTopCornerBounds"),right=(Rect)field(corners,"mRightTopCornerBounds");
      if(left.right>45||right.left<135||left.contains(90,60)||right.contains(90,60))throw new Exception("Narrow window corners overlap native handle");
    }
    result.putString("nativeResizeGeometry","PASS actual Samsung geometry: central top edge disabled for touch/pointer, enlarged upper corners hit5/6, narrow native-handle lane protected, sides/bottom retained, idempotent/update/disable restore");
  }
  static void verifyNativeInsets(Bundle result) throws Exception {
    Class<?> stateClass=Class.forName("android.view.InsetsState"),sourceClass=Class.forName("android.view.InsetsSource");
    Object state=stateClass.getConstructor().newInstance();int type=WindowInsets.Type.captionBar();
    Object source=sourceClass.getConstructor(int.class,int.class).newInstance(987,type);
    Object statusSource=sourceClass.getConstructor(int.class,int.class).newInstance(988,WindowInsets.Type.statusBars());
    Rect frame=new Rect(100,200,1100,2000),bounds=new Rect(frame);
    sourceClass.getMethod("setFrame",Rect.class).invoke(source,new Rect(100,200,1100,260));
    sourceClass.getMethod("setFrame",Rect.class).invoke(statusSource,new Rect(100,200,1100,250));
    stateClass.getMethod("addSource",sourceClass).invoke(state,source);
    stateClass.getMethod("addSource",sourceClass).invoke(state,statusSource);
    io.github.s24u.popupscale.gestures.CaptionInsetsReader reader=io.github.s24u.popupscale.gestures.CaptionInsetsReader.create(stateClass);
    if(reader==null||reader.calculate(state,frame,bounds).top!=60)throw new Exception("Absolute caption Insets adapter failed");
    if(reader.getArity()==4){
      sourceClass.getMethod("setAttachedInsets",android.graphics.Insets.class).invoke(source,android.graphics.Insets.of(0,60,0,0));
      if(reader.calculate(state,frame,bounds).top!=60)throw new Exception("Attached caption Insets need task bounds");
    }
    if(!reader.getCanStripCaption())throw new Exception("Caption strip adapter unavailable on real InsetsState");
    Object stripped=reader.stripCaptionBar(state);
    if(stripped==state||reader.calculate(stripped,frame,bounds).top!=0)throw new Exception("Stripped InsetsState still retains captionBar");
    if(reader.calculate(state,frame,bounds).top!=60)throw new Exception("Original InsetsState mutated by stripCaptionBar");
    if(((Integer)stateClass.getMethod("sourceSize").invoke(stripped))!=1)throw new Exception("Non-caption InsetsSource lost during stripCaptionBar");
    result.putString("nativeInsets","PASS production adapter arity="+reader.getArity()+" absolute/attached caption top=60 and stripCaptionBar=0");
  }
  static void verifyNativeGap(Context app,Bundle result) throws Exception {
    Context system=app.createPackageContext("com.android.systemui",Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);
    int layout=system.getResources().getIdentifier("mw_handle_view_layout","layout","com.android.systemui");
    if(layout==0)throw new Exception("Native handle layout unavailable");
    ViewGroup root=(ViewGroup)LayoutInflater.from(system).inflate(layout,null);
    android.widget.ImageView bar=(android.widget.ImageView)root.getChildAt(0);
    bar.setImageTintList(android.content.res.ColorStateList.valueOf(Color.BLUE));
    // This captured firmware's setupHandleVerticalPadding(freeform) uses these two
    // native dimensions; inflating XML alone leaves it in its unbound/default state.
    bar.setPadding(bar.getPaddingLeft(),system.getResources().getDimensionPixelSize(0x7e070d37),
        bar.getPaddingRight(),system.getResources().getDimensionPixelSize(0x7e070d36));
    int width=Math.max(1,bar.getLayoutParams().width),height=120,caption=60;
    root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);
    Float visual=io.github.s24u.popupscale.gestures.CaptionAnchorGeometry.visualBottom(bar,root,0f);
    if(visual==null)throw new Exception("Native handle drawable not laid out");
    float density=android.content.res.Resources.getSystem().getDisplayMetrics().density;
    float shift=io.github.s24u.popupscale.gestures.CaptionAnchorPolicy.shift(caption,visual,density);
    Bitmap before=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888),after=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
    try{
      root.draw(new Canvas(before));bar.setTranslationY(shift);root.draw(new Canvas(after));
      int oldBottom=blueBottom(before),newBottom=blueBottom(after);float gap=caption-newBottom-1;
      if(oldBottom<0||newBottom<0||Math.abs(gap-2*density)>2f)throw new Exception("Native pill gap "+oldBottom+" -> "+newBottom+" gap="+gap+" expected="+(2*density)+" shift="+shift+" drawableBottom="+visual);
      result.putString("nativeHandleGap","PASS native layout pixels gap="+gap+" target="+(2*density)+" shift="+shift+"; same button moves");
    }finally{bar.setTranslationY(0);before.recycle();after.recycle();}
  }
  static int blueBottom(Bitmap image){
    for(int y=image.getHeight()-1;y>=0;y--)for(int x=0;x<image.getWidth();x++){
      int c=image.getPixel(x,y);if(Color.alpha(c)>128&&Color.blue(c)>150&&Color.red(c)<80&&Color.green(c)<100)return y;
    }
    return -1;
  }
  static void capture(Instrumentation r,Rect area,File file) throws Exception {
    Bitmap whole=r.getUiAutomation().takeScreenshot();if(whole==null)throw new IOException("screenshot unavailable");
    Bitmap part=null;
    try{
      if(!area.intersect(0,0,whole.getWidth(),whole.getHeight()))throw new IOException("window off display");
      part=Bitmap.createBitmap(whole,area.left,area.top,area.width(),area.height());
      try(OutputStream stream=new FileOutputStream(file)){if(!part.compress(Bitmap.CompressFormat.PNG,100,stream))throw new IOException("PNG encode failed");}
    }finally{if(part!=null&&part!=whole)part.recycle();whole.recycle();}
  }
}
