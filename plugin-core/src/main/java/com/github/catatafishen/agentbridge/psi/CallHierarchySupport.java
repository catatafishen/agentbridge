package com.github.catatafishen.agentbridge.psi;

import com.github.catatafishen.agentbridge.psi.tools.FqnResolver;
import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Language-agnostic call hierarchy support.
 * <p>
 * Finds all callers of any named PSI element (method, function, procedure, etc.) by:
 * <ol>
 *   <li>Resolving the named element by name + file + line using {@link PsiNameIdentifierOwner}</li>
 *   <li>Searching for all references using the platform-level {@link ReferencesSearch}</li>
 * </ol>
 * Works across all JetBrains IDEs: IntelliJ IDEA (Java/Kotlin), PyCharm (Python),
 * GoLand (Go), WebStorm (JS/TS), CLion (C/C++), etc.
 */
public class CallHierarchySupport {

    private CallHierarchySupport() {
    }

    /**
     * Finds all callers of the named element at the given file/line, up to the given depth.
     * Must be called inside a read action.
     *
     * @param depth how many levels to traverse (1 = direct callers only)
     */
    public static String getCallHierarchy(@NotNull Project project, @NotNull String elementName,
                                          @NotNull String filePath, int line, int depth) {
        PsiNameIdentifierOwner element = resolveNamedElementAtLocation(project, filePath, line, elementName);
        if (element == null) {
            return "Error: Could not find '" + elementName + "' at " + filePath + ":" + line;
        }
        return buildCallHierarchyResult(element, project, depth);
    }

    /**
     * Finds all callers of a method resolved by FQN, up to the given depth.
     * Must be called inside a read action.
     *
     * @param fqn   fully-qualified name (e.g. "com.example.MyClass.myMethod")
     * @param depth how many levels to traverse (1 = direct callers only)
     */
    public static String getCallHierarchyByFqn(@NotNull Project project, @NotNull String fqn, int depth) {
        PsiElement resolved = FqnResolver.resolve(fqn, project);
        if (resolved == null) {
            return "Error: Could not resolve FQN '" + fqn + "'. "
                + "Ensure it is a valid fully-qualified Java/Kotlin class or member name. "
                + "Use 'file' + 'line' parameters for non-Java symbols.";
        }
        if (!(resolved instanceof PsiNameIdentifierOwner named)) {
            return "Error: Resolved '" + fqn + "' but it is not a named element that can have callers.";
        }
        return buildCallHierarchyResult(named, project, depth);
    }

    private static String buildCallHierarchyResult(@NotNull PsiNameIdentifierOwner element,
                                                   @NotNull Project project, int depth) {
        String basePath = project.getBasePath();
        StringBuilder sb = new StringBuilder();
        sb.append("Callers of ").append(formatElementSignature(element)).append(":\n");

        Set<PsiElement> visited = new HashSet<>();
        visited.add(element);
        collectCallers(sb, element, project, basePath, visited, 1, depth);

        if (sb.indexOf("\n  ") == -1) {
            return "No callers found for: " + formatElementSignature(element);
        }
        return sb.toString();
    }

    private static PsiNameIdentifierOwner resolveNamedElementAtLocation(@NotNull Project project,
                                                                        @NotNull String filePath,
                                                                        int line,
                                                                        @NotNull String elementName) {
        ToolUtils.LineContext ctx = ToolUtils.resolveLineContext(project, filePath, line);
        if (ctx == null) return null;
        PsiNameIdentifierOwner declaration = ToolUtils.findNamedElement(ctx, elementName);
        if (declaration != null) return declaration;
        // Fallback: the caller pointed at a usage rather than a declaration (e.g. a call site
        // for printf inside main()). Resolve the symbol on the line via the IDE's reference
        // contributors and use the target declaration. Language-agnostic — works for any IDE
        // whose PSI registers reference providers (Java, Kotlin, C/C++ in CLion, Python in
        // PyCharm, Go in GoLand, JS/TS in WebStorm, etc.).
        return resolveViaReference(ctx, elementName);
    }

