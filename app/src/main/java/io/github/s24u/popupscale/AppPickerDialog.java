package io.github.s24u.popupscale;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.graphics.drawable.Drawable;
import android.text.*;
import android.view.*;
import android.widget.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;

/** Friendly app names/icons loaded locally from PackageManager. */
final class AppPickerDialog {
  interface Selected { void apply(Set<String> packages); }
  private static final class App {String name,label;App(String n,String l){name=n;label=l;}}
  static androidx.appcompat.app.AlertDialog show(Activity activity,Set<String> initial,Selected result){
    return show(activity,initial,"选择应用","不选应用",8,true,result);
  }
  static androidx.appcompat.app.AlertDialog show(Activity activity,Set<String> initial,String title,
      String neutralLabel,int maxCount,boolean includeSystemRoles,Selected result){
    LinkedHashSet<String> checked=new LinkedHashSet<>(initial);
    LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
    int pad=dp(activity,18);body.setPadding(pad,0,pad,0);
    EditText search=new EditText(activity);search.setSingleLine();search.setHint("搜索应用名称");body.addView(search,new LinearLayout.LayoutParams(-1,-2));
    ListView list=new ListView(activity);list.setDivider(null);body.addView(list,new LinearLayout.LayoutParams(-1,dp(activity,360)));
    TextView loading=new TextView(activity);loading.setText("正在载入应用…");body.addView(loading);
    ArrayList<App> all=new ArrayList<>(),visible=new ArrayList<>();
    Map<String,Drawable> icons=new HashMap<>();PackageManager pm=activity.getPackageManager();
    BaseAdapter adapter=new BaseAdapter(){
      public int getCount(){return visible.size();}public Object getItem(int i){return visible.get(i);}public long getItemId(int i){return i;}
      public View getView(int i,View old,ViewGroup parent){
        App app=visible.get(i);LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(activity,10),0,dp(activity,10));
        ImageView icon=new ImageView(activity);Drawable image=icons.get(app.name);
        if(image==null)try{image=pm.getApplicationIcon(app.name);if(icons.size()<50)icons.put(app.name,image);}catch(Exception ignored){}
        icon.setImageDrawable(image);row.addView(icon,new LinearLayout.LayoutParams(dp(activity,36),dp(activity,36)));
        TextView name=new TextView(activity);name.setText(app.label);name.setTextSize(16);name.setPadding(dp(activity,14),0,dp(activity,8),0);
        row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        CheckBox box=new CheckBox(activity);box.setChecked(checked.contains(app.name));box.setClickable(false);box.setFocusable(false);row.addView(box);return row;
      }
    };
    list.setAdapter(adapter);
    list.setOnItemClickListener((p,v,index,id)->{
      String name=visible.get(index).name;
      if(checked.contains(name))checked.remove(name);
      else if(maxCount<=0||checked.size()<maxCount)checked.add(name);
      else Toast.makeText(activity,"最多选择"+maxCount+"个应用",Toast.LENGTH_SHORT).show();
      adapter.notifyDataSetChanged();
    });
    Runnable filter=()->{String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);visible.clear();for(App a:all)if(a.label.toLowerCase(Locale.ROOT).contains(query))visible.add(a);adapter.notifyDataSetChanged();};
    search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){filter.run();}public void afterTextChanged(Editable e){}});
    androidx.appcompat.app.AlertDialog dialog=new MaterialAlertDialogBuilder(activity).setTitle(title).setView(body)
        .setNegativeButton("取消",null).setNeutralButton(neutralLabel,(d,w)->result.apply(new LinkedHashSet<>()))
        .setPositiveButton("完成",(d,w)->result.apply(checked)).show();
    new Thread(()->{
      LinkedHashMap<String,App> apps=new LinkedHashMap<>();
      try {
        for(ResolveInfo info:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0))
          if(info.activityInfo!=null&&(includeSystemRoles||AppScopePolicy.isOrdinaryPackage(info.activityInfo.packageName)))try{apps.put(info.activityInfo.packageName,new App(info.activityInfo.packageName,info.loadLabel(pm).toString()));}catch(RuntimeException ignored){}
      }catch(RuntimeException ignored){}
      if(includeSystemRoles)for(String name:new String[]{activity.getPackageName(),"com.android.systemui","com.sec.android.app.launcher"})
        try{apps.put(name,new App(name,pm.getApplicationLabel(pm.getApplicationInfo(name,0)).toString()));}catch(Exception ignored){}
      ArrayList<App> sorted=new ArrayList<>(apps.values());sorted.sort((a,b)->java.text.Collator.getInstance().compare(a.label,b.label));
      activity.runOnUiThread(()->{if(activity.isDestroyed()||activity.isFinishing())return;all.addAll(sorted);loading.setText("没有可选应用，可不选继续");loading.setVisibility(sorted.isEmpty()?View.VISIBLE:View.GONE);filter.run();});
    },"app-picker").start();
    return dialog;
  }
  static String installedLabels(Context c,Collection<String> packages,String emptyText){
    ArrayList<String> names=new ArrayList<>();for(String name:packages)try{names.add(c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(name,0)).toString());}catch(Exception ignored){}
    return names.isEmpty()?emptyText:android.text.TextUtils.join("、",names);
  }
  static String labels(Context c,Collection<String> packages){
    ArrayList<String> names=new ArrayList<>();for(String name:packages)try{names.add(c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(name,0)).toString());}catch(Exception ignored){names.add("已移除的应用");}
    return names.isEmpty()?"可跳过，不影响记录":android.text.TextUtils.join("、",names);
  }
  private static int dp(Context c,int value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
}
