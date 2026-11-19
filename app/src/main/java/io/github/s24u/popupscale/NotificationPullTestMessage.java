package io.github.s24u.popupscale;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** A local-only synthetic notification. Never sends a chat or opens a real notification. */
public final class NotificationPullTestMessage {
  public static final int PERMISSION_REQUEST=743;
  private NotificationPullTestMessage(){}
  public static void begin(Activity activity){
    if(Build.VERSION.SDK_INT>=33&&activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
      activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},PERMISSION_REQUEST);return;
    }
    NotificationManager manager=activity.getSystemService(NotificationManager.class);
    if(!manager.areNotificationsEnabled()){
      Toast.makeText(activity,"请先在应用信息中允许本模块发送通知",Toast.LENGTH_LONG).show();return;
    }
    Context app=activity.getApplicationContext();
    activity.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    new Handler(Looper.getMainLooper()).postDelayed(()->post(app),900);
  }
  public static void post(Context context){
    NotificationManager manager=context.getSystemService(NotificationManager.class);
    NotificationChannel channel=new NotificationChannel("popup_pull_test","小窗操作测试",NotificationManager.IMPORTANCE_HIGH);
    channel.setSound(null,null);channel.enableVibration(false);manager.createNotificationChannel(channel);
    Intent target=new Intent(context,DemoActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra("notification_pull_demo",true);
    PendingIntent pending=PendingIntent.getActivity(context,743,target,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Notification notification=new Notification.Builder(context,channel.getId()).setSmallIcon(R.drawable.ic_stat_notification)
        .setContentTitle("小窗打开测试").setContentText("从这张通知上向下滑动，打开测试小窗")
        .setContentIntent(pending).setCategory(Notification.CATEGORY_MESSAGE).setLocalOnly(true)
        .setAutoCancel(true).setTimeoutAfter(15000).build();
    try{manager.notify(743,notification);}
    catch(SecurityException denied){Toast.makeText(context,"通知权限未开启，暂时无法显示测试消息",Toast.LENGTH_LONG).show();}
  }
}
