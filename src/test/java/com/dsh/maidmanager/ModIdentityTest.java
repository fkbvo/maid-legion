package com.dsh.maidmanager;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the invariants that a rename can silently break.
 *
 * <p>These exist because the project has already shipped the exact failure this class checks
 * for: the mod was registered as {@code maid_manager} while its assets lived in
 * {@code assets/maidmanager/}. Minecraft looks translations up under
 * {@code assets/<modid>/lang/}, so every string in the GUI silently fell back to its raw
 * translation key - a bug that compiles, tests and looks fine in a jar listing, and only
 * shows up in game.
 *
 * <p>These run as plain JUnit with no Minecraft runtime: they inspect the source tree on
 * disk, so they fail fast at build time instead of in front of a player.
 */
public class ModIdentityTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final Pattern MOD_ID = Pattern.compile("MOD_ID\\s*=\\s*\"([^\"]+)\"");

    /** Reads the mod id from the one place it is declared in code. */
    private static String modId() throws IOException {
        Path modClass = Path.of("src", "main", "java", "com", "dsh", "maidmanager",
                "MaidManagerMod.java");
        String source = Files.readString(modClass, StandardCharsets.UTF_8);
        Matcher matcher = MOD_ID.matcher(source);
        assertTrue("MOD_ID constant not found in MaidManagerMod.java", matcher.find());
        return matcher.group(1);
    }

    /**
     * The single most important invariant: assets must live under {@code assets/<modid>/},
     * or no translation loads.
     */
    @Test
    public void assetsDirectoryMatchesModId() throws IOException {
        String id = modId();
        Path assets = RESOURCES.resolve("assets").resolve(id);
        assertTrue("Translations must live in assets/" + id + "/lang, but that directory "
                + "does not exist. Every GUI string will show as a raw key in game.",
                Files.isDirectory(assets));

        Path lang = assets.resolve("lang");
        assertTrue("assets/" + id + "/lang is missing", Files.isDirectory(lang));
        assertTrue("zh_cn.json is missing", Files.exists(lang.resolve("zh_cn.json")));
        assertTrue("en_us.json is missing", Files.exists(lang.resolve("en_us.json")));
    }

    /** Every translation key must be namespaced with the mod id, never a stale one. */
    @Test
    public void languageKeysUseTheModId() throws IOException {
        String id = modId();
        for (String locale : List.of("zh_cn.json", "en_us.json")) {
            String json = Files.readString(
                    RESOURCES.resolve("assets").resolve(id).resolve("lang").resolve(locale),
                    StandardCharsets.UTF_8);
            assertTrue(locale + " should contain keys prefixed '" + id + "'", json.contains(id + "."));
            assertFalse(locale + " still references a stale 'maid_manager' namespace",
                    json.contains("maid_manager"));
        }
    }

    /** The two locale files must define exactly the same key set. */
    @Test
    public void localeFilesHaveMatchingKeys() throws IOException {
        String id = modId();
        Set<String> zh = keysOf(RESOURCES.resolve("assets").resolve(id)
                .resolve("lang").resolve("zh_cn.json"));
        Set<String> en = keysOf(RESOURCES.resolve("assets").resolve(id)
                .resolve("lang").resolve("en_us.json"));
        assertEquals("zh_cn and en_us define different key sets; one locale is incomplete",
                zh, en);
        assertTrue("expected a non-trivial number of keys", zh.size() > 20);
    }

    /** Crude but sufficient reader: pulls the top-level "key": pairs out of the JSON. */
    private static Set<String> keysOf(Path jsonFile) throws IOException {
        String json = Files.readString(jsonFile, StandardCharsets.UTF_8);
        Set<String> keys = new TreeSet<>();
        Matcher matcher = Pattern.compile("^\\s*\"([^\"]+)\"\\s*:", Pattern.MULTILINE).matcher(json);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    /**
     * The dist jar name is derived from mod_id, so a stale id here would ship a jar whose
     * name disagrees with the mod inside it.
     */
    @Test
    public void gradlePropertiesAgreeWithTheCode() throws IOException {
        String id = modId();
        String props = Files.readString(Path.of("gradle.properties"), StandardCharsets.UTF_8);
        assertTrue("gradle.properties must declare mod_id=" + id,
                props.contains("mod_id=" + id));

        try (Stream<Path> walk = Files.walk(Path.of("src", "main", "resources"))) {
            boolean stale = walk.filter(Files::isRegularFile).anyMatch(p -> {
                try {
                    return Files.readString(p, StandardCharsets.UTF_8).contains("maid_manager");
                } catch (IOException e) {
                    return false;
                }
            });
            assertFalse("a resource file still references the old 'maid_manager' namespace", stale);
        }
    }
}
