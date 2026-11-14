# just downloader 출시판 코드 줄이기(R8) 보존 규칙 v0.1.0 (2026-11-14, 앱 0.7.5)
# 플레이 콘솔 '앱 코드 최적화(난독화 1%)' 경고 해결용. 우리 코드·androidx·Media3·material 은 줄이고 이름도 바꾼다.
# 아래는 이름·구조를 그대로 둬야 하는 것만 남긴다.

# 받기 라이브러리(youtubedl-android: yt-dlp·ffmpeg 실행기) — 안에서 클래스를 이름으로 찾고 결과를 jackson 으로 읽음.
# 보존 규칙을 따로 주지 않는 라이브러리라 통째로 둔다.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class org.apache.commons.compress.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
-dontwarn java.beans.**
-dontwarn org.slf4j.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**

# 꺼짐 기록에서 줄 번호를 볼 수 있게(이름 대응표는 aab 안에 같이 들어가 플레이가 자동으로 풀어 줌)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
