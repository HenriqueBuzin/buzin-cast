package io.buzincast.fireos3probe;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class LegacyUrlRequestTest {
    @Test public void onlyTrustedRecordingFactoryAllowsLocalMediaAndPreservesSeeks() throws Exception {
        java.io.File file=java.io.File.createTempFile("recording-",".mkv");
        try {
            LegacyUrlRequest recording=LegacyUrlRequest.recording(file);
            assertTrue(recording.localRecording);assertEquals("video/x-matroska",recording.mime);
            assertEquals(recording.location,recording.withPosition(1234).location);
            assertTrue(recording.withPosition(1234).localRecording);assertEquals(1234,recording.withPosition(1234).startMs);
            assertThrows(IllegalArgumentException.class,()->new LegacyUrlRequest(recording.location,0,null));
            assertThrows(IllegalArgumentException.class,()->recording.withPosition(-1));
            assertFalse(new LegacyUrlRequest("http://example.test/video",0,null).withPosition(1).localRecording);
        }finally {file.delete();}
        assertThrows(java.io.IOException.class,()->LegacyUrlRequest.recording(file));
    }
    @Test public void copiesHeadersAndPreservesMediaPosition() {
        Map<String,String> headers=new HashMap<>();headers.put("Authorization","Bearer fixture");
        LegacyUrlRequest request=new LegacyUrlRequest("https://example.test/video.mp4?q=1",1234,headers);
        headers.clear();assertEquals("Bearer fixture",request.headers.get("Authorization"));assertEquals(1234,request.startMs);
        assertThrows(UnsupportedOperationException.class,()->request.headers.clear());
    }
    @Test public void rejectsLocalFilesCredentialsAndInvalidNetworkAuthority() {
        for(String url:new String[]{"file:///media.mp4","content://media/1","http://u:p@example.test/video","http:///video","http://example.test:0/v","http://example.test:65536/v","http://example.test/\r\nx"})
            assertThrows(IllegalArgumentException.class,()->new LegacyUrlRequest(url,0,null));
    }
    @Test public void rejectsHeaderInjectionAndFramingOverrides() {
        for(String key:new String[]{"Host","Connection","content-length","Transfer-Encoding","bad:key"}) {
            Map<String,String> values=new HashMap<>();values.put(key,"fixture");
            assertThrows(IllegalArgumentException.class,()->new LegacyUrlRequest("http://example.test/v",0,values));
        }
        Map<String,String> values=new HashMap<>();values.put("X-Fixture","a\r\nb");
        assertThrows(IllegalArgumentException.class,()->new LegacyUrlRequest("http://example.test/v",0,values));
    }
    @Test public void enforcesRequestBudgetsBeforeOpeningAPlayer() {
        assertThrows(IllegalArgumentException.class,()->new LegacyUrlRequest("https://example.test/v",-1,null));
        assertThrows(IllegalArgumentException.class,()->new LegacyUrlRequest("https://example.test/v",1L+LegacyUrlRequest.MAX_POSITION_MS,null));
        Map<String,String> values=new HashMap<>();for(int i=0;i<25;i++)values.put("X-"+i,"v");
        assertThrows(IllegalArgumentException.class,()->new LegacyUrlRequest("https://example.test/v",0,values));
    }
    @Test public void preservesMimeAndLongSeekPositions() {
        LegacyUrlRequest request=new LegacyUrlRequest("https://example.test/stream",1L+Integer.MAX_VALUE,null,"application/dash+xml");
        assertEquals(1L+Integer.MAX_VALUE,request.startMs);assertEquals("application/dash+xml",request.mime);
        assertEquals("video/mp4",new LegacyUrlRequest("https://example.test/v",0,null,"video/mp4; codecs=avc1").mime);
        for(String mime:new String[]{"video/mp4\r\nX: value","file", "video/"})
            assertThrows(IllegalArgumentException.class,()->new LegacyUrlRequest("https://example.test/v",0,null,mime));
    }
}
