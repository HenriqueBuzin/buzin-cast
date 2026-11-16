package io.buzincast.legacy;

import android.view.KeyEvent;
import io.buzincast.fireos3probe.LegacyDacp;

/** The Activity and system media receiver use the same press/release semantics. */
final class LegacyMediaKeys {
    static boolean handles(int key) {
        return key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key==KeyEvent.KEYCODE_MEDIA_PLAY ||
            key==KeyEvent.KEYCODE_MEDIA_PAUSE || key==KeyEvent.KEYCODE_MEDIA_STOP ||
            key==KeyEvent.KEYCODE_MEDIA_NEXT || key==KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
            key==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || key==KeyEvent.KEYCODE_MEDIA_REWIND;
    }
    static LegacyDacp.Command command(int key,boolean down,int repeat,boolean playing) {
        if(!down)return key==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || key==KeyEvent.KEYCODE_MEDIA_REWIND?LegacyDacp.Command.RESUME:null;
        if(repeat!=0)return null;
        switch(key) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:return playing?LegacyDacp.Command.PAUSE:LegacyDacp.Command.PLAY;
            case KeyEvent.KEYCODE_MEDIA_PLAY:return LegacyDacp.Command.PLAY;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:case KeyEvent.KEYCODE_MEDIA_STOP:return LegacyDacp.Command.PAUSE;
            case KeyEvent.KEYCODE_MEDIA_NEXT:return LegacyDacp.Command.NEXT;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:return LegacyDacp.Command.PREVIOUS;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:return LegacyDacp.Command.FAST_FORWARD;
            case KeyEvent.KEYCODE_MEDIA_REWIND:return LegacyDacp.Command.REWIND;
            default:return null;
        }
    }
}
