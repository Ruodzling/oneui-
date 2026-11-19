package io.github.s24u.popupscale;

import java.util.*;

/** Only OS-verified owner clone profiles; never treats arbitrary secondary users as clones. */
public final class CloneUserPolicy {
  public static final String CLONE_TYPE="android.os.usertype.profile.CLONE";
  private static volatile Set<Integer> clones=Collections.emptySet();
  private CloneUserPolicy(){}
  public static boolean verifiedProfile(int user,int parent,String type){return user>0&&parent==0&&CLONE_TYPE.equals(type);}
  public static void replaceVerifiedClones(Collection<Integer> users){
    Set<Integer> next=new HashSet<>();for(Integer id:users)if(id!=null&&id>0)next.add(id);clones=Collections.unmodifiableSet(next);
  }
  public static boolean supported(int user){return user==0||clones.contains(user);}
  public static boolean visibleToOwner(int taskUser,int owner){return owner==0&&supported(taskUser);}
  public static Set<Integer> cloneIds(){return clones;}
}
