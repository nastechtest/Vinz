package com.vinz.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The translations as compressed tables (1.8.0, #268; splitTranslations in app/build.gradle.kts, Strings.kt): each
 * language still reads its own words, the words Android shows itself stayed resources, and a string's text comes out
 * as Android's resource compiler made it (checked once against all 115,103 translated strings of 1.7.9.3's APK).
 */
class TranslationTablesTest {
    @Test fun textComesOutAsAapt2MakesIt() {
        assertEquals("a b", Strings.compile("  a \n\t  b  "))
        assertEquals("Couldn't load \"%1\$s\"", Strings.compile("Couldn\\'t load \\\"%1\$s\\\""))
        assertEquals("a\nb", Strings.compile("a\\nb"))
        // An escaped line break or a quoted space at either end is kept; plain white space there isn't.
        assertEquals("…and %1\$s more\n", Strings.compile("…and %1\$s more\\n "))
        assertEquals("  a  b", Strings.compile(" \"  a  \"b "))
        assertEquals("é @ ? \\ 100%", Strings.compile("\\u00e9 \\@ \\? \\\\ 100%"))
        // Only ASCII white space is folded: French's narrow no-break space stays where it is.
        assertEquals("a : b", Strings.compile("a :   b"))
    }

    @Test fun nothingReadsAStringPastTr() {
        // A string read straight from the resources would now be English in every language (the translations aren't
        // resources any more), so the code reads them all through tr()/trp().
        val bad = Regex("""(getString|getText)\(\s*(com\.vinz\.music\.)?R\.string\.""")
        val found = File("src/main/java").walkTopDown().filter { it.extension == "kt" && it.name != "Strings.kt" }
            .flatMap { f -> f.readLines().withIndex().filter { bad.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}" } }.toList()
        assertTrue("Read with tr() instead: $found", found.isEmpty())
    }
}
