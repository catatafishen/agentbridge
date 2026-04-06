package com.github.catatafishen.agentbridge.psi;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fetches rule descriptions from SonarLint's BackendService via reflection and formats
 * a "Referenced Rules" section to append to SonarQube analysis output.
 *
 * <p>All interaction is via reflection since SonarLint has no public API.
 */
@SuppressWarnings("java:S3011") // setAccessible is inherent to reflection-based SonarLint integration
public final class SonarRuleDescriptions {

    private static final Logger LOG = Logger.getInstance(SonarRuleDescriptions.class);

    private static final String SONAR_PLUGIN_ID = "org.sonarlint.idea";
    private static final String BACKEND_SERVICE_CLASS = "org.sonarlint.intellij.core.BackendService";
    private static final String PARAMS_CLASS =
        "org.sonarsource.sonarlint.core.rpc.protocol.backend.rules.GetStandaloneRuleDescriptionParams";

    // Matches [SEVERITY/ruleKey] in finding format "%s:%d [%s/%s] %s", e.g. "[MAJOR/java:S3776]"
    private static final int MAX_DESCRIPTION_CHARS = 800;
    private static final int FETCH_TIMEOUT_MS = 8_000;

    private SonarRuleDescriptions() {
    }

    /**
     * Fetches descriptions for the given rule keys and returns a formatted section.
     * Used by {@code get_sonar_rule_description} tool for on-demand lookups.
     */
    @NotNull
    public static String buildRulesSectionForKeys(@NotNull Collection<String> ruleKeys) {
        ClassLoader cl = PlatformApiCompat.getPluginClassLoader(SONAR_PLUGIN_ID);
        if (cl == null) return "SonarLint plugin not available.";
        if (ruleKeys.isEmpty()) return "No rule keys provided.";

        LinkedHashSet<String> keys = new LinkedHashSet<>(ruleKeys);
        Map<String, String> descriptions = fetchDescriptions(keys, cl);
        if (descriptions.isEmpty()) return "No descriptions found for the requested rule keys.";

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : descriptions.entrySet()) {
            sb.append(entry.getValue()).append("\n\n");
        }
        return sb.toString().trim();
    }

    private static Map<String, String> fetchDescriptions(Set<String> ruleKeys, ClassLoader cl) {
        try {
            Class<?> backendClass = Class.forName(BACKEND_SERVICE_CLASS, true, cl);
            Object backend = PlatformApiCompat.getApplicationServiceByRawClass(backendClass);
            if (backend == null) return Collections.emptyMap();

            Class<?> paramsClass = Class.forName(PARAMS_CLASS, true, cl);
            Constructor<?> paramsCtor = paramsClass.getConstructor(String.class);
            Method getDetailsMethod = backendClass.getMethod("getStandaloneRuleDetails", paramsClass);

            List<Map.Entry<String, CompletableFuture<?>>> futures = requestAllDescriptions(ruleKeys, paramsCtor, getDetailsMethod, backend);
            return collectResults(futures);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyMap();
        } catch (Exception e) {
            LOG.debug("Rule description fetch failed: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    private static List<Map.Entry<String, CompletableFuture<?>>> requestAllDescriptions(
        Set<String> ruleKeys, Constructor<?> paramsCtor, Method getDetailsMethod, Object backend) {
        List<Map.Entry<String, CompletableFuture<?>>> futures = new ArrayList<>();
        for (String ruleKey : ruleKeys) {
            CompletableFuture<?> future = requestDescription(ruleKey, paramsCtor, getDetailsMethod, backend);
            if (future != null) futures.add(Map.entry(ruleKey, future));
        }
        return futures;
    }

    @Nullable
    private static CompletableFuture<?> requestDescription(
        String ruleKey, Constructor<?> paramsCtor, Method getDetailsMethod, Object backend) {
        try {
            Object params = paramsCtor.newInstance(ruleKey);
            @SuppressWarnings("unchecked") // Raw class from foreign classloader; result used as Object only
            CompletableFuture<Object> future = (CompletableFuture<Object>) getDetailsMethod.invoke(backend, params);
            return future;
        } catch (ReflectiveOperationException e) {
            LOG.debug("Failed to request description for " + ruleKey + ": " + e.getMessage());
            return null;
        }
    }

    private static Map<String, String> collectResults(List<Map.Entry<String, CompletableFuture<?>>> futures)
        throws InterruptedException {
        Map<String, String> result = new LinkedHashMap<>();
        long deadline = System.currentTimeMillis() + FETCH_TIMEOUT_MS;
        for (Map.Entry<String, CompletableFuture<?>> entry : futures) {
            long remaining = Math.max(100, deadline - System.currentTimeMillis());
            collectResult(result, entry.getKey(), entry.getValue(), remaining);
        }
        return result;
    }

    private static void collectResult(
        Map<String, String> result, String ruleKey, CompletableFuture<?> future, long timeoutMs)
        throws InterruptedException {
        try {
            Object response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            String formatted = formatResponse(ruleKey, response);
            if (formatted != null) result.put(ruleKey, formatted);
        } catch (ExecutionException | TimeoutException e) {
            LOG.debug("Failed to get description for " + ruleKey + ": " + e.getMessage());
        }
    }

    @Nullable
    private static String formatResponse(String ruleKey, Object response) {
        try {
            String name = (String) call(call(response, "getRuleDefinition"), "getName");
            Object either = call(response, "getDescription");
            if (either == null) return null;
            String html = extractHtml(either);
            String text = htmlToText(html);
            return formatEntry(ruleKey, name, text);
        } catch (ReflectiveOperationException e) {
            LOG.debug("Could not format rule description for " + ruleKey + ": " + e.getMessage());
            return null;
        }
    }

    private static String extractHtml(Object either) throws ReflectiveOperationException {
        Object isLeftObj = call(either, "isLeft");
        boolean isLeft = Boolean.TRUE.equals(isLeftObj);
        if (isLeft) {
            return (String) call(call(either, "getLeft"), "getHtmlContent");
        }
        return extractSplitDescriptionHtml(either);
    }

    private static String extractSplitDescriptionHtml(Object either) throws ReflectiveOperationException {
        Object split = call(either, "getRight");
        String intro = (String) call(split, "getIntroductionHtmlContent");
        List<?> tabs = (List<?>) call(split, "getTabs");
        StringBuilder sb = new StringBuilder();
        if (intro != null) sb.append(intro);
        if (tabs != null) {
            for (Object tab : tabs) {
                appendTab(sb, tab);
            }
        }
        return sb.toString();
    }

    private static void appendTab(StringBuilder sb, Object tab) throws ReflectiveOperationException {
        String title = (String) call(tab, "getTitle");
        Object content = call(tab, "getContent");
        if (content == null) return;
        Object isLeftObj = call(content, "isLeft");
        if (!Boolean.TRUE.equals(isLeftObj)) return;
        String sectionHtml = (String) call(call(content, "getLeft"), "getHtmlContent");
        if (title != null) sb.append("<h3>").append(title).append("</h3>");
        if (sectionHtml != null) sb.append(sectionHtml);
    }

    private static String formatEntry(String ruleKey, @Nullable String name, String text) {
        String header = (name != null && !name.isEmpty()) ? ruleKey + " · " + name : ruleKey;
        if (text.isEmpty()) return header;
        return header + "\n  " + text.replace("\n", "\n  ");
    }

    @Nullable
    private static Object call(@Nullable Object obj, String methodName) throws ReflectiveOperationException {
        if (obj == null) return null;
        Method m = obj.getClass().getMethod(methodName);
        return m.invoke(obj);
    }

    private static String htmlToText(@Nullable String html) {
        if (html == null || html.isEmpty()) return "";
        String text = html
            .replaceAll("(?i)<(h[1-6]|p|div|li|br|tr)[^>]*>", "\n")
            .replaceAll("<[^>]+>", "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replaceAll("[ \t]+", " ")
            .replaceAll("(?m)^[ \t]+", "")
            .replaceAll("\n{3,}", "\n\n")
            .trim();
        if (text.length() > MAX_DESCRIPTION_CHARS) {
            text = text.substring(0, MAX_DESCRIPTION_CHARS).trim() + "...";
        }
        return text;
    }
}
