package com.minion.gui.input;

import java.util.*;

/** Memory-only snapshot: immutable chips share their strings/images, never JavaFX nodes or session IDs. */
final class ComposerDraft {
    final String text,inline;
    final int anchor,caret,pasteSeq;
    final double scrollTop,scrollLeft;
    final List<InputChip> chips;
    final Map<String,InputChip> dropped;
    ComposerDraft(String text,int anchor,int caret,double top,double left,int seq,String inline,
                  List<InputChip> chips,Map<String,InputChip> dropped){
        this.text=text;this.anchor=anchor;this.caret=caret;scrollTop=top;scrollLeft=left;pasteSeq=seq;this.inline=inline;
        this.chips=new ArrayList<InputChip>(chips);this.dropped=new LinkedHashMap<String,InputChip>(dropped);
    }
}
