package de.s04tv.fireedition;

import android.webkit.WebView;
import org.json.JSONObject;

/**
 * Only talks to window.player on https://tv.schalke04.de/videos/*.
 * No playout token, URLs, cookies or raw event payloads leave the web page.
 */
final class ThreeQPlayerController {
    private ThreeQPlayerController() {}

    interface Response { void completed(JSONObject result); }

    static void install(WebView webView) {
        if (webView != null) webView.evaluateJavascript(SCRIPT, null);
    }

    static void command(WebView webView, String command, Response callback) {
        if (webView == null) return;
        switch (command) {
            case "toggle": case "back10": case "next10": case "fullscreen":
            case "exitfullscreen": case "pause": break;
            default: return;
        }
        webView.evaluateJavascript(
                "(function(){return JSON.stringify(window.__s04tvThreeQ?window.__s04tvThreeQ.execute('" +
                        command + "'): {ok:false,reason:'controller-not-ready'});})();",
                value -> {
                    if (callback == null) return;
                    try {
                        String json = new org.json.JSONArray("[" + value + "]").getString(0);
                        callback.completed(new JSONObject(json));
                    } catch (Exception e) {
                        JSONObject failure = new JSONObject();
                        try { failure.put("ok", false).put("reason", "invalid-response"); }
                        catch (Exception ignored) {}
                        callback.completed(failure);
                    }
                });
    }

    static void diagnostics(WebView webView, Response callback) {
        if (webView == null) return;
        webView.evaluateJavascript(
                "(function(){return JSON.stringify(window.__s04tvThreeQ?window.__s04tvThreeQ.metrics(): {error:'controller-not-ready'});})();",
                value -> {
                    if (callback == null) return;
                    try {
                        callback.completed(new JSONObject(
                                new org.json.JSONArray("[" + value + "]").getString(0)));
                    } catch (Exception error) {
                        callback.completed(new JSONObject());
                    }
                });
    }

