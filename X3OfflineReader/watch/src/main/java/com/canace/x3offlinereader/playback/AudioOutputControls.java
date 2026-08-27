package com.canace.x3offlinereader.playback;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaRouter;
import android.os.Build;
import android.widget.Button;
import android.widget.Toast;
import com.canace.x3offlinereader.R;
import com.canace.x3offlinereader.ui.RoundChoiceDialog;
import java.util.ArrayList;
import java.util.List;

/** Shared system-volume and public MediaRouter controls; it never boosts beyond the system limit. */
@SuppressWarnings("deprecation") public final class AudioOutputControls {
    private final Activity activity;private final AudioManager audio;private final MediaRouter router;private final Button volumeButton,outputButton;
    private final MediaRouter.Callback callback=new MediaRouter.SimpleCallback(){@Override public void onRouteSelected(MediaRouter router,int type,MediaRouter.RouteInfo info){refresh();}@Override public void onRouteUnselected(MediaRouter router,int type,MediaRouter.RouteInfo info){refresh();}@Override public void onRouteChanged(MediaRouter router,MediaRouter.RouteInfo info){refresh();}@Override public void onRouteAdded(MediaRouter router,MediaRouter.RouteInfo info){refresh();}@Override public void onRouteRemoved(MediaRouter router,MediaRouter.RouteInfo info){refresh();}@Override public void onRouteVolumeChanged(MediaRouter router,MediaRouter.RouteInfo info){refresh();}};
    public AudioOutputControls(Activity activity,Button volumeButton,Button outputButton){this.activity=activity;this.volumeButton=volumeButton;this.outputButton=outputButton;audio=(AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);router=(MediaRouter)activity.getSystemService(Context.MEDIA_ROUTER_SERVICE);volumeButton.setOnClickListener(v->showVolume());outputButton.setOnClickListener(v->showOutputs());refresh();}
    public void start(){router.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO,callback);refresh();}
    public void stop(){router.removeCallback(callback);}
    public void refresh(){int max=Math.max(1,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));int current=audio.getStreamVolume(AudioManager.STREAM_MUSIC);volumeButton.setText(activity.getString(R.string.volume_value,Math.round(current*100f/max)));MediaRouter.RouteInfo selected=router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO);outputButton.setText(isWatchSpeaker(selected)?R.string.output_speaker:R.string.output_bluetooth);}
    private void showVolume(){int max=Math.max(1,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));int current=audio.getStreamVolume(AudioManager.STREAM_MUSIC);String[] labels=new String[max+1];for(int i=0;i<=max;i++)labels[i]=activity.getString(R.string.volume_percent,Math.round(i*100f/max));RoundChoiceDialog.show(activity,activity.getText(R.string.volume_title),labels,current,which->{audio.setStreamVolume(AudioManager.STREAM_MUSIC,which,0);refresh();});}
    private void showOutputs(){List<MediaRouter.RouteInfo> routes=new ArrayList<>();for(int i=0;i<router.getRouteCount();i++){MediaRouter.RouteInfo route=router.getRouteAt(i);if(route.isEnabled()&&(route.getSupportedTypes()&MediaRouter.ROUTE_TYPE_LIVE_AUDIO)!=0)routes.add(route);}if(routes.isEmpty()){Toast.makeText(activity,R.string.no_audio_routes,Toast.LENGTH_SHORT).show();return;}MediaRouter.RouteInfo selected=router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO);String[] labels=new String[routes.size()];int checked=-1;for(int i=0;i<routes.size();i++){labels[i]=routeName(routes.get(i),true);if(routes.get(i)==selected)checked=i;}RoundChoiceDialog.show(activity,activity.getText(R.string.output_title),labels,checked,which->{router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO,routes.get(which));refresh();});}
    private String routeName(MediaRouter.RouteInfo route,boolean full){if(isWatchSpeaker(route)){String model=Build.MODEL==null||Build.MODEL.trim().isEmpty()?activity.getString(R.string.output_watch):Build.MODEL.trim();return activity.getString(R.string.output_watch_speaker,model);}return route.getName(activity).toString().trim();}
    private boolean isWatchSpeaker(MediaRouter.RouteInfo route){if(route==null||route==router.getDefaultRoute())return true;String name=route.getName(activity).toString().trim();return name.equals("手机")||name.equalsIgnoreCase("phone");}
}
