package io.buzincast.legacy;

import androidx.multidex.MultiDexApplication;
import io.github.jqssun.airplay.billing.AppstoreBilling;

/** Store-only entry point: use exactly the same independent IAP/license initialization as the modern app. */
public final class LegacyAppstoreApplication extends MultiDexApplication {
    @Override public void onCreate() {
        super.onCreate();
        AppstoreBilling.INSTANCE.init(this);
    }
}
