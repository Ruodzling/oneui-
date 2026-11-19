package io.github.s24u.popupscale;

import java.lang.reflect.*;
import java.util.*;

/** Discover the callback owned by the named FastAction view, not an obfuscated package. */
public final class SideGestureDispatchDiscovery {
  public static List<Method> find(Class<?> view){
    List<Method> result=new ArrayList<>();
    for(Field field:view.getDeclaredFields())try{
      Class<?> callback=field.getType();
      if(Modifier.isStatic(field.getModifiers())||callback.isPrimitive()||callback.getName().startsWith("android.")||callback.getName().startsWith("java."))continue;
      for(Method click:callback.getDeclaredMethods()){
        if(!click.getName().equals("c")||click.getReturnType()!=void.class||click.getParameterCount()!=1||Modifier.isStatic(click.getModifiers()))continue;
        Class<?> item=click.getParameterTypes()[0];
        Method menu=callback.getDeclaredMethod("d",item);
        if(menu.getReturnType()!=void.class||Modifier.isStatic(menu.getModifiers()))continue;
        boolean windowOwned=false,hasString=false,hasInt=false;
        for(Field owner:callback.getDeclaredFields())if(!Modifier.isStatic(owner.getModifiers()))
          for(Field nested:owner.getType().getDeclaredFields())if(nested.getType()==view&&!Modifier.isStatic(nested.getModifiers()))windowOwned=true;
        for(Field f:item.getDeclaredFields()){hasString|=f.getType()==String.class;hasInt|=f.getType()==int.class;}
        if(windowOwned&&hasString&&hasInt&&!result.contains(click))result.add(click);
      }
    }catch(ReflectiveOperationException|LinkageError ignored){}
    return result;
  }
  private SideGestureDispatchDiscovery(){}
}
