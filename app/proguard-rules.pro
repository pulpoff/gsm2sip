# SIP-GSM Gateway - no proguard rules needed for debug builds
# For release builds, keep SIP and RTP classes:
-keep class com.callagent.gateway.sip.** { *; }
-keep class com.callagent.gateway.rtp.** { *; }
-keep class com.callagent.gateway.gsm.** { *; }
