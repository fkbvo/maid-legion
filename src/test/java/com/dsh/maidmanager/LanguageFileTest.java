package com.dsh.maidmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Guards the language files and the code that refers to them.
 *
 * <p>These three checks exist because each failure mode is silent in game:
 *
 * <ul>
 *   <li>A <b>malformed</b> file makes Minecraft drop the whole locale, so every string in the mod
 *       falls back to its raw key. That is a total loss of translation from one stray quote, and
 *       nothing else in the build notices - the jar still compiles and loads.</li>
 *   <li>An <b>unbalanced</b> pair leaves one language half-translated, which only shows up when
 *       someone switches locale.</li>
 *   <li>A <b>missing</b> key renders as the key itself, so a typo in a component literal is
 *       invisible until that exact screen is opened.</li>
 * </ul>
 */
public class LanguageFileTest {

    private static final Path LANG = Paths.get("src", "main", "resources", "assets",
            "touhou_maid_legion", "lang");
    private static final Path JAVA = Paths.get("src", "main", "java");

    /** Matches only key literals passed straight to {@code Component.translatable}. */
    private static final Pattern TRANSLATABLE =
            Pattern.compile("translatable\\(\\s*\"([^\"]+)\"");

    private static JsonObject parse(String locale) throws IOException {
        String text = Files.readString(LANG.resolve(locale), StandardCharsets.UTF_8);
        JsonElement parsed = JsonParser.parseString(text);
        assertTrue(locale + " must be a JSON object", parsed.isJsonObject());
        return parsed.getAsJsonObject();
    }

    @Test
    public void bothLocalesAreWellFormedJson() throws IOException {
        // A single missing quote here costs every translation in the mod, so it is asserted
        // rather than assumed. (It happened: one entry lost its closing quote.)
        assertTrue("zh_cn.json must parse", parse("zh_cn.json").size() > 0);
        assertTrue("en_us.json must parse", parse("en_us.json").size() > 0);
    }

    @Test
    public void theTwoLocalesDefineTheSameKeys() throws IOException {
        Set<String> zh = parse("zh_cn.json").keySet();
        Set<String> en = parse("en_us.json").keySet();
        assertEquals("one locale is incomplete",
                new TreeSet<>(zh), new TreeSet<>(en));
        assertTrue("expected a non-trivial number of keys", zh.size() > 20);
    }

    @Test
    public void noLocaleHasABlankValue() throws IOException {
        for (String locale : new String[]{"zh_cn.json", "en_us.json"}) {
            JsonObject json = parse(locale);
            for (String key : json.keySet()) {
                assertTrue(locale + " has a blank value for " + key,
                        !json.get(key).getAsString().trim().isEmpty());
            }
        }
    }

    @Test
    public void everyKeyTheCodeReferencesExists() throws IOException {
        Set<String> defined = parse("zh_cn.json").keySet();
        Set<String> referenced = referencedKeys();
        assertTrue("expected to find some translatable() calls", referenced.size() > 20);

        Set<String> missing = new TreeSet<>();
        for (String key : referenced) {
            // Keys built by concatenation (e.g. a per-upgrade prefix plus its id) cannot be
            // matched literally; those families are covered by ModIdentityTest's prefix check.
            if (key.endsWith(".")) {
                continue;
            }
            if (!defined.contains(key)) {
                missing.add(key);
            }
        }
        assertEquals("keys referenced in Java but missing from the language files",
                new TreeSet<String>(), missing);
    }

    /** Key literals passed to {@code Component.translatable} in the main sources. */
    private static Set<String> referencedKeys() throws IOException {
        Set<String> keys = new HashSet<>();
        try (Stream<Path> files = Files.walk(JAVA)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    // Skip comments: prose about keys is not a reference to one.
                    if (trimmed.startsWith("*") || trimmed.startsWith("//")
                            || trimmed.startsWith("/*")) {
                        continue;
                    }
                    Matcher matcher = TRANSLATABLE.matcher(line);
                    while (matcher.find()) {
                        keys.add(matcher.group(1));
                    }
                }
            }
        }
        return keys;
    }
}
