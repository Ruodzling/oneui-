package io.github.s24u.popupscale;
import android.app.*;
import android.content.*;
import android.os.Bundle;
import java.lang.reflect.*;
import java.util.*;

/** Reflects actual APK contracts only; no app launch or external UI interaction. */
final class OhoDispatcherDeviceChecks {
  static void run(Instrumentation r){Bundle out=new Bundle();int code=Activity.RESULT_CANCELED;
    try{
      Context oho=r.getTargetContext().createPackageContext(SideGesturePolicy.PACKAGE_NAME,Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);
      Class<?> view=oho.getClassLoader().loadClass("com.samsung.android.sidegesturepad.fastaction.SGPFastActionView");
      List<Method> result=SideGestureDispatchDiscovery.find(view);
      if(result.size()!=1)throw new AssertionError("Unique callback expected, found "+result.size());
      out.putString("callback",result.get(0).toString());
      long version=oho.getPackageManager().getPackageInfo(SideGesturePolicy.PACKAGE_NAME,0).getLongVersionCode();
      if(version==942300000&&!(result.get(0).getDeclaringClass().getName().equals("a6.f0")&&result.get(0).getParameterTypes()[0].getName().equals("a6.n")))
        throw new AssertionError("Actual9.4.23 click ABI differs from inspected APK");
      if(SideGestureDispatchDiscovery.find(NotFastAction.class).size()!=0)throw new AssertionError("Unrelated callback was accepted");
      out.putString("summary","PASS exact installed OHO view-owned click/menu/item/window contracts; unrelated callback rejected. No package dependency force-stop in this test.");code=Activity.RESULT_OK;
    }catch(Throwable error){out.putString("error",android.util.Log.getStackTraceString(error));}r.finish(code,out);
  }
  static class NotFastAction{Unrelated callback;}
  static class Unrelated{void c(String x){}void d(String x){}}
}
