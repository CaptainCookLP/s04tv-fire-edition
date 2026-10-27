package de.s04tv.fireedition;

import android.webkit.WebView;

import org.json.JSONObject;

/** Fills only visible login fields on the original S04 account origin; never submits a form. */
final class LoginAutoFill {
    private LoginAutoFill() {}

    static void fill(WebView webView, String email, String password) {
        if (webView == null) return;
        if ((email == null || email.isEmpty()) && (password == null || password.isEmpty())) return;

        // JSONObject.quote prevents quotes/newlines from becoming executable JavaScript.
        String js = "(function(){" +
                "if(location.protocol!=='https:'||location.hostname!=='account.schalke04.de')return;" +
                "var path=location.pathname.toLowerCase();" +
                "if(!(path==='/'||path==='/login'||path==='/login/'||path==='/s/login'||path==='/s/login/'))return;" +
                "if(typeof window.__s04tvStopLoginFill==='function')window.__s04tvStopLoginFill();" +
                "var username=" + JSONObject.quote(email == null ? "" : email) + ";" +
                "var password=" + JSONObject.quote(password == null ? "" : password) + ";" +
                "var finishedUser=!username,finishedPassword=!password,observer=null,timer=null,queued=false;" +
                "var deadline=Date.now()+30000;" +
                "function stop(){" +
                "if(observer)observer.disconnect();if(timer)clearInterval(timer);" +
                "window.__s04tvStopLoginFill=null;username='';password='';}" +
                "window.__s04tvStopLoginFill=stop;" +
                "function inputs(root,out,level){" +
                "if(!root||level>4)return;" +
                "var found=root.querySelectorAll('input');for(var i=0;i<found.length;i++)out.push(found[i]);" +
                "var all=root.querySelectorAll('*');" +
                "for(var j=0;j<all.length;j++){" +
                "var el=all[j];" +
                "if(el.shadowRoot)inputs(el.shadowRoot,out,level+1);" +
                "if(el.tagName==='IFRAME'){try{" +
                "if(el.contentWindow&&el.contentWindow.location.origin===location.origin)" +
                "inputs(el.contentDocument,out,level+1);" +
                "}catch(ignore){}}" +
                "}}" +
                "function visible(el){" +
                "return el&&!el.disabled&&!el.readOnly&&el.type!=='hidden'&&el.getClientRects().length>0;}" +
                "function hint(el){" +
                "var label='';" +
                "if(el.labels)for(var i=0;i<el.labels.length;i++)label+=' '+el.labels[i].textContent;" +
                "return ([el.name,el.id,el.autocomplete,el.placeholder,el.getAttribute('aria-label'),label]" +
                ".join(' ').toLowerCase());}" +
                "function put(el,value){" +
                "if(!visible(el)||el.value||!value)return false;" +
                "var setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;" +
                "setter.call(el,value);" +
                "el.dispatchEvent(new Event('input',{bubbles:true}));" +
                "el.dispatchEvent(new Event('change',{bubbles:true}));" +
                "return true;}" +
                "function tick(){" +
                "queued=false;" +
                "if(location.protocol!=='https:'||location.hostname!=='account.schalke04.de'" +
                "||Date.now()>deadline){stop();return;}" +
                "var path=location.pathname.toLowerCase();" +
                "if(!(path==='/'||path==='/login'||path==='/login/'||path==='/s/login'||path==='/s/login/')){stop();return;}" +
                "var fields=[];inputs(document,fields,0);" +
                "var user=null,pass=null;" +
                "for(var i=0;i<fields.length;i++){" +
                "var field=fields[i];if(!visible(field))continue;" +
                "var type=(field.type||'').toLowerCase(),meta=hint(field);" +
                "if(!pass&&type==='password'&&!/confirm|repeat|new.password|wiederhol|best.tig/i.test(meta))pass=field;" +
                "if(!user&&(type==='email'||((type==='text'||type==='tel')&&" +
                "/e.mail|email|user.?name|benutzername|login|anmelden/.test(meta))))user=field;" +
                "}" +
                "if(user&&username){if(put(user,username)||user.value===username)finishedUser=true;}" +
                "if(pass&&password){if(put(pass,password)||pass.value===password)finishedPassword=true;}" +
                "if(finishedUser&&finishedPassword)stop();" +
                "}" +
                "function schedule(){if(!queued){queued=true;setTimeout(tick,90);}}" +
                "observer=new MutationObserver(schedule);" +
                "observer.observe(document.documentElement||document,{subtree:true,childList:true});" +
                "timer=setInterval(tick,750);" +
                "tick();" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    static void stop(WebView webView) {
        if (webView != null) {
            webView.evaluateJavascript(
                    "(function(){if(typeof window.__s04tvStopLoginFill==='function')" +
                            "window.__s04tvStopLoginFill();})();", null);
        }
    }
}
