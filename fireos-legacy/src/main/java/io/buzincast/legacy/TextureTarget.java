package io.buzincast.legacy;

import android.graphics.SurfaceTexture;
import android.view.Surface;

/** The view and any retiring decoder wrappers have independent lifetimes. */
final class TextureTarget {
    final SurfaceTexture texture;
    private int leases;
    private boolean destroyed,released;
    TextureTarget(SurfaceTexture texture) { this.texture=texture; }
    synchronized Surface acquire() {
        if(destroyed)throw new IllegalStateException("Texture view already destroyed");
        Surface surface=new Surface(texture);leases++;return surface;
    }
    synchronized void retired() {
        if(leases<=0)throw new IllegalStateException("Unbalanced display lease");
        leases--;releaseIfUnused();
    }
    synchronized void destroyView() { destroyed=true;releaseIfUnused(); }
    private void releaseIfUnused() {
        if(destroyed && leases==0 && !released) {released=true;texture.release();}
    }
}
