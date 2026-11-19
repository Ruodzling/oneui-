package io.github.s24u.popupscale;

import android.content.Context;
import android.os.*;
import java.util.*;
import de.robv.android.xposed.XposedHelpers;

/** Discovers actual profile types/parents through UserManager. No profile names or app data are read. */
public final class CloneProfileRegistry {
  private static long last=-10000;
  private static String signature="";
  private CloneProfileRegistry(){}
  public static synchronized void refresh(Context context){
    if(context==null)return;long now=SystemClock.elapsedRealtime();if(now-last<5000)return;last=now;
    try{
      Object manager=context.getSystemService(Context.USER_SERVICE);if(manager==null)return;
      List<?> profiles=(List<?>)XposedHelpers.callMethod(manager,"getProfiles",0);
      Set<Integer> ids=new HashSet<>();
      for(Object info:profiles){
        int id=XposedHelpers.getIntField(info,"id");
        String type=(String)XposedHelpers.getObjectField(info,"userType");
        int parent=-1;
        try{parent=XposedHelpers.getIntField(info,"profileGroupId");}catch(Throwable ignored){}
        try{Object p=XposedHelpers.callMethod(manager,"getProfileParent",id);if(p!=null)parent=XposedHelpers.getIntField(p,"id");}catch(Throwable ignored){}
        if(CloneUserPolicy.verifiedProfile(id,parent,type))ids.add(id);
      }
      CloneUserPolicy.replaceVerifiedClones(ids);
      String value=new TreeSet<>(ids).toString();if(!value.equals(signature)){signature=value;io.github.s24u.popupscale.gestures.Guard.INSTANCE.info("verified owner clone users="+value);}
    }catch(Throwable unavailable){/* Missing/denied UserManager metadata retains the last verified set; no ID guessing. */}
  }
}
