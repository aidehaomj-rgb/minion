package com.minion.core.agent;

import com.google.gson.JsonObject;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Identifies tool calls that visibly launch the same URL outside Minion. */
final class ExternalOpenPolicy {
    private static final Pattern URL=Pattern.compile("(?i)https?://[^\\s\"'<>]+");
    private static final Pattern LAUNCHER=Pattern.compile(
            "(?i)(?:cmd(?:\\.exe)?[^\\r\\n]{0,60}\\bstart\\b|start-process\\b|"
                    + "chrome(?:\\.exe)?\\b|msedge(?:\\.exe)?\\b|firefox(?:\\.exe)?\\b|explorer(?:\\.exe)?\\b)");

    private ExternalOpenPolicy(){}

    static String keyFor(String tool,JsonObject args){
        if(args==null)return null;
        if("OpenUrl".equals(tool))return key(text(args,"url"));
        if("Browser".equals(tool)&&"open".equalsIgnoreCase(text(args,"action")))return key(text(args,"url"));
        if(!"Bash".equals(tool))return null;
        String command=text(args,"command");
        if(command.isEmpty()||!LAUNCHER.matcher(command).find())return null;
        Matcher match=URL.matcher(command);
        return match.find()?key(trimUrlPunctuation(match.group())):null;
    }

    private static String trimUrlPunctuation(String value){
        int end=value.length();
        while(end>0&&")]}.,;；，。".indexOf(value.charAt(end-1))>=0)end--;
        return value.substring(0,end);
    }

    private static String key(String raw){
        if(raw==null||raw.trim().isEmpty())return null;
        try {
            URI uri=new URI(raw.trim()).normalize();
            String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT);
            String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);
            if(!("http".equals(scheme)||"https".equals(scheme))||host.isEmpty())return null;
            String path=uri.getRawPath()==null||uri.getRawPath().isEmpty()?"/":uri.getRawPath();
            String query=uri.getRawQuery()==null?"":"?"+uri.getRawQuery();
            return scheme+"://"+host+(uri.getPort()<0?"":":"+uri.getPort())+path+query;
        } catch(Exception invalid){return null;}
    }

    private static String text(JsonObject object,String key){
        return object.has(key)&&!object.get(key).isJsonNull()?object.get(key).getAsString():"";
    }
}
