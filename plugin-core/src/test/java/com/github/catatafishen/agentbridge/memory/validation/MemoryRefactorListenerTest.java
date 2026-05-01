package com.github.catatafishen.agentbridge.memory.validation;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNamedElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MemoryRefactorListener#extractFqn(PsiElement)}.
 *
 * <p>NOTE: {@link ExtractFqnTests#extractsPathFromPsiFile()} uses {@code mock(VirtualFile.class)}.
 * When run alone this test passes. In some multi-class test runners (e.g., JBR 25 inline-mock
 * sessions) VirtualFile may be naturally loaded before Mockito can instrument it, causing the
 * test to fail. Run this class in isolation to verify it passes.
 */
class MemoryRefactorListenerTest {

    @Nested
    @DisplayName("extractFqn")
    class ExtractFqnTests {

        @Test
        @DisplayName("extracts FQN from PsiClass")
        void extractsFqnFromPsiClass() {
            PsiClass psiClass = mock(PsiClass.class);
            when(psiClass.getQualifiedName()).thenReturn("com.example.UserService");

            assertEquals("com.example.UserService", MemoryRefactorListener.extractFqn(psiClass));
        }

        @Test
        @DisplayName("returns null for PsiClass without qualified name")
        void returnsNullForClassWithoutQualifiedName() {
            PsiClass psiClass = mock(PsiClass.class);
            when(psiClass.getQualifiedName()).thenReturn(null);

            assertNull(MemoryRefactorListener.extractFqn(psiClass));
        }

        @Test
        @DisplayName("extracts FQN from PsiMethod with containing class")
        void extractsFqnFromPsiMethod() {
            PsiClass containingClass = mock(PsiClass.class);
            when(containingClass.getQualifiedName()).thenReturn("com.example.UserService");

            PsiMethod psiMethod = mock(PsiMethod.class);
            when(psiMethod.getName()).thenReturn("authenticate");
            when(psiMethod.getContainingClass()).thenReturn(containingClass);

            assertEquals("com.example.UserService.authenticate",
                MemoryRefactorListener.extractFqn(psiMethod));
        }

        @Test
        @DisplayName("returns null for PsiMethod without containing class")
        void returnsNullForMethodWithoutClass() {
            PsiMethod psiMethod = mock(PsiMethod.class);
            when(psiMethod.getContainingClass()).thenReturn(null);

            assertNull(MemoryRefactorListener.extractFqn(psiMethod));
        }

        @Test
        @DisplayName("returns null for PsiMethod whose class has no FQN")
        void returnsNullForMethodWhoseClassHasNoFqn() {
            PsiClass containingClass = mock(PsiClass.class);
            when(containingClass.getQualifiedName()).thenReturn(null);

            PsiMethod psiMethod = mock(PsiMethod.class);
            when(psiMethod.getContainingClass()).thenReturn(containingClass);

            assertNull(MemoryRefactorListener.extractFqn(psiMethod));
        }

        @Test
        @DisplayName("extracts path from PsiFile")
        void extractsPathFromPsiFile() {
            VirtualFile vFile = mock(VirtualFile.class);
            when(vFile.getPath()).thenReturn("/src/main/java/UserService.java");

            PsiFile psiFile = mock(PsiFile.class);
            when(psiFile.getVirtualFile()).thenReturn(vFile);

            assertEquals("/src/main/java/UserService.java",
                MemoryRefactorListener.extractFqn(psiFile));
        }

        @Test
        @DisplayName("returns null for PsiFile without virtual file")
        void returnsNullForPsiFileWithoutVirtualFile() {
            PsiFile psiFile = mock(PsiFile.class);
            when(psiFile.getVirtualFile()).thenReturn(null);

            assertNull(MemoryRefactorListener.extractFqn(psiFile));
        }

        @Test
        @DisplayName("extracts name from PsiNamedElement")
        void extractsNameFromPsiNamedElement() {
            PsiNamedElement named = mock(PsiNamedElement.class);
            when(named.getName()).thenReturn("myVariable");

            assertEquals("myVariable", MemoryRefactorListener.extractFqn(named));
        }

        @Test
        @DisplayName("returns null for unknown PsiElement type")
        void returnsNullForUnknownElement() {
            PsiElement element = mock(PsiElement.class);
            assertNull(MemoryRefactorListener.extractFqn(element));
        }
    }

    // ── lifecycle & guard-clause tests ────────────────────────────────────

    @Nested
    @DisplayName("Lifecycle — methods that do not reference RefactoringEventData")
    class LifecycleTests {

        @Test
        @DisplayName("undoRefactoring does not throw")
        void undoRefactoring_doesNotThrow() {
            Project project = mock(Project.class);
            MemoryRefactorListener listener = new MemoryRefactorListener(project);
            // undoRefactoring takes only a String — no RefactoringEventData class loading
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                listener.undoRefactoring("refactoring.rename"));
        }

        @Test
        @DisplayName("dispose does not throw")
        void dispose_doesNotThrow() {
            Project project = mock(Project.class);
            MemoryRefactorListener listener = new MemoryRefactorListener(project);
            // dispose() sets pendingOldFqn = null — no side effects
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(listener::dispose);
        }
    }
}
