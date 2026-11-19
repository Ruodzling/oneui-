package io.github.s24u.popupscale;

import java.util.*;

public final class CloneUserChecks {
  private static void check(boolean value){if(!value)throw new AssertionError("clone identity/isolation");}
  public static void main(String[] args){
    check(CloneUserPolicy.supported(0)&&!CloneUserPolicy.supported(95));
    check(CloneUserPolicy.verifiedProfile(95,0,CloneUserPolicy.CLONE_TYPE));
    check(!CloneUserPolicy.verifiedProfile(95,10,CloneUserPolicy.CLONE_TYPE));
    for(String type:new String[]{"android.os.usertype.profile.MANAGED","android.os.usertype.profile.PRIVATE","android.os.usertype.full.SECONDARY",null})check(!CloneUserPolicy.verifiedProfile(95,0,type));
    CloneUserPolicy.replaceVerifiedClones(Collections.singleton(95));
    try{
      check(CloneUserPolicy.visibleToOwner(95,0)&&!CloneUserPolicy.visibleToOwner(95,10)&&!CloneUserPolicy.visibleToOwner(0,95));
      for(String app:new String[]{"com.coolapk.market","com.ss.android.ugc.aweme"}){
        var geometry=ExternalScalePolicy.calculate(app,true,true,false,true,95,0,1,5,825,1361,600,500);
        check(geometry!=null&&geometry.widthDp==500);
        check(ExternalScalePolicy.calculate(app,true,false,false,true,95,0,1,5,825,1361,600,500)==null);
        check(ExternalScalePolicy.calculate(app,true,true,true,true,95,0,1,5,825,1361,600,500)==null);
        check(ExternalScalePolicy.calculate(app,true,true,false,false,95,0,1,5,825,1361,600,500)==null);
        for(int user:new int[]{-1,10,94,96,150})check(ExternalScalePolicy.calculate(app,true,true,false,true,user,0,1,5,825,1361,600,500)==null);
        for(int mode:new int[]{1,2,6})check(ExternalScalePolicy.calculate(app,true,true,false,true,95,0,1,mode,825,1361,600,500)==null);
      }
    }finally{CloneUserPolicy.replaceVerifiedClones(Collections.emptySet());}
    check(!CloneUserPolicy.supported(95));
    System.out.println("PASS: OS-verified owner clone only; real parent/type required; owner/clone geometry and master/diagnostic/exclusion/fullscreen/user isolation.");
  }
}
