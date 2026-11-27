# ical4j and dav4jvm load parsers, factories and XML handlers by reflection/ServiceLoader; R8 would strip them.
-keep class net.fortuna.ical4j.** { *; }
-keep class at.bitfire.dav4jvm.** { *; }
# Optional ical4j/threeten-extra dependencies (Groovy builders, filter parser, Caffeine cache, JVM-only APIs) that Android never loads.
-dontwarn groovy.**
-dontwarn org.codehaus.groovy.**
-dontwarn org.jparsec.**
-dontwarn com.github.benmanes.caffeine.**
-dontwarn java.beans.Transient
-dontwarn java.time.zone.ZoneRulesProvider
-dontwarn org.joda.convert.**