    private static final String SCRIPT = "(function(){\n 'use strict';\n if(location.protocol!=='https:'||location.hostname!=='tv.schalke04.de')return;\n if(window.__s04tvThreeQ){window.__s04tvThreeQ.refresh();return;}\n var tracked=null,route='',ready=false,eventNames=[],error='none';\n var subscribed=null,seenAt=0;\n var autoplay={route:'',element:null,attempts:0,next:0,complete:false,blocked:false};\n function onVideo(){return /^\\/videos\\//.test(location.pathname);}\n function box(){return document.querySelector('.video__player.js3q-wrapper');}\n function media(){var w=box();return w?w.querySelector('video.sdn-source-element'):null;}\n function player(){\n  var p=window.player;\n  return p&&typeof p==='object'&&typeof p.play==='function'&&typeof p.pause==='function'?p:null;\n }\n function events(p){\n  if(subscribed===p||!p||typeof p.on!=='function')return;\n  subscribed=p;\n  ['player.ready','player.fullscreenChange','media.ready','media.playing','media.paused','media.seeked','media.autoplayfailed'].forEach(function(name){\n   try{p.on(name,function(){\n    eventNames.push(name);if(eventNames.length>8)eventNames.shift();\n    if(name==='media.playing')autoplay.complete=true;\n    if(name==='media.autoplayfailed')error='autoplay-rejected';\n   });}catch(e){error='event-registration';}\n  });\n }\n function refresh(){\n  if(!onVideo()){tracked=null;subscribed=null;ready=false;route='';autoplay.route='';return;}\n  var path=location.pathname;\n  if(path!==route){\n    route=path;tracked=null;subscribed=null;ready=false;eventNames=[];error='none';\n    autoplay={route:path,element:null,attempts:0,next:0,complete:false,blocked:false};\n  }\n  tracked=box();\n  var p=player(),v=media();\n  if(p&&tracked){ready=true;events(p);seenAt=Date.now();}\n  else ready=false;\n  if(!ready||!v)return;\n  if(autoplay.element!==v){\n    autoplay.element=v;autoplay.attempts=0;autoplay.next=0;\n    autoplay.complete=!v.paused;autoplay.blocked=false;\n  }\n  if(!v.paused||v.ended){autoplay.complete=true;return;}\n  // Autoplay once per newly created video. Never resume after manual pause.\n  if(autoplay.complete||autoplay.blocked||v.readyState<2)return;\n  if(autoplay.attempts>=3){autoplay.blocked=true;return;}\n  var now=Date.now();if(now<autoplay.next)return;\n  autoplay.attempts++;autoplay.next=now+1500;\n  try{\n    var response=p.play();\n    if(response&&typeof response.catch==='function')\n      response.catch(function(){error='autoplay-blocked';});\n  }catch(e){error='autoplay-error';autoplay.blocked=true;}\n }\n function result(ok,method,reason){return {ok:!!ok,method:method,reason:reason||''};}\n function execute(kind){\n  if(!onVideo())return result(false,'none','not-video');\n  refresh();\n  var p=player(),v=media();\n  if(!p)return result(false,'none','player-not-ready');\n  try{\n   if(kind==='toggle'){\n    autoplay.complete=true;\n    if(v? v.paused : (document.querySelector('.video__player.playing')===null))p.play();\n    else p.pause();\n    return result(true,'3q-api','requested');\n   }\n   if(kind==='back10'||kind==='next10'){\n    if(typeof p.seek!=='function'||typeof p.getCurrentTime!=='function')return result(false,'3q-api','seek-not-available');\n    var now=Number(p.getCurrentTime()),duration=typeof p.getDuration==='function'?Number(p.getDuration()):NaN;\n    if(!Number.isFinite(now))return result(false,'3q-api','time-unavailable');\n    var target=Math.max(0,now+(kind==='back10'?-10:10));\n    if(Number.isFinite(duration)&&duration>0)target=Math.min(target,duration);\n    p.seek(Math.floor(target));\n    return result(true,'3q-api','seek-requested');\n   }\n   if(kind==='fullscreen'){\n    if(document.fullscreenElement||document.webkitFullscreenElement)return result(true,'fullscreen-api','already-fullscreen');\n    if(typeof p.fullscreen!=='function')return result(false,'3q-api','fullscreen-unavailable');\n    p.fullscreen();\n    return result(true,'3q-api','fullscreen-requested-not-confirmed');\n   }\n   if(kind==='exitfullscreen'){\n    if(typeof p.exitfullscreen==='function')p.exitfullscreen();\n    return result(true,'3q-api','exit-requested');\n   }\n   if(kind==='pause'){\n    autoplay.complete=true;\n    p.pause();return result(true,'3q-api','pause-requested');\n   }\n   return result(false,'none','unknown-command');\n  }catch(e){error='3q-command';return result(false,'3q-api','exception');}\n }\n function metrics(){\n  refresh();\n  var w=box(),v=media(),p=player(),r=w?w.getBoundingClientRect():null,vr=v?v.getBoundingClientRect():null;\n  var fs=document.fullscreenElement||document.webkitFullscreenElement||null;\n  function size(r){return r?Math.round(r.width)+'x'+Math.round(r.height):'n/a';}\n  var time=null,duration=null;\n  if(p){try{time=Number(p.getCurrentTime());if(!Number.isFinite(time))time=null;}catch(e){}\n        try{duration=Number(p.getDuration());if(!Number.isFinite(duration))duration=null;}catch(e){}}\n  var vready=v?Number(v.readyState):null;\n  return {route:onVideo()?'video':'catalog',wrapper:!!w,apiReady:!!p,media:!!v,mediaReadyState:vready,\n   screen:size({width:innerWidth,height:innerHeight}),wrapperSize:size(r),videoSize:size(vr),\n   fullscreen:!!fs,fullscreenTarget:fs===w?'player':fs?'other':'none',\n   kiosk:document.documentElement.classList.contains('s04tv-tv-kiosk'),\n   time:time,duration:duration,lastEvents:eventNames.slice(-5),lastError:error,\n   autoplayAttempts:autoplay.attempts,autoplayComplete:autoplay.complete};\n }\n window.__s04tvThreeQ={refresh:refresh,execute:execute,metrics:metrics};\n window.addEventListener('popstate',refresh);\n window.addEventListener('hashchange',refresh);\n setInterval(refresh,600);\n refresh();\n})();";
}
