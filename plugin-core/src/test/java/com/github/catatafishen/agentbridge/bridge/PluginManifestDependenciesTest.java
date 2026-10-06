package com.github.catatafishen.agentbridge.bridge;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the {@code <depends>} entries in plugin.xml.
 *
 * <p>A declared plugin dependency makes that plugin's class loader a parent of ours, and parents are searched
 * before the IDE's own libraries. SonarQube for IDE bundles kotlin-stdlib 1.6.10, so declaring it made
 * {@code KotlinVersion} resolve to 1.6.10 instead of the platform's 2.x and broke the Koog agent. Only
 * JetBrains-bundled modules and plugins are allowed here; marketplace plugins are reached by reflection through
 * {@code PlatformApiCompat.getPluginClassLoader}, which needs no declaration.</p>
 *
 * <p>Adding an entry to {@link #ALLOWED} is a deliberate review step: check what the plugin bundles first.</p>
 */
class PluginManifestDependenciesTest {

    /**
     * Platform modules and JetBrains-bundled plugins, built with the platform's own libraries.
     */
    private static final Set<String> ALLOWED = Set.of(
        "com.intellij.modules.platform",
        "com.intellij.modules.java",
        "com.intellij.modules.jcef",
        "Git4Idea",
        "org.jetbrains.plugins.terminal",
        "org.jetbrains.plugins.gradle",
        "org.jetbrains.idea.maven",
        "com.intellij.database"
    );

    @Test
    void onlyJetBrainsBundledPluginsAreDeclared() throws Exception {
        Set<String> unexpected = new TreeSet<>();
        for (Element depends : dependsElements()) {
            String id = depends.getTextContent().trim();
            if (!ALLOWED.contains(id)) {
                unexpected.add(id);
            }
        }

        assertTrue(unexpected.isEmpty(),
            "plugin.xml declares " + unexpected + ". A <depends> makes that plugin's class loader a parent of "
                + "ours, so anything it bundles (e.g. SonarQube's kotlin-stdlib 1.6.10) can shadow the platform's "
                + "library. Reach marketplace plugins by reflection instead (PlatformApiCompat.getPluginClassLoader). "
                + "If this is a JetBrains-bundled plugin, add it to ALLOWED after checking what it bundles.");
    }

    @Test
    void everyConfigFileReferencedByADependencyExists() throws Exception {
        List<String> missing = new ArrayList<>();
        for (Element depends : dependsElements()) {
            String configFile = depends.getAttribute("config-file");
            if (configFile.isEmpty()) continue;
            try (InputStream in = PluginManifestDependenciesTest.class.getResourceAsStream("/META-INF/" + configFile)) {
                if (in == null) {
                    missing.add(configFile);
                }
            }
        }

        assertTrue(missing.isEmpty(),
            "plugin.xml references config files that are not on the classpath: " + missing
                + ". An optional dependency with a missing config-file fails plugin loading when that plugin is present.");
    }

    private static List<Element> dependsElements() throws Exception {
        try (InputStream in = PluginManifestDependenciesTest.class.getResourceAsStream("/META-INF/plugin.xml")) {
            assertNotNull(in, "plugin.xml should be on the test classpath");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            // Parsed as XML, so a mention inside a comment is not mistaken for a declaration.
            Document document = factory.newDocumentBuilder().parse(in);
            NodeList nodes = document.getDocumentElement().getElementsByTagName("depends");
            List<Element> result = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                result.add((Element) nodes.item(i));
            }
            assertTrue(!result.isEmpty(), "plugin.xml should declare at least the platform dependency");
            return result;
        }
    }
}
