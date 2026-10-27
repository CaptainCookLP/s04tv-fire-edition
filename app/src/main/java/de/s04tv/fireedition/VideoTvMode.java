package de.s04tv.fireedition;

import android.view.KeyEvent;
import android.webkit.WebView;
import org.json.JSONArray;

/** SPA-aware fullscreen presentation and accessible player diagnostics. */
final class VideoTvMode {
    private VideoTvMode() {}

    static void present(WebView view) {
        if (view != null) view.evaluateJavascript(SCRIPT, null);
    }

    static void toggle(WebView view) {
        if (view == null) return;
        view.evaluateJavascript(
                "window.__s04tvVideoTvMode ? window.__s04tvVideoTvMode.command('toggle') : false",
                result -> {
                    if (!"true".equals(result)) sendPlayerShortcut(view, KeyEvent.KEYCODE_K);
                });
    }

    static void navigate(WebView view, String direction) {
        if (view == null || (!"back10".equals(direction) && !"next10".equals(direction))) return;
        view.evaluateJavascript(
                "window.__s04tvVideoTvMode ? window.__s04tvVideoTvMode.command('" + direction + "') : false",
                result -> {
                    if (!"true".equals(result)) sendPlayerShortcut(view,
                            "back10".equals(direction) ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
                });
    }

    static void fullscreen(WebView view) {
        if (view == null) return;
        view.evaluateJavascript(
                "window.__s04tvVideoTvMode ? window.__s04tvVideoTvMode.command('fullscreen') : false",
                result -> {
                    if (!"true".equals(result)) sendPlayerShortcut(view, KeyEvent.KEYCODE_F);
                });
    }

    private static void sendPlayerShortcut(WebView view, int keycode) {
        if (!view.isAttachedToWindow()) return;
        view.requestFocus();
        long now=android.os.SystemClock.uptimeMillis();
        view.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keycode, 0));
        view.dispatchKeyEvent(new KeyEvent(now, now+20, KeyEvent.ACTION_UP, keycode, 0));
    }

    interface DiagnosticCallback { void onResult(String details); }

    static void diagnose(WebView view, DiagnosticCallback callback) {
        if (view == null) return;
        view.evaluateJavascript(
                "window.__s04tvVideoTvMode ? window.__s04tvVideoTvMode.diagnostics() : 'not-initialized'",
                output -> {
                    try { callback.onResult(new JSONArray("[" + output + "]").getString(0)); }
                    catch (Exception e) { callback.onResult("Diagnostics unavailable"); }
                });
    }

    private static final String SCRIPT = "(function(){\n if(location.protocol!=='https:'||location.hostname!=='tv.schalke04.de')return;\n if(window.__s04tvVideoTvMode){window.__s04tvVideoTvMode.refresh();return;}\n var marker='data-s04tv-video-fill',ancestor='data-s04tv-video-parent',active=null,mode='waiting',route='';\n var css=document.createElement('style');\n css.id='s04tv-tv-player-css';\n css.textContent='html.s04tv-video-open,html.s04tv-video-open body{overflow:hidden!important;background:#000!important;margin:0!important;height:100%!important}'\n + '['+ancestor+']{overflow:visible!important;transform:none!important;filter:none!important;contain:none!important;clip-path:none!important}'\n + '['+marker+']{position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;width:100vw!important;height:100vh!important;min-width:100vw!important;min-height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;padding:0!important;transform:none!important;aspect-ratio:auto!important;object-fit:contain!important;display:block!important;visibility:visible!important;background:black!important;z-index:2147483000!important}'\n + '['+marker+'] video,['+marker+'] iframe{width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;object-fit:contain!important}';\n (document.head||document.documentElement).appendChild(css);\n function isVideo(){return /^\\/videos\\//.test(location.pathname);}\n function rect(e){return e.getBoundingClientRect();}\n function visible(e){if(!e||!e.isConnected||e.closest('[hidden],[aria-hidden=\"true\"]'))return false;var r=rect(e),s=getComputedStyle(e);return r.width>180&&r.height>100&&s.display!=='none'&&s.visibility!=='hidden';}\n function getMedia(){return Array.from(document.querySelectorAll('video')).filter(visible)[0]||null;}\n function find(){\n   var v=getMedia();\n   if(v){\n     var candidate=v;\n     for(var p=v.parentElement,n=0;p&&p!==document.body&&n<6;p=p.parentElement,n++){\n       var r=rect(p);\n       if(r.width>=200&&r.height>=110&&r.height<innerHeight*2&&r.width<innerWidth*1.7&&p.querySelectorAll('video').length===1)candidate=p;\n     }\n     return candidate;\n   }\n   var frames=Array.from(document.querySelectorAll('iframe')).filter(visible);\n   var f=frames.find(function(e){return /3qsdn|3q\\.video|3q\\.de|player/i.test((e.getAttribute('src')||'')+' '+(e.getAttribute('title')||'')+' '+(e.getAttribute('allow')||''));});\n   if(f)return f;\n   var placeholders=Array.from(document.querySelectorAll('[id],[class]')).filter(function(e){return /js3q|3q-player|video-player|player-container/i.test((e.id||'')+' '+String(e.className||''))&&visible(e);});\n   placeholders.sort(function(a,b){return rect(b).width*rect(b).height-rect(a).width*rect(a).height;});\n   return placeholders[0]||null;\n }\n function clear(){\n   document.documentElement.classList.remove('s04tv-video-open');\n   if(active)active.removeAttribute(marker);\n   document.querySelectorAll('['+ancestor+']').forEach(function(e){e.removeAttribute(ancestor);});\n   active=null;\n }\n function refresh(){\n   if(!isVideo()){if(active)clear();route=location.pathname;mode='catalog';return;}\n   if(route!==location.pathname){clear();route=location.pathname;mode='waiting';}\n   if(active&&!active.isConnected)clear();\n   if(!active){\n     var el=find();\n     if(!el){mode='not-found';return;}\n     active=el;\n     for(var p=el.parentElement;p&&p!==document.documentElement;p=p.parentElement)p.setAttribute(ancestor,'1');\n     el.setAttribute(marker,'1');\n     document.documentElement.classList.add('s04tv-video-open');\n   }\n   var r=rect(active);\n   mode=r.width>=innerWidth*.83&&r.height>=innerHeight*.79?'viewport-filled':'element-too-small';\n }\n function command(cmd){\n   if(!isVideo())return false;\n   if(cmd==='fullscreen'){refresh();return mode==='viewport-filled';}\n   var v=getMedia();if(!v)return false;\n   if(cmd==='toggle'){if(v.paused)v.play().catch(function(){});else v.pause();return true;}\n   if(cmd==='back10'){if(isFinite(v.duration))v.currentTime=Math.max(0,v.currentTime-10);return true;}\n   if(cmd==='next10'){if(isFinite(v.duration))v.currentTime=Math.min(v.duration,v.currentTime+10);return true;}\n   return false;\n }\n function diagnostics(){\n   var r=active&&active.isConnected?rect(active):null;\n   return JSON.stringify({mode:mode,route:isVideo()?'video':'catalog',videos:document.querySelectorAll('video').length,iframes:document.querySelectorAll('iframe').length,screen:Math.round(innerWidth)+'x'+Math.round(innerHeight),player:r?Math.round(r.width)+'x'+Math.round(r.height):'not-found',directMedia:!!getMedia()});\n }\n window.__s04tvVideoTvMode={refresh:refresh,command:command,diagnostics:diagnostics};\n setInterval(refresh,400);refresh();\n})();";
}
