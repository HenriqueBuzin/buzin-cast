package io.buzincast.legacy;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;

/** Only synthetic, fixed fixtures. No filesystem or user data is exposed. */
public final class LegacySubtitleFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() {return true;}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("Read only");
        try {
            byte[] bytes;
            switch(uri.getLastPathSegment()) {
                case "srt":bytes="1\n00:00:00,000 --> 00:00:10,000\nSynthetic SRT caption\n".getBytes("UTF-8");break;
                case "vtt":bytes="\uFEFFWEBVTT\n\n00:00.000 --> 00:10.000\nSynthetic VTT caption\n".getBytes("UTF-8");break;
                case "invalid":bytes="Not subtitles".getBytes("UTF-8");break;
                case "malformed":bytes=new byte[]{(byte)0xc3,0x28};break;
                case "oversized":bytes=new byte[2*1024*1024+1];break;
                default:throw new FileNotFoundException("Unknown synthetic fixture");
            }
            ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
            new Thread(()-> {try(ParcelFileDescriptor.AutoCloseOutputStream stream=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {stream.write(bytes);}catch(java.io.IOException closed) { /* Rejection closes the reader. */ }},"subtitle-fixture").start();
            return pipe[0];
        }catch(java.io.IOException error) {throw new FileNotFoundException(error.toString());}
    }
    @Override public String getType(Uri uri) {return "text/plain";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {return null;}
    @Override public Uri insert(Uri uri,ContentValues values) {throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args) {throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) {throw new UnsupportedOperationException();}
}
