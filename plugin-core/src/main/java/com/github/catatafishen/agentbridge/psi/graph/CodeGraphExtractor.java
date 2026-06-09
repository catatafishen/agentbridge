package com.github.catatafishen.agentbridge.psi.graph;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks a {@link PsiFile} and produces graph nodes (named declarations) and edges
 * (resolved references). Language-agnostic — uses platform-level PSI interfaces
 * ({@link PsiNameIdentifierOwner}, {@link PsiReference}) so the same code works
 * for Java, Kotlin, Python, and any other language with a PSI implementation.
 */
public final class CodeGraphExtractor {

    private static final Logger LOG = Logger.getInstance(CodeGraphExtractor.class);

    /**
     * Result of extracting a single file.
     */
    public record FileExtraction(@NotNull String relativePath,
                                 @NotNull String contentHash,
                                 @NotNull List<NodeData> nodes,
                                 @NotNull List<EdgeData> edges) {
    }

    private final Project project;

    public CodeGraphExtractor(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Extract nodes/edges from a single PSI file. Must be called inside a read action.
     */
    @NotNull
    public FileExtraction extract(@NotNull PsiFile file) {
        VirtualFile vf = file.getVirtualFile();
        String relPath = relativePath(vf);
        String hash = sha256(file.getText());
        List<NodeData> nodes = new ArrayList<>();
        List<EdgeData> edges = new ArrayList<>();
        String language = file.getLanguage().getID();

        // File-level node — anchors edges to/from this file even when no declarations exist
        String fileId = "file:" + relPath;
        nodes.add(new NodeData(
            fileId,
            file.getName(),
            "file",
            null,
            relPath,
            0,
            language));

        // Declarations: anything with a name identifier — class, method, function, field
        Collection<PsiNameIdentifierOwner> declared =
            PsiTreeUtil.findChildrenOfType(file, PsiNameIdentifierOwner.class);
        Set<String> seenIds = new HashSet<>();
        for (PsiNameIdentifierOwner owner : declared) {
            String name = owner.getName();
            if (name == null || name.isEmpty()) continue;
            String fqn = computeFqn(owner, name);
            String id = "sym:" + relPath + "#" + fqn;
            if (seenIds.add(id)) {
                int line = lineOf(file, owner);
                nodes.add(new NodeData(id, name, kindOf(owner), fqn, relPath, line, language));
                edges.add(new EdgeData(fileId, id, "contains", relPath, line));
            }
        }

        // References: resolve anything resolvable, capture cross-file edges
        collectCrossFileEdges(file, relPath, fileId, edges);

        return new FileExtraction(relPath, hash, nodes, edges);
    }

    private void collectCrossFileEdges(@NotNull PsiFile file, @NotNull String relPath,
                                       @NotNull String fileId, @NotNull List<EdgeData> edges) {
        ProjectFileIndex idx = ProjectFileIndex.getInstance(project);
        Collection<PsiReference> refs = collectRefs(file);
        for (PsiReference ref : refs) {
            VirtualFile targetVf = resolveTargetVf(ref);
            if (targetVf == null || !idx.isInProject(targetVf)) continue;
            String targetPath = relativePath(targetVf);
            if (!targetPath.equals(relPath)) {
                String targetId = "file:" + targetPath;
                int srcLine = lineOf(file, ref.getElement());
                edges.add(new EdgeData(fileId, targetId, "uses", relPath, srcLine));
            }
        }
    }

    @Nullable
    private static VirtualFile resolveTargetVf(@NotNull PsiReference ref) {
        PsiElement target;
        try {
            target = ref.resolve();
        } catch (Exception e) {
            return null;
        }
        if (target == null) return null;
        PsiFile targetFile = target.getContainingFile();
        if (targetFile == null) return null;
        return targetFile.getVirtualFile();
    }

    @NotNull
    private Collection<PsiReference> collectRefs(@NotNull PsiFile file) {
        List<PsiReference> result = new ArrayList<>();
        file.accept(new com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                Collections.addAll(result, element.getReferences());
                super.visitElement(element);
            }
        });
        return result;
    }

    @NotNull
    private String relativePath(@Nullable VirtualFile vf) {
        if (vf == null) return "<unknown>";
        String basePath = project.getBasePath();
        VirtualFile base = basePath != null
            ? com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(basePath) : null;
        if (base == null) return vf.getPath();
        String baseStr = base.getPath();
        String full = vf.getPath();
        if (full.startsWith(baseStr + "/")) {
            return full.substring(baseStr.length() + 1);
        }
        return full;
    }

    @NotNull
    private static String kindOf(@NotNull PsiNameIdentifierOwner owner) {
        String simple = owner.getClass().getSimpleName();
        // Heuristic — most PSI classes follow PsiClass / PsiMethod / KtClass / KtFunction patterns
        String lower = simple.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("interface")) return "interface";
        if (lower.contains("enum")) return "enum";
        if (lower.contains("class")) return "class";
        if (lower.contains("method") || lower.contains("function")) return "method";
        if (lower.contains("field") || lower.contains("property")) return "field";
        if (lower.contains("variable")) return "variable";
        return "symbol";
    }

    @Nullable
    private static String computeFqn(@NotNull PsiNameIdentifierOwner owner, @NotNull String name) {
        // Walk up parents, collecting any named owners — yields a dot-path
        StringBuilder sb = new StringBuilder(name);
        PsiElement parent = owner.getParent();
        while (parent != null) {
            if (parent instanceof PsiNameIdentifierOwner namedParent) {
                String pname = namedParent.getName();
                if (pname != null && !pname.isEmpty()) {
                    sb.insert(0, pname + ".");
                }
            }
            parent = parent.getParent();
        }
        return sb.toString();
    }

    private static int lineOf(@NotNull PsiFile file, @Nullable PsiElement element) {
        if (element == null) return 0;
        com.intellij.openapi.editor.Document doc =
            com.intellij.psi.PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (doc == null) return 0;
        int offset = element.getTextOffset();
        if (offset < 0 || offset > doc.getTextLength()) return 0;
        return doc.getLineNumber(offset) + 1;
    }

    @NotNull
    private static String sha256(@NotNull String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            LOG.warn("Failed to compute content hash", e);
            return Integer.toHexString(content.hashCode());
        }
    }
}
