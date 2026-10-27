package de.s04tv.fireedition;

import android.webkit.WebView;

/**
 * Deterministic TV-sized display of the observed, same-origin 3Q wrapper.
 * This is a fallback display mode, never falsely reports HTML Fullscreen success.
 * Actual fullscreen commands are sent using ThreeQPlayerController.
 */
final class VideoTvMode {
    private VideoTvMode() {}

    static void present(WebView webView) {
        if (webView != null) webView.evaluateJavascript(SCRIPT, null);
    }

    static void exit(WebView webView) {
        if (webView != null) webView.evaluateJavascript(
                "if(window.__s04tvVideoTvMode)window.__s04tvVideoTvMode.exit();", null);
    }

    private static final String SCRIPT = "(function(){\n 'use strict';\n if(location.protocol!=='https:'||location.hostname!=='tv.schalke04.de')return;\n if(window.__s04tvVideoTvMode){window.__s04tvVideoTvMode.refresh();return;}\n var route='',wrapper=null,style=document.createElement('style'),attr='data-s04tv-tv-wrapper',parent='data-s04tv-tv-parent';\n style.id='s04tv-fire-tv-style';\n style.textContent=[\n  'html.s04tv-tv-kiosk,html.s04tv-tv-kiosk body{margin:0!important;overflow:hidden!important;height:100%!important;background:#000!important}',\n  '[data-s04tv-tv-parent]{transform:none!important;filter:none!important;contain:none!important;clip-path:none!important;overflow:visible!important}',\n  '[data-s04tv-tv-wrapper]{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;padding:0!important;display:block!important;visibility:visible!important;aspect-ratio:auto!important;z-index:2147483000!important;background:#000!important;overflow:hidden!important}',\n  '[data-s04tv-tv-wrapper] .js3q-player,[data-s04tv-tv-wrapper] .sdn-display{width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;aspect-ratio:auto!important}',\n  '[data-s04tv-tv-wrapper] video.sdn-source-element{width:100%!important;height:100%!important;max-width:100%!important;max-height:100%!important;object-fit:contain!important}',\n  '[data-s04tv-tv-wrapper]:focus{outline:none!important}'\n ].join('');\n (document.head||document.documentElement).appendChild(style);\n function isVideo(){return /^\\/videos\\//.test(location.pathname);}\n function clear(){\n  document.documentElement.classList.remove('s04tv-tv-kiosk');\n  document.querySelectorAll('['+attr+']').forEach(function(el){el.removeAttribute(attr);});\n  document.querySelectorAll('['+parent+']').forEach(function(el){el.removeAttribute(parent);});\n  wrapper=null;\n }\n function refresh(){\n  if(!isVideo()){if(wrapper)clear();route='';return;}\n  if(route!==location.pathname){clear();route=location.pathname;}\n  var el=document.querySelector('.video__player.js3q-wrapper');\n  if(!el||!el.isConnected||!el.querySelector('video'))return;\n  if(wrapper===el)return;\n  clear();\n  wrapper=el;\n  for(var p=el.parentElement;p&&p!==document.documentElement;p=p.parentElement){\n   if(p===document.body)break;\n   p.setAttribute(parent,'1');\n  }\n  el.setAttribute(attr,'1');\n  document.documentElement.classList.add('s04tv-tv-kiosk');\n }\n function exit(){clear();}\n window.__s04tvVideoTvMode={refresh:refresh,exit:exit};\n setInterval(refresh,450);\n refresh();\n})();";
}
