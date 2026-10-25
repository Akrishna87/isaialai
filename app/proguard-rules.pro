# Compose, Media3 and AndroidX ship their own consumer rules; the one rule below is for casting.

# Named only in the manifest (cast options), so R8 can't see it's used.
-keep class io.github.akrishna87.mymusic.CastOptionsProvider { <init>(); *; }
