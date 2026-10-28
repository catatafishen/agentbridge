package com.github.catatafishen.agentbridge.psi.tools.editing;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link EditingTool#declarationEndOffset} for languages (Python) where the named element of an assignment is only
 * the target name, not the whole statement. The Python plugin is not available in the test IDE, so the PSI
 * shape is imitated with mocks whose class names follow Python's ({@code PyTargetExpressionImpl}, ...).
 */
class DeclarationEndOffsetTest {

    // Names mimic the Python PSI classes; declared as abstract so Mockito can mock them.
    abstract static class PyTargetExpressionImpl implements PsiElement {
    }

    abstract static class PyAssignmentStatementImpl implements PsiElement {
    }

    abstract static class PyCallExpressionImpl implements PsiElement {
    }

    private static <T extends PsiElement> T node(Class<T> type, int start, int end, PsiElement parent) {
        T element = Mockito.mock(type);
        Mockito.when(element.getTextRange()).thenReturn(new TextRange(start, end));
        Mockito.when(element.getParent()).thenReturn(parent);
        return element;
    }

    @Test
    void assignmentTargetExpandsToTheWholeMultiLineStatement() {
        PsiFile file = Mockito.mock(PsiFile.class);
        Mockito.when(file.getTextRange()).thenReturn(new TextRange(0, 500));
        // MOCK_USER = User(\n ... \n)   ->  statement spans 100..300, the name only 100..109
        PyAssignmentStatementImpl statement = node(PyAssignmentStatementImpl.class, 100, 300, file);
        PyTargetExpressionImpl target = node(PyTargetExpressionImpl.class, 100, 109, statement);

        assertEquals(300, EditingTool.declarationEndOffset(target));
    }

    @Test
    void anElementThatIsNotAnAssignmentTargetKeepsItsOwnRange() {
        PsiFile file = Mockito.mock(PsiFile.class);
        PyCallExpressionImpl call = node(PyCallExpressionImpl.class, 10, 40, file);

        assertEquals(40, EditingTool.declarationEndOffset(call));
    }

    @Test
    void anAssignmentThatDoesNotStartAtTheNameIsNotUsed() {
        // a = b = 1 : the target of "b" sits in the middle of the statement, so it must not claim the whole statement
        PsiFile file = Mockito.mock(PsiFile.class);
        PyAssignmentStatementImpl statement = node(PyAssignmentStatementImpl.class, 0, 20, file);
        PyTargetExpressionImpl second = node(PyTargetExpressionImpl.class, 4, 5, statement);

        assertEquals(5, EditingTool.declarationEndOffset(second));
    }

    @Test
    void aTopLevelElementWithoutParentKeepsItsOwnRange() {
        PyTargetExpressionImpl target = node(PyTargetExpressionImpl.class, 3, 8, null);

        assertEquals(8, EditingTool.declarationEndOffset(target));
    }
}
