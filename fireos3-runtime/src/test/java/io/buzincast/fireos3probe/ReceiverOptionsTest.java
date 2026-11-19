package io.buzincast.fireos3probe;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReceiverOptionsTest {
    @Test public void pinIsExplicitAndDisabledInExistingConstructors() {
        assertFalse(new ReceiverOptions("TV",7000).requirePin);
        assertFalse(new ReceiverOptions("TV",7000,1280,720,30,false,20,95).requirePin);
        assertTrue(new ReceiverOptions("TV",7000,1280,720,30,false,20,95,true).requirePin);
    }
    @Test public void validatesMediaBoundsBeforeCrossingJni() {
        ReceiverOptions config=new ReceiverOptions("TV",7000,1920,1080,30,false,0,99);
        assertEquals(1920,config.width);assertEquals(0,config.cushionMs);assertEquals(99,config.percentile);
        assertThrows(IllegalArgumentException.class,()->new ReceiverOptions("TV",7000,0,720,30,false,20,95));
        assertThrows(IllegalArgumentException.class,()->new ReceiverOptions("TV",7000,1280,720,0,false,20,95));
        assertThrows(IllegalArgumentException.class,()->new ReceiverOptions("TV",7000,1280,720,30,false,-1,95));
        assertThrows(IllegalArgumentException.class,()->new ReceiverOptions("TV",7000,1280,720,30,false,20,101));
    }
    @Test public void rejectsBlankAndControlCharacters() {
        assertFalse(ReceiverOptions.validName("")); assertFalse(ReceiverOptions.validName("\u2003"));
        assertFalse(ReceiverOptions.validName("TV\nRoom")); assertFalse(ReceiverOptions.validName(null));
    }
    @Test public void appliesDnsUtf8ByteLimit() {
        assertTrue(ReceiverOptions.validName("á".repeat(31)+"a"));
        assertFalse(ReceiverOptions.validName("á".repeat(32)));
        assertTrue(ReceiverOptions.validName("Buzin テレビ"));
    }
    @Test public void preservesPortAndNameAndRejectsOutOfRange() {
        ReceiverOptions config=new ReceiverOptions("Buzin Cast",7000);
        assertEquals(7000,config.port); assertEquals("Buzin Cast",config.name);
        assertThrows(IllegalArgumentException.class,()->new ReceiverOptions("TV",0));
        assertThrows(IllegalArgumentException.class,()->new ReceiverOptions("TV",65536));
    }
}
