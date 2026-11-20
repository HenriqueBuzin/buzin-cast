// Included by the instrumented Android DNS-SD shim to exercise its private record storage.
const char *android_dnssd_txt_capacity_contract(void) {
    txt_record_t record = {0};
    char key[MAX_TXT_KEY];
    for (int index = 0; index < MAX_TXT_ENTRIES; ++index) {
        snprintf(key, sizeof(key), "key-%d", index);
        _txt_set(&record, key, "original");
    }
    if (record.count != MAX_TXT_ENTRIES) return "TXT record did not fill to capacity";

    _txt_set(&record, key, "updated");
    _txt_set(&record, "over-capacity", "unexpected");
    if (record.count != MAX_TXT_ENTRIES) return "TXT record size changed at capacity";

    for (int index = 0; index < MAX_TXT_ENTRIES; ++index) {
        snprintf(key, sizeof(key), "key-%d", index);
        if (strcmp(record.entries[index].key, key) != 0) return "TXT key or entry order changed";
        const char *expected = index == MAX_TXT_ENTRIES - 1 ? "updated" : "original";
        if (strcmp(record.entries[index].val, expected) != 0) return "Unexpected TXT value at capacity";
    }
    return NULL;
}
