# 只允许按注解/类名精确保留；禁止 -keep class ** { *; }（G11 会扫描该文件）。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
