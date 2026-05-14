package com.github.catatafishen.agentbridge.psi;

import com.github.catatafishen.agentbridge.psi.ShellScriptRunConfigXmlBuilder.ShellScriptConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellScriptRunConfigXmlBuilderTest {

    private static Document parse(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        var builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static String option(Document doc, String name) {
        NodeList nodes = doc.getElementsByTagName("option");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            if (name.equals(el.getAttribute("name"))) {
                return el.getAttribute("value");
            }
        }
        return null;
    }

    @Nested
    class IsShellScriptType {

        @Test
        void recognisesShellScriptDisplayName() {
            assertTrue(ShellScriptRunConfigXmlBuilder.isShellScriptType("Shell Script"));
        }

        @Test
        void recognisesShortAlias() {
            assertTrue(ShellScriptRunConfigXmlBuilder.isShellScriptType("sh"));
        }

        @Test
        void recognisesTypeId() {
            assertTrue(ShellScriptRunConfigXmlBuilder.isShellScriptType("ShConfigurationType"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"shell script", "SHELL SCRIPT", "SH"})
        void isCaseInsensitive(String type) {
            assertTrue(ShellScriptRunConfigXmlBuilder.isShellScriptType(type));
        }

        @Test
        void rejectsNullAndOtherTypes() {
            assertFalse(ShellScriptRunConfigXmlBuilder.isShellScriptType(null));
            assertFalse(ShellScriptRunConfigXmlBuilder.isShellScriptType("gradle"));
            assertFalse(ShellScriptRunConfigXmlBuilder.isShellScriptType("application"));
        }
    }

    @Nested
    class Build {

        private ShellScriptConfig minimalConfig() {
            return new ShellScriptConfig(
                "/project/scripts/build.sh", null, null, null, null, null, true
            );
        }

        @Test
        void producesValidXml() throws Exception {
            String xml = ShellScriptRunConfigXmlBuilder.build("My Script", minimalConfig(), "/project");
            Document doc = parse(xml); // throws if not valid XML
            assertEquals("component", doc.getDocumentElement().getTagName());
        }

        @Test
        void configurationHasCorrectTypeAndName() throws Exception {
            String xml = ShellScriptRunConfigXmlBuilder.build("My Script", minimalConfig(), "/project");
            Document doc = parse(xml);
            Element config = (Element) doc.getElementsByTagName("configuration").item(0);
            assertEquals("ShConfigurationType", config.getAttribute("type"));
            assertEquals("My Script", config.getAttribute("name"));
        }

        @ParameterizedTest
        @CsvSource({
            "SCRIPT_PATH, $PROJECT_DIR$/scripts/build.sh",
            "EXECUTE_SCRIPT_FILE, true",
            "INTERPRETER_PATH, /bin/bash"
        })
        void minimalConfigDefaultOptions(String optionName, String expectedValue) throws Exception {
            String xml = ShellScriptRunConfigXmlBuilder.build("Run", minimalConfig(), "/project");
            Document doc = parse(xml);
            assertEquals(expectedValue, option(doc, optionName));
        }

        @Test
        void inlineScriptTextProducesExecuteScriptFileFalse() throws Exception {
            ShellScriptConfig config = new ShellScriptConfig(
                null, "echo hello", null, null, null, null, true
            );
            String xml = ShellScriptRunConfigXmlBuilder.build("Inline", config, "/project");
            Document doc = parse(xml);
            assertEquals("echo hello", option(doc, "SCRIPT_TEXT"));
            assertEquals("false", option(doc, "EXECUTE_SCRIPT_FILE"));
            assertEquals("", option(doc, "SCRIPT_PATH"));
        }

        @Test
        void customInterpreterUsed() throws Exception {
            ShellScriptConfig config = new ShellScriptConfig(
                "/project/run.sh", null, null, "/usr/bin/zsh", null, null, false
            );
            String xml = ShellScriptRunConfigXmlBuilder.build("Run", config, "/project");
            Document doc = parse(xml);
            assertEquals("/usr/bin/zsh", option(doc, "INTERPRETER_PATH"));
        }

        @Test
        void workingDirDefaultsToProjectDir() throws Exception {
            String xml = ShellScriptRunConfigXmlBuilder.build("Run", minimalConfig(), "/project");
            Document doc = parse(xml);
            assertEquals("$PROJECT_DIR$", option(doc, "SCRIPT_WORKING_DIRECTORY"));
        }

        @Test
        void workingDirMacroApplied() throws Exception {
            ShellScriptConfig config = new ShellScriptConfig(
                "/project/run.sh", null, null, null, null, "/project/subdir", true
            );
            String xml = ShellScriptRunConfigXmlBuilder.build("Run", config, "/project");
            Document doc = parse(xml);
            assertEquals("$PROJECT_DIR$/subdir", option(doc, "SCRIPT_WORKING_DIRECTORY"));
        }

        @Test
        void executeInTerminalValueRespected() throws Exception {
            ShellScriptConfig terminalConfig = new ShellScriptConfig(
                "/project/run.sh", null, null, null, null, null, true
            );
            ShellScriptConfig panelConfig = new ShellScriptConfig(
                "/project/run.sh", null, null, null, null, null, false
            );
            Document terminalDoc = parse(ShellScriptRunConfigXmlBuilder.build("T", terminalConfig, "/p"));
            Document panelDoc = parse(ShellScriptRunConfigXmlBuilder.build("P", panelConfig, "/p"));
            assertEquals("true", option(terminalDoc, "EXECUTE_IN_TERMINAL"));
            assertEquals("false", option(panelDoc, "EXECUTE_IN_TERMINAL"));
        }

        @Test
        void configNameWithXmlSpecialCharsIsEscaped() throws Exception {
            ShellScriptConfig config = new ShellScriptConfig(
                null, "echo \"hello\" & <world>", null, null, null, null, true
            );
            String xml = ShellScriptRunConfigXmlBuilder.build("Test & \"Config\"", config, "/p");
            Document doc = parse(xml); // would throw on parse failure
            Element configuration = (Element) doc.getElementsByTagName("configuration").item(0);
            assertEquals("Test & \"Config\"", configuration.getAttribute("name"));
            assertEquals("echo \"hello\" & <world>", option(doc, "SCRIPT_TEXT"));
        }
    }

    @Nested
    class ApplyProjectDirMacro {

        @Test
        void substitutesProjectDirPrefix() {
            assertEquals("$PROJECT_DIR$/foo/bar",
                ShellScriptRunConfigXmlBuilder.applyProjectDirMacro("/home/user/project/foo/bar", "/home/user/project"));
        }

        @Test
        void exactMatch() {
            assertEquals("$PROJECT_DIR$",
                ShellScriptRunConfigXmlBuilder.applyProjectDirMacro("/home/user/project", "/home/user/project"));
        }

        @Test
        void noMatchReturnedUnchanged() {
            assertEquals("/other/path",
                ShellScriptRunConfigXmlBuilder.applyProjectDirMacro("/other/path", "/home/user/project"));
        }

        @Test
        void nullPathReturnsEmpty() {
            assertEquals("", ShellScriptRunConfigXmlBuilder.applyProjectDirMacro(null, "/project"));
        }

        @Test
        void nullProjectDirReturnsOriginalPath() {
            assertEquals("/some/path", ShellScriptRunConfigXmlBuilder.applyProjectDirMacro("/some/path", null));
        }

        @Test
        void emptyPathReturnsEmpty() {
            assertEquals("", ShellScriptRunConfigXmlBuilder.applyProjectDirMacro("", "/project"));
        }
    }

    @Nested
    class EscapeXml {

        @Test
        void escapeAmpersand() {
            assertEquals("a&amp;b", ShellScriptRunConfigXmlBuilder.escapeXml("a&b"));
        }

        @Test
        void escapeDoubleQuote() {
            assertEquals("a&quot;b", ShellScriptRunConfigXmlBuilder.escapeXml("a\"b"));
        }

        @Test
        void escapeLessThan() {
            assertEquals("a&lt;b", ShellScriptRunConfigXmlBuilder.escapeXml("a<b"));
        }

        @Test
        void escapeGreaterThan() {
            assertEquals("a&gt;b", ShellScriptRunConfigXmlBuilder.escapeXml("a>b"));
        }

        @Test
        void noSpecialCharactersUnchanged() {
            assertEquals("hello world", ShellScriptRunConfigXmlBuilder.escapeXml("hello world"));
        }

        @Test
        void nullReturnsEmpty() {
            assertEquals("", ShellScriptRunConfigXmlBuilder.escapeXml(null));
        }

        @Test
        void allSpecialCharsTogether() {
            assertEquals("&amp;&quot;&lt;&gt;", ShellScriptRunConfigXmlBuilder.escapeXml("&\"<>"));
        }
    }
}
