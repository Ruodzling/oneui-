package io.github.s24u.popupscale;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;

/** Installed manifest/Configuration ABI only; never opens a room or reads a room identifier. */
final class DouyinDensityDeviceChecks {
  static void run(Instrumentation runner){
    Bundle result=new Bundle();int code=Activity.RESULT_OK;
    try{
      PackageInfo pkg=runner.getTargetContext().getPackageManager().getPackageInfo("com.ss.android.ugc.aweme",PackageManager.GET_ACTIVITIES);
      int checked=0;
      for(ActivityInfo nativeInfo:pkg.activities){
        if(!DouyinDensityPolicy.INSTANCE.playerActivity(nativeInfo.packageName,nativeInfo.name))continue;
        int original=nativeInfo.configChanges;
        if((original&0xc80)!=0xc80)throw new Exception("Page lacks native window-size handling: "+nativeInfo.name);
        int adapted=DouyinDensityPolicy.INSTANCE.densityCallbacks(nativeInfo.packageName,nativeInfo.name,true,true,0,0,1,5,original);
        ActivityInfo scoped=new ActivityInfo(nativeInfo);scoped.configChanges=adapted;
        if(nativeInfo.configChanges!=original||adapted!=(original|0x1000))throw new Exception("Scoped ActivityInfo copy mutated native metadata");
        Configuration before=new Configuration();before.densityDpi=600;before.screenWidthDp=384;before.screenHeightDp=800;before.smallestScreenWidthDp=384;before.orientation=1;before.screenLayout=2;
        Configuration after=new Configuration(before);after.densityDpi=350;after.screenWidthDp=500;after.screenHeightDp=825;after.smallestScreenWidthDp=500;after.screenLayout=3;
        int changed=before.diff(after);
        if((changed&0x1000)==0||(changed&scoped.configChanges)!=changed)throw new Exception("Native Configuration diff remains unhandled: "+Integer.toHexString(changed));
        result.putString(nativeInfo.name,"PASS flags="+Integer.toHexString(original)+"->"+Integer.toHexString(adapted)+" nativeDiff="+Integer.toHexString(changed)+" original ActivityInfo unchanged");
        checked++;
      }
      if(checked<2)throw new Exception("Expected installed live and feed Activities, got "+checked);
      result.putString("summary","PASS installed Douyin "+pkg.getLongVersionCode()+" native manifest and configuration diff; no system hook or room-state verification claimed");
    }catch(Throwable error){code=Activity.RESULT_CANCELED;result.putString("error",error.toString());}
    runner.finish(code,result);
  }
}
