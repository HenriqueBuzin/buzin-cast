package io.buzincast.legacy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import io.buzincast.fireos3probe.LegacyUrlRequest;
import io.github.jqssun.airplay.media.PhotoActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Keeps the same 16 MiB input limit without retaining two encoded copies in a small heap. */
public final class LegacyPhotoLoader {
    private static final int MAX_BYTES=16*1024*1024;
    private LegacyPhotoLoader() {}
    public static Bitmap load(Context context,String location) throws Exception {
        File temporary=File.createTempFile("dlna-photo-",".image",context.getCacheDir());
        try {
            URL url=new URL(new LegacyUrlRequest(location,0,null).location);
            for(int redirect=0;redirect<5;redirect++) {
                // Validate every redirect before opening a connection.
                url=new URL(new LegacyUrlRequest(url.toString(),0,null).location);
                HttpURLConnection connection=(HttpURLConnection)url.openConnection();
                connection.setConnectTimeout(5000);connection.setReadTimeout(8000);connection.setInstanceFollowRedirects(false);
                try {
                    int status=connection.getResponseCode();
                    if(status>=300 && status<400) {
                        String next=connection.getHeaderField("Location");
                        if(next==null)throw failure(R.string.photo_error_redirect);
                        url=new URL(url,next);continue;
                    }
                    if(status!=200)throw failure(R.string.photo_error);
                    if(connection.getContentLength()>MAX_BYTES)throw failure(R.string.photo_error_size);
                    try(InputStream input=connection.getInputStream();FileOutputStream output=new FileOutputStream(temporary)) {
                        byte[] chunk=new byte[16384];int count,total=0;
                        while((count=input.read(chunk))!=-1) {
                            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Photo load interrupted");
                            if(count>MAX_BYTES-total)throw failure(R.string.photo_error_size);
                            output.write(chunk,0,count);total+=count;
                        }
                    }
                    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                    BitmapFactory.decodeFile(temporary.getAbsolutePath(),bounds);
                    if(bounds.outWidth<=0 || bounds.outHeight<=0)throw failure(R.string.photo_error_format);
                    int sample=1;while(bounds.outWidth/sample>1920 || bounds.outHeight/sample>1080)sample*=2;
                    BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;
                    Bitmap bitmap=BitmapFactory.decodeFile(temporary.getAbsolutePath(),options);
                    if(bitmap==null)throw failure(R.string.photo_error_format);
                    return bitmap;
                }finally {connection.disconnect();}
            }
            throw failure(R.string.photo_error_redirect);
        }finally {
            if(!temporary.delete() && temporary.exists())android.util.Log.w("LegacyPhotoLoader","Photo cache cleanup failed");
        }
    }
    private static PhotoActivity.PhotoLoadException failure(int message) {return new PhotoActivity.PhotoLoadException(message);}
}
