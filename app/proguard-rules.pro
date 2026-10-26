# Keep line numbers for readable crash reports in release builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The app uses no reflection-based serialization: JSON is parsed by app.todo.core.json,
# SQLite rows are mapped by hand, so no extra keep rules are required.
-dontwarn org.jetbrains.annotations.**
