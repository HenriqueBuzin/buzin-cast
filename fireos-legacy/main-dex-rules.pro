# This class exists only in androidTest; its standalone provider process
# must not depend on the instrumentation runner to install secondary dex.
-keep class io.buzincast.legacy.LegacySubtitleFixtureProvider** { *; }
