package com.minion.core.agent;

import com.minion.core.llm.LlmException;

/** Shared root/child protocol rules. Partial output is not a license to retry an entire turn. */
public final class TurnSemantics {
    private TurnSemantics(){}
    public static boolean transientFailure(LlmException e){
        return e!=null&&(e.type==LlmException.Type.RATE_LIMIT||e.type==LlmException.Type.TIMEOUT
                ||e.type==LlmException.Type.NETWORK&&e.retryable||e.httpCode==500||e.httpCode==502);
    }
    public static boolean visible(CharSequence text){
        if(text==null)return false;
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(!Character.isWhitespace(c)&&c!='\u200B'&&c!='\uFEFF')return true;}
        return false;
    }
    public static boolean noOutput(CharSequence content,CharSequence thinking){return !visible(content)&&!visible(thinking);}
}
