package app.todo

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards two classes of resource bug that the compiler cannot see:
 *
 *  1. a string with a positional placeholder (`%1$s`) rendered through
 *     `stringResource(...)` without arguments — the user then sees `%1$s` on screen
 *     (this is exactly what happened to "上次同步");
 *  2. a key that exists in one locale but not the other.
 */
class StringResourceConsistencyTest {

    private val moduleRoot: File = run {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/res/values/strings.xml").isFile) return@run dir
            if (File(dir, "app/src/main/res/values/strings.xml").isFile) return@run File(dir, "app")
            dir = dir.parentFile
        }
        error("cannot locate the app module from ${File("").absolutePath}")
    }

    private val stringsEn: File = File(moduleRoot, "src/main/res/values/strings.xml")
    private val stringsZh: File = File(moduleRoot, "src/main/res/values-zh/strings.xml")

    private fun entries(file: File): Map<String, String> {
        val regex = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        return regex.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun placeholderIndices(value: String): List<String> =
        Regex("""%(\d)\$[sd]""").findAll(value).map { it.groupValues[1] }.sorted().toList()

    private fun kotlinSources(): List<File> =
        File(moduleRoot, "src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun bothLocalesDefineTheSameKeys() {
        val en = entries(stringsEn).keys
        val zh = entries(stringsZh).keys
        assertTrue("missing in values-zh: ${en - zh}", (en - zh).isEmpty())
        assertTrue("missing in values: ${zh - en}", (zh - en).isEmpty())
    }

    @Test
    fun placeholdersMatchAcrossLocales() {
        val en = entries(stringsEn)
        val zh = entries(stringsZh)
        val mismatched = en.keys.filter { key ->
            zh.containsKey(key) &&
                placeholderIndices(en.getValue(key)) != placeholderIndices(zh.getValue(key))
        }
        assertTrue("placeholder mismatch between locales: $mismatched", mismatched.isEmpty())
    }

    @Test
    fun apostrophesAreEscaped() {
        // AAPT2 refuses an unescaped apostrophe ("Invalid unicode escape sequence"),
        // which is a build error rather than a warning — catch it in the test instead.
        val unescaped = Regex("""(?<!\\)'""")
        val offenders = (entries(stringsEn) + entries(stringsZh))
            .filterValues { unescaped.containsMatchIn(it) }
            .keys
        assertTrue("unescaped apostrophes in: $offenders", offenders.isEmpty())
    }

    @Test
    fun formatStringsAreAlwaysCalledWithArguments() {
        // Check both locales: a placeholder that only exists in one of them is still
        // rendered literally for users of that language.
        val formatStrings = (entries(stringsEn) + entries(stringsZh))
            .filterValues { Regex("""%\d\$[sd]""").containsMatchIn(it) }
            .keys

        val offenders = mutableListOf<String>()
        for (source in kotlinSources()) {
            val lines = source.readLines()
            lines.forEachIndexed { index, line ->
                formatStrings.forEach { name ->
                    val marker = "R.string.$name"
                    var from = line.indexOf(marker)
                    while (from >= 0) {
                        val after = line.substring(from + marker.length).trimStart()
                        val passesArguments = after.startsWith(",")
                        if (!passesArguments) {
                            offenders += "${source.relativeTo(moduleRoot)}:${index + 1} -> $name"
                        }
                        from = line.indexOf(marker, from + marker.length)
                    }
                }
            }
        }

        assertTrue(
            "format strings used without arguments: ${offenders.joinToString()}",
            offenders.isEmpty(),
        )
    }
}
