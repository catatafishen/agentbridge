package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.jps.model.java.JavaResourceRootType;
import org.jetbrains.jps.model.java.JavaSourceRootType;
import org.jetbrains.jps.model.java.JpsJavaExtensionService;

import java.util.HashMap;
import java.util.Map;

/**
 * Checks {@link ProjectFileScanner} against real directory markings: scan() and searchScope()
 * must select the same files, and each marked root must map to the matching category.
 */
public class ProjectFileScannerTest extends BasePlatformTestCase {

    private VirtualFile srcFile;
    private VirtualFile testFile;
    private VirtualFile resFile;
    private VirtualFile testResFile;
    private VirtualFile genFile;
    private VirtualFile excludedFile;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        srcFile = myFixture.addFileToProject("marked/main/Main.txt", "x").getVirtualFile();
        testFile = myFixture.addFileToProject("marked/test/MainTest.txt", "x").getVirtualFile();
        resFile = myFixture.addFileToProject("marked/res/app.properties", "x").getVirtualFile();
        testResFile = myFixture.addFileToProject("marked/testres/t.properties", "x").getVirtualFile();
        genFile = myFixture.addFileToProject("marked/gen/Gen.txt", "x").getVirtualFile();
        excludedFile = myFixture.addFileToProject("marked/out/Built.txt", "x").getVirtualFile();

        var module = getModule();
        PsiTestUtil.addSourceRoot(module, srcFile.getParent(), JavaSourceRootType.SOURCE);
        PsiTestUtil.addSourceRoot(module, testFile.getParent(), JavaSourceRootType.TEST_SOURCE);
        PsiTestUtil.addSourceRoot(module, resFile.getParent(), JavaResourceRootType.RESOURCE);
        PsiTestUtil.addSourceRoot(module, testResFile.getParent(), JavaResourceRootType.TEST_RESOURCE);
        PsiTestUtil.addSourceRoot(module, genFile.getParent(), JavaSourceRootType.SOURCE,
            JpsJavaExtensionService.getInstance().createSourceRootProperties("", true));
        PsiTestUtil.addExcludedRoot(module, excludedFile.getParent());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            var module = getModule();
            for (VirtualFile f : new VirtualFile[]{srcFile, testFile, resFile, testResFile, genFile}) {
                PsiTestUtil.removeSourceRoot(module, f.getParent());
            }
            PsiTestUtil.removeExcludedRoot(module, excludedFile.getParent());
        } finally {
            super.tearDown();
        }
    }

    private static FileCategory.Selection scope(String... ids) {
        JsonObject args = new JsonObject();
        JsonArray a = new JsonArray();
        for (String id : ids) a.add(id);
        args.add(FileCategory.PARAM_SCOPE, a);
        return FileCategory.parseScope(args);
    }

    private Map<VirtualFile, FileCategory> scanned(FileCategory.Selection s) {
        Map<VirtualFile, FileCategory> out = new HashMap<>();
        ReadAction.run(() -> ProjectFileScanner.scan(getProject(), s, (vf, c) -> {
            out.put(vf, c);
            return true;
        }));
        return out;
    }

    public void testEachMarkedRootMapsToItsCategory() {
        Map<VirtualFile, FileCategory> all = scanned(scope("all"));
        assertEquals(FileCategory.SOURCES, all.get(srcFile));
        assertEquals(FileCategory.TEST_SOURCES, all.get(testFile));
        assertEquals(FileCategory.RESOURCES, all.get(resFile));
        assertEquals(FileCategory.TEST_RESOURCES, all.get(testResFile));
        assertEquals(FileCategory.GENERATED_SOURCES, all.get(genFile));
    }

    public void testExcludedRootIsNeverScannedEvenWithAll() {
        assertFalse(scanned(scope("all")).containsKey(excludedFile));
    }

    public void testSingleCategorySelectsOnlyThatRoot() {
        Map<VirtualFile, FileCategory> tests = scanned(scope("test_sources"));
        assertTrue(tests.containsKey(testFile));
        assertFalse(tests.containsKey(srcFile));
        assertFalse(tests.containsKey(genFile));
    }

    public void testSearchScopeAgreesWithScan() {
        for (String id : new String[]{"sources", "test_sources", "resources", "test_resources", "generated_sources"}) {
            FileCategory.Selection s = scope(id);
            Map<VirtualFile, FileCategory> scanned = scanned(s);
            GlobalSearchScope gs = ReadAction.compute(() -> ProjectFileScanner.searchScope(getProject(), s));
            for (VirtualFile f : new VirtualFile[]{srcFile, testFile, resFile, testResFile, genFile}) {
                boolean inScope = ReadAction.compute(() -> gs.contains(f));
                assertEquals(id + " / " + f.getPath(), scanned.containsKey(f), inScope);
            }
        }
    }

    public void testLibrariesOnlyScopeContainsNoProjectFiles() {
        GlobalSearchScope gs = ReadAction.compute(
            () -> ProjectFileScanner.searchScope(getProject(), scope("libraries")));
        assertFalse(ReadAction.compute(() -> gs.contains(srcFile)));
        assertTrue(scanned(scope("libraries")).isEmpty());
    }

    public void testExcludedRootIsReportedByName() {
        var roots = ReadAction.compute(() -> ProjectFileScanner.excludedRoots(getProject()));
        assertTrue(roots.contains(excludedFile.getParent()));
    }
}
