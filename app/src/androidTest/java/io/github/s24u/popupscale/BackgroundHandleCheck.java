package io.github.s24u.popupscale;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.os.Bundle;
import io.github.s24u.popupscale.gestures.BackgroundTaskOperation;

final class BackgroundHandleCheck {
  static void run(Instrumentation r) {
    Activity page=null;
    Bundle result=new Bundle();
    int code=Activity.RESULT_CANCELED;
    try {
      Context app=r.getTargetContext();
      ActivityOptions opts=ActivityOptions.makeBasic();
      ActivityOptions.class.getMethod("setLaunchWindowingMode",int.class).invoke(opts,5);
      opts.setLaunchBounds(new Rect(180,480,1050,1980));
      page=r.startActivitySync(new Intent(app,DemoActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK),opts.toBundle());
      final int id=page.getTaskId();
      ActivityManager am=app.getSystemService(ActivityManager.class);
      ActivityManager.AppTask task=am.getAppTasks().stream().filter(t->t.getTaskInfo().taskId==id).findFirst().orElseThrow();
      if(!page.isInMultiWindowMode())throw new Exception("Fixture not freeform");
      r.getUiAutomation().adoptShellPermissionIdentity();
      try {
        Object info=task.getTaskInfo();
        Object token=info.getClass().getField("token").get(info);
        Class<?> wc=Class.forName("android.window.WindowContainerTransaction");
        Object wct=wc.getConstructor().newInstance();
        BackgroundTaskOperation.configure(wct,token);
        Class<?> organizer=Class.forName("android.window.WindowOrganizer");
        organizer.getMethod("applyTransaction",wc).invoke(organizer.getConstructor().newInstance(),wct);
      } finally {r.getUiAutomation().dropShellPermissionIdentity();}
      boolean hidden=false;
      for(int i=0;i<30;i++){
        Object info=task.getTaskInfo();
        if(!info.getClass().getField("isVisible").getBoolean(info)){hidden=true;break;}
        Thread.sleep(100);
      }
      if(!hidden)throw new Exception("Task still visible");
      task.moveToFront();
      boolean resumed=false;
      for(int i=0;i<30;i++){
        ActivityManager.RecentTaskInfo info=task.getTaskInfo();
        if(info.taskId==id&&info.getClass().getField("isVisible").getBoolean(info)){resumed=true;break;}
        Thread.sleep(100);
      }
      if(!resumed)throw new Exception("Same task did not resume");
      result.putString("ownTask","PASS freeform hidden, retained in recents, same task visible after moveToFront");code=Activity.RESULT_OK;
    } catch(Throwable e){result.putString("error",e+" cause="+e.getCause());}
    finally {if(page!=null){Activity own=page;r.runOnMainSync(own::finishAndRemoveTask);}}
    r.finish(code,result);
  }
}
