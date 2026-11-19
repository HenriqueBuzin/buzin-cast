package io.buzincast.fireos3probe;

import androidx.media3.common.ColorInfo;
import androidx.media3.common.util.UnstableApi;

/** Media3 1.2 writes absent nclx fields as reserved zero values; omit incomplete declarations. */
@androidx.annotation.OptIn(markerClass=UnstableApi.class)
final class LegacyExportColor {
    static ColorInfo complete(ColorInfo color) {
        if(color==null)return null;
        boolean space=color.colorSpace==1 || color.colorSpace==2 || color.colorSpace==6;
        boolean transfer=color.colorTransfer==1 || color.colorTransfer==3 || color.colorTransfer==6 || color.colorTransfer==7;
        return space && transfer && (color.colorRange==1 || color.colorRange==2)?color:null;
    }
}
