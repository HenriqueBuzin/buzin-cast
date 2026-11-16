package io.buzincast.fireos3probe;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReceiverOptionsTest {
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