    /**
     * Tries to resolve {@code elementName} via reference contributors at any occurrence of the
     * symbol text on the target line. Returns the target declaration as a
     * {@link PsiNameIdentifierOwner} if exactly that is what the reference resolves to,
     * or {@code null} otherwise.
     */
    @Nullable
    private static PsiNameIdentifierOwner resolveViaReference(@NotNull ToolUtils.LineContext ctx,
                                                              @NotNull String elementName) {
        if (elementName.isEmpty()) return null;
        PsiFile psiFile = ctx.psiFile();
        VirtualFile vf = psiFile.getVirtualFile();
        if (vf == null) return null;
        Document document = FileDocumentManager.getInstance().getDocument(vf);
        if (document == null) return null;

        String lineText = document.getText(new TextRange(ctx.lineStart(), ctx.lineEnd()));
        int searchFrom = 0;
        while (searchFrom <= lineText.length() - elementName.length()) {
            int symIdx = lineText.indexOf(elementName, searchFrom);
            if (symIdx < 0) break;
            if (isWholeIdentifierMatch(lineText, symIdx, elementName.length())) {
                int rawOffset = ctx.lineStart() + symIdx;
                int offset = TargetElementUtil.adjustOffset(psiFile, document, rawOffset);
                PsiNameIdentifierOwner target = resolveReferenceTarget(psiFile, offset);
                if (target != null) return target;
            }
            searchFrom = symIdx + elementName.length();
        }
        return null;
    }

    @Nullable
    private static PsiNameIdentifierOwner resolveReferenceTarget(@NotNull PsiFile psiFile, int offset) {
        PsiReference ref = psiFile.findReferenceAt(offset);
        if (ref != null) {
            PsiNameIdentifierOwner target = firstNamedTarget(ref);
            if (target != null) return target;
        }
        // Caret may be on a declaration name itself; getNamedElement walks to the enclosing
        // declaration. This matches the IDE's behaviour of Cmd+B on a declaration showing the
        // declaration itself.
        PsiElement elementAt = psiFile.findElementAt(offset);
        if (elementAt != null) {
            PsiElement named = TargetElementUtil.getNamedElement(elementAt);
            if (named instanceof PsiNameIdentifierOwner owner) return owner;
        }
        return null;
    }

    /**
     * Iterates every resolve candidate of {@code ref} and returns the first one that is a
     * {@link PsiNameIdentifierOwner}. For polyvariant references this is important: an earlier
     * candidate that is not a named declaration must not shadow a later one that is — otherwise
     * the call-hierarchy lookup spuriously reports "Could not find ...".
     */
    @Nullable
    private static PsiNameIdentifierOwner firstNamedTarget(@NotNull PsiReference ref) {
        if (ref instanceof PsiPolyVariantReference poly) {
            for (ResolveResult rr : poly.multiResolve(false)) {
                if (rr.getElement() instanceof PsiNameIdentifierOwner owner) return owner;
            }
            return null;
        }
        return ref.resolve() instanceof PsiNameIdentifierOwner owner ? owner : null;
    }

    private static boolean isWholeIdentifierMatch(@NotNull String text, int start, int length) {
        if (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) return false;
        int end = start + length;
        return end >= text.length() || !Character.isJavaIdentifierPart(text.charAt(end));
    }

    private static void collectCallers(@NotNull StringBuilder sb, @NotNull PsiElement target,
                                       @NotNull Project project, @Nullable String basePath,
                                       @NotNull Set<PsiElement> visited, int currentDepth, int maxDepth) {
        Collection<PsiReference> references = ReferencesSearch.search(
            target, GlobalSearchScope.projectScope(project)).findAll();

        String indent = "  ".repeat(currentDepth);
        for (PsiReference ref : references) {
            PsiElement element = ref.getElement();
            PsiNamedElement containingNamed = PsiTreeUtil.getParentOfType(element, PsiNamedElement.class);

            sb.append(indent);
            sb.append(containingNamed != null ? containingNamed.getName() : "(top level)");
            appendFileLocation(sb, element, basePath);
            sb.append("\n");

            if (currentDepth < maxDepth && containingNamed instanceof PsiNameIdentifierOwner named
                && visited.add(named)) {
                collectCallers(sb, named, project, basePath, visited, currentDepth + 1, maxDepth);
            }
        }
    }

    private static void appendFileLocation(@NotNull StringBuilder sb, @NotNull PsiElement element,
                                           @Nullable String basePath) {
        ToolUtils.appendFileLocation(sb, element, basePath);
    }

    private static @NotNull String formatElementSignature(@NotNull PsiNameIdentifierOwner element) {
        PsiElement parent = element.getParent();
        String name = element.getName();
        if (name == null) name = "(unknown)";
        if (parent instanceof PsiNamedElement namedParent && namedParent.getName() != null) {
            return namedParent.getName() + "." + name + "()";
        }
        return name + "()";
    }
}
