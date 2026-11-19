package io.buzincast.fireos3probe;

import androidx.media3.common.ColorInfo;
import org.junit.Test;
import static org.junit.Assert.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class LegacyExportColorTest {
    @Test public void omitsUnknownNclxInsteadOfClaimingRgbForYuv() {
        assertNull(LegacyExportColor.complete(null));
        assertNull(LegacyExportColor.complete(new ColorInfo(-1,2,-1,null)));
        assertNull(LegacyExportColor.complete(new ColorInfo(0,2,0,null)));
    }
    @Test public void retainsCompleteHdrAndFullRangeColorInformation() {
        ColorInfo hdr=new ColorInfo(6,2,6,new byte[]{1,2,3}),sdr=new ColorInfo(1,1,3,null);
        assertSame(hdr,LegacyExportColor.complete(hdr));assertSame(sdr,LegacyExportColor.complete(sdr));
        assertArrayEquals(new byte[]{1,2,3},LegacyExportColor.complete(hdr).hdrStaticInfo);
    }
}
