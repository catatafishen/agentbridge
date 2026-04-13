package com.github.catatafishen.agentbridge.psi.java;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiStatement;

/**
 * Java-specific suppress logic isolated from CodeQualityTools so the plugin
 * passes verification on non-Java IDEs (where java-psi classes are absent).
 */
public class CodeQualityJavaSupport {

    private static final String LABEL_SUPPRESS_INSPECTION = "Suppress Inspection";

    /**
     * Extracts leading whitespace (spaces and tabs) from a line of text.
     * Pure function — no IDE dependencies.
     */
    static String extractLeadingWhitespace(String lineText) {
        StringBuilder indent = new StringBuilder();
        for (char c : lineText.toCharArray()) {
            if (c == ' ' || c == '\t') indent.append(c);
            else break;
        }
        return indent.toString();
    }

    private CodeQualityJavaSupport() {
    }

    public static String suppress(Project project, PsiElement element, String inspectionId, Document document) {
        var target = findSuppressTarget(element);
        if (target == null) return "No suppressible element found at this location";
        return suppressJava(project, target, inspectionId, document);
    }

    private static PsiElement findSuppressTarget(PsiElement element) {
        var current = element;
        while (current != null) {
            if (current instanceof PsiMethod ||
                current instanceof PsiField ||
                current instanceof PsiClass ||
                current instanceof PsiLocalVariable) {
                return current;
            }
            if (current instanceof PsiStatement) {
                return current;
            }
            current = current.getParent();
        }
        return element;
    }

    private static String suppressJava(Project project, PsiElement target, String inspectionId,
                                       Document document) {
        int targetOffset = target.getTextRange().getStartOffset();
        int targetLine = document.getLineNumber(targetOffset);
        int lineStart = document.getLineStartOffset(targetLine);

        String lineText = document.getText(
            new TextRange(lineStart, document.getLineEndOffset(targetLine)));
        String indent = extractLeadingWhitespace(lineText);

        if (target instanceof PsiModifierListOwner modListOwner) {
            var modList = modListOwner.getModifierList();
            if (modList != null) {
                var existing = modList.findAnnotation("java.lang.SuppressWarnings");
                if (existing != null) {
                    return addToExistingSuppressWarnings(project, existing, inspectionId, document);
                }
            }
        }

        String annotation = indent + "@SuppressWarnings(\"" + inspectionId + "\")\n";
        WriteAction.run(() ->
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                document.insertString(lineStart, annotation);
                PsiDocumentManager.getInstance(project).commitDocument(document);
            }, LABEL_SUPPRESS_INSPECTION, null)
        );

        return "Added @SuppressWarnings(\"" + inspectionId + "\") at line " + (targetLine + 1);
    }

    private static String addToExistingSuppressWarnings(Project project, PsiAnnotation annotation,
                                                        String inspectionId, Document document) {
        String text = annotation.getText();
        if (text.contains(inspectionId)) {
            return "Inspection '" + inspectionId + "' is already suppressed at this location";
        }

        WriteAction.run(() ->
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                var value = annotation.findAttributeValue("value");
                if (value != null) {
                    if (value instanceof PsiArrayInitializerMemberValue) {
                        int endBrace = value.getTextRange().getEndOffset() - 1;
                        document.insertString(endBrace, ", \"" + inspectionId + "\"");
                    } else {
                        var range = value.getTextRange();
                        String existing = document.getText(range);
                        document.replaceString(range.getStartOffset(), range.getEndOffset(),
                            "{" + existing + ", \"" + inspectionId + "\"}");
                    }
                    PsiDocumentManager.getInstance(project).commitDocument(document);
                }
            }, LABEL_SUPPRESS_INSPECTION, null)
        );

        return "Added '" + inspectionId + "' to existing @SuppressWarnings annotation";
    }
}
