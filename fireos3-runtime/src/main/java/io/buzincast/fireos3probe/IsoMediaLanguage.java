package io.buzincast.fireos3probe;

import java.util.Locale;
import java.util.MissingResourceException;

/** ISO 639-2 media labels without Locale.forLanguageTag (API21). */
final class IsoMediaLanguage {
    static String normalize(String tag) {
        if(tag==null)return null;
        if(tag.length()>128)return "und";
        String primary=tag.trim().replace('_','-').split("-",2)[0].toLowerCase(Locale.ROOT);
        if(!primary.matches("[a-z]{2,3}"))return "und";
        try {String iso=new Locale(primary).getISO3Language();return iso.matches("[a-z]{3}")?iso:"und";}
        catch(MissingResourceException unavailable) {return "und";}
    }
}
