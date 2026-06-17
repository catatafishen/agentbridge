package com.github.catatafishen.agentbridge.psi.cpp;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiWhiteSpace;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for navigating CLion Nova's lazy C/C++ PSI.
 * <p>
 * CLion Nova's lazy parser produces no {@link PsiNamedElement} instances for C/C++ declarations,
 * so the ordinary {@code PsiNamedElement} walks used for Java (and every other eager-PSI language)
 * find nothing. This is the shared root cause behind the {@code get_file_outline},
 * {@code search_symbols} and {@code get_symbol_info} CLion Nova bugs (issue #794). Rather than
 * re-deriving the node shapes in each tool, all CLion-Nova-specific C/C++ knowledge lives here.
 * <p>
 * CLion Nova represents declarations with two distinct shapes:
 * <ul>
 *   <li><b>Type declarations</b> (class/struct/enum/union) at file top level — a single
 *       {@code CppKeyword:*_KEYWORD} node wrapping a {@code DUMMY_NODE} (name) child and a
 *       {@code DUMMY_BLOCK} (body) child. Nodes without a {@code DUMMY_BLOCK} child are forward
 *       declarations and are skipped.</li>
 *   <li><b>Function definitions</b> and <b>namespace-nested declarations</b> — a flat
 *       {@code DUMMY_NODE} (signature / token stream) immediately followed by a sibling
 *       {@code DUMMY_BLOCK} (body). Using-aliases and forward declarations are also
 *       {@code DUMMY_NODE} but have no following {@code DUMMY_BLOCK}.</li>
 * </ul>
 * Namespaces appear either as a structured {@code CppKeyword:NAMESPACE_CPP_KEYWORD} node (body is
 * a {@code DUMMY_BLOCK} child) at top level, or as the flat {@code DUMMY_NODE}+{@code DUMMY_BLOCK}
 * sibling form when nested. Declarations inside {@code namespace X { ... }} are reached by
 * recursing into namespace bodies; anonymous namespaces have no emittable name but their contents
 * are still collected.
 * <p>
 * Both directions a tool needs — top-down enumeration ({@link #walkSymbols}) and bottom-up
 * ancestor classification ({@link #findEnclosingDeclaration}) — are backed by the single
 * {@link #classify(PsiElement, PsiElement)} primitive, so a node shape is defined exactly once.
 */
public final class CppNovaPsiSupport {

    private static final String NAMESPACE_KEYWORD_TYPE = "CppKeyword:NAMESPACE_CPP_KEYWORD";
    private static final String DUMMY_BLOCK = "DUMMY_BLOCK";
    private static final String DUMMY_NODE = "DUMMY_NODE";
    private static final String KIND_NAMESPACE = "namespace";

    private CppNovaPsiSupport() {
    }

    /**
     * A CLion Nova C/C++ declaration (type, function, or namespace), with its {@code kind} label,
     * extracted {@code name}, and the PSI {@code node} it was recognized from.
     */
    public record CppDeclaration(String kind, String name, PsiElement node) {
    }

    /**
     * Callback for {@link #walkSymbols}, invoked once per recognized declaration.
     */
    @FunctionalInterface
    public interface CppSymbolVisitor {
        void visit(String kind, String name, int line);
    }

    /**
     * Walks the CLion Nova C/C++ declarations of {@code psiFile} top-down, invoking {@code visitor}
     * for each recognized top-level and namespace-nested symbol. Used by the {@code get_file_outline}
     * and {@code search_symbols} fallbacks when the {@link PsiNamedElement} walk yields nothing.
     */
    public static void walkSymbols(PsiFile psiFile, Document document, CppSymbolVisitor visitor) {
        walkIn(significantChildren(psiFile), document, visitor);
    }

    /**
     * Resolves the CLion Nova C/C++ declaration whose PSI node encloses {@code elementAt}, by
     * walking up its ancestors and classifying each with {@link #classify}. Used by
     * {@code get_symbol_info} as a fallback when the ordinary {@link PsiNamedElement} ancestor walk
     * finds nothing. Unlike {@link #walkSymbols} (which only enumerates a file's top-level and
     * namespace-nested declarations), this matches a cursor positioned anywhere inside a type
     * declaration's body — but not inside a function body, since a function's {@code DUMMY_BLOCK}
     * body is a sibling of its {@code DUMMY_NODE} signature, not a descendant.
     *
     * @return the enclosing declaration, or {@code null} if no recognized shape encloses
     * {@code elementAt}
     */
    @Nullable
    public static CppDeclaration findEnclosingDeclaration(@Nullable PsiElement elementAt) {
        for (PsiElement current = elementAt; current != null && !(current instanceof PsiFile);
             current = current.getParent()) {
            CppDeclaration declaration = classify(current, nextSignificantSibling(current));
            if (declaration != null) return declaration;
        }
        return null;
    }

    /**
     * Classifies a single PSI node as the C/C++ declaration it represents (type, function, or
     * namespace), or {@code null} if {@code node} is not one of CLion Nova's recognized shapes.
     * {@code nextSibling} is the node's following significant sibling — required because a function
     * (or namespace-nested type) is a {@code DUMMY_NODE} signature immediately followed by a
     * sibling {@code DUMMY_BLOCK} body. This primitive backs both {@link #walkSymbols} and
     * {@link #findEnclosingDeclaration}, so the shape definitions exist exactly once.
     */
    @Nullable
    private static CppDeclaration classify(PsiElement node, @Nullable PsiElement nextSibling) {
        String elementType = elementType(node);
        String typeKind = cppKeywordKind(elementType);
        if (typeKind != null) {
            String name = extractTypeDeclarationName(node);
            return name != null ? new CppDeclaration(typeKind, name, node) : null;
        }
        if (NAMESPACE_KEYWORD_TYPE.equals(elementType)) {
            String name = namespaceName(node);
            return name != null ? new CppDeclaration(KIND_NAMESPACE, name, node) : null;
        }
        if (DUMMY_NODE.equals(elementType)) {
            return classifyDummyNode(node, nextSibling);
        }
        return null;
    }

    /**
     * Classifies a {@code DUMMY_NODE} — the flat token-stream form CLion Nova uses for functions
     * and for declarations nested inside a namespace body. A namespace is recognized by its first
     * child; a type or function requires a following {@code DUMMY_BLOCK} body (a {@code DUMMY_NODE}
     * with no following body is a forward declaration or using-alias and is skipped).
     */
    @Nullable
    private static CppDeclaration classifyDummyNode(PsiElement node, @Nullable PsiElement nextSibling) {
        if (NAMESPACE_KEYWORD_TYPE.equals(firstChildElementType(node))) {
            String name = firstIdentifier(node);
            return validName(name) ? new CppDeclaration(KIND_NAMESPACE, name, node) : null;
        }
        boolean hasBlock = nextSibling != null && DUMMY_BLOCK.equals(elementType(nextSibling));
        if (!hasBlock) return null;
        String typeKind = cppKeywordKind(firstChildElementType(node));
        if (typeKind != null) {
            String name = firstIdentifier(node);
            return validName(name) ? new CppDeclaration(typeKind, name, node) : null;
        }
        String name = extractFunctionName(node.getText());
        return name != null ? new CppDeclaration(ToolUtils.ELEMENT_TYPE_FUNCTION, name, node) : null;
    }

    /**
     * Recursively walks significant sibling nodes, emitting recognized declarations and descending
     * into namespace bodies. Namespace bodies are entered even when the namespace itself is
     * anonymous (no emittable name), so declarations nested inside it are still collected.
     */
    private static void walkIn(List<PsiElement> children, Document document, CppSymbolVisitor visitor) {
        for (int i = 0; i < children.size(); i++) {
            PsiElement child = children.get(i);
            PsiElement next = (i + 1 < children.size()) ? children.get(i + 1) : null;
            CppDeclaration declaration = classify(child, next);
            if (declaration != null) {
                visitor.visit(declaration.kind(), declaration.name(), lineOf(declaration.node(), document));
            }
            PsiElement body = namespaceBody(child, next);
            if (body != null) {
                walkIn(significantChildren(body), document, visitor);
            }
        }
    }

    /**
     * Returns the {@code DUMMY_BLOCK} body of {@code node} when it is a namespace node (so
     * {@link #walkIn} can recurse into it), or {@code null} when {@code node} is not a namespace.
     * The body is a child of the structured {@code CppKeyword:NAMESPACE_CPP_KEYWORD} node but a
     * following sibling of the flat {@code DUMMY_NODE} namespace form.
     */
    @Nullable
    private static PsiElement namespaceBody(PsiElement node, @Nullable PsiElement nextSibling) {
        String elementType = elementType(node);
        if (NAMESPACE_KEYWORD_TYPE.equals(elementType)) {
            return firstDummyBlockChild(node);
        }
        if (DUMMY_NODE.equals(elementType) && NAMESPACE_KEYWORD_TYPE.equals(firstChildElementType(node))) {
            return (nextSibling != null && DUMMY_BLOCK.equals(elementType(nextSibling))) ? nextSibling : null;
        }
        return null;
    }

    /**
     * The namespace name: the structured type-declaration name if present, else the first
     * {@code IDENTIFIER}, or {@code null} when neither yields a valid C++ identifier (anonymous
     * namespace).
     */
    @Nullable
    private static String namespaceName(PsiElement node) {
        String name = extractTypeDeclarationName(node);
        if (name == null) {
            name = firstIdentifier(node);
        }
        return validName(name) ? name : null;
    }

    @Nullable
    private static PsiElement nextSignificantSibling(PsiElement node) {
        PsiElement parent = node.getParent();
        if (parent == null) return null;
        List<PsiElement> siblings = significantChildren(parent);
        int idx = siblings.indexOf(node);
        return (idx >= 0 && idx + 1 < siblings.size()) ? siblings.get(idx + 1) : null;
    }

    private static String elementType(PsiElement node) {
        return node.getNode() != null ? node.getNode().getElementType().toString() : "";
    }

    private static int lineOf(PsiElement element, Document document) {
        return document.getLineNumber(element.getTextOffset()) + 1;
    }

    /**
     * Returns the element-type string of the first significant child of {@code node}, or {@code ""}.
     */
    private static String firstChildElementType(PsiElement node) {
        List<PsiElement> kids = significantChildren(node);
        return kids.isEmpty() ? "" : elementType(kids.getFirst());
    }

    /**
     * Returns the text of the first {@code IDENTIFIER} child of {@code node}, or {@code null}.
     */
    @Nullable
    private static String firstIdentifier(PsiElement node) {
        for (PsiElement child : significantChildren(node)) {
            if ("IDENTIFIER".equals(elementType(child))) {
                return child.getText();
            }
        }
        return null;
    }

    /**
     * Returns direct children of {@code parent}, skipping whitespace and comments.
     */
    private static List<PsiElement> significantChildren(PsiElement parent) {
        List<PsiElement> result = new ArrayList<>();
        for (PsiElement child : parent.getChildren()) {
            if (!(child instanceof PsiWhiteSpace) && !(child instanceof PsiComment)) {
                result.add(child);
            }
        }
        return result;
    }

    /**
     * Returns the first direct {@code DUMMY_BLOCK} child of {@code parent} (a namespace body), or
     * {@code null} if none.
     */
    @Nullable
    private static PsiElement firstDummyBlockChild(PsiElement parent) {
        for (PsiElement child : parent.getChildren()) {
            if (DUMMY_BLOCK.equals(elementType(child))) {
                return child;
            }
        }
        return null;
    }

    /**
     * Returns the type declaration name if {@code node} has a {@code DUMMY_NODE} name child and a
     * {@code DUMMY_BLOCK} body child (full definition); returns {@code null} for forward
     * declarations.
     */
    @Nullable
    private static String extractTypeDeclarationName(PsiElement node) {
        String name = null;
        boolean hasBody = false;
        for (PsiElement gc : node.getChildren()) {
            String gcEt = elementType(gc);
            if (DUMMY_NODE.equals(gcEt) && name == null) {
                name = firstToken(gc.getText().trim());
            } else if (DUMMY_BLOCK.equals(gcEt)) {
                hasBody = true;
            }
        }
        return (hasBody && name != null && !name.isEmpty() && isCppIdentifier(name)) ? name : null;
    }

    /**
     * Returns the text before the first whitespace character, i.e. the first token.
     */
    private static String firstToken(String s) {
        int ws = indexOfWhitespace(s);
        return ws > 0 ? s.substring(0, ws) : s;
    }

    @Nullable
    private static String extractFunctionName(String text) {
        int paren = text.indexOf('(');
        if (paren < 0) return null;
        // Take text before '(' and find the last identifier token (handles "ReturnType ClassName::method(")
        String prefix = text.substring(0, paren).trim();
        int end = prefix.length();
        int start = end;
        while (start > 0 && (Character.isLetterOrDigit(prefix.charAt(start - 1))
            || prefix.charAt(start - 1) == '_'
            || prefix.charAt(start - 1) == ':')) {
            start--;
        }
        String name = prefix.substring(start, end);
        if (name.startsWith("::")) name = name.substring(2);
        if (name.isEmpty() || name.chars().noneMatch(Character::isLetter)) return null;
        return name;
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private static boolean validName(@Nullable String name) {
        return name != null && isCppIdentifier(name);
    }

    private static boolean isCppIdentifier(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }

    /**
     * Maps a CLion Nova C++ element-type string to a display kind, or returns {@code null} if the
     * element type does not correspond to a type declaration keyword.
     */
    @Nullable
    private static String cppKeywordKind(String elementType) {
        return switch (elementType) {
            case "CppKeyword:CLASS_KEYWORD" -> "class";
            case "CppKeyword:STRUCT_KEYWORD" -> "struct";
            case "CppKeyword:UNION_KEYWORD" -> "union";
            case "CppKeyword:ENUM_KEYWORD" -> "enum";
            default -> null;
        };
    }
}
