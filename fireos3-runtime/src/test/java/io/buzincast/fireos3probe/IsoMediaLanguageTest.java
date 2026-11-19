package io.buzincast.fireos3probe;

import org.junit.Test;
import static org.junit.Assert.*;

public class IsoMediaLanguageTest {
    @Test public void handlesRegionScriptAndExistingIso3Codes() {
        assertEquals("eng",IsoMediaLanguage.normalize("en-US"));assertEquals("por",IsoMediaLanguage.normalize("pt_BR"));
        assertEquals("zho",IsoMediaLanguage.normalize("zh-Hant-TW"));assertEquals("heb",IsoMediaLanguage.normalize("iw"));
        assertEquals("cor",IsoMediaLanguage.normalize("kw"));assertEquals("eng",IsoMediaLanguage.normalize("eng"));
    }
    @Test public void preservesAbsenceAndSafelyHandlesInvalidTags() {
        assertNull(IsoMediaLanguage.normalize(null));assertEquals("und",IsoMediaLanguage.normalize("und"));
        for(String value:new String[]{"","-US","badtag","zz-AA","en\r\nother"})assertEquals("und",IsoMediaLanguage.normalize(value));
    }
}
