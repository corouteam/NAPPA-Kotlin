package nl.vu.cs.s2group.nappa.plugin.util;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtModifierList;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

/**
 * An class containing common utility methods to simplify the instrumentation actions
 * <p>
 * For utility methods to manipulate the PSI Tree, check the utility classes provided by IntelliJ at
 * {@link com.intellij.psi.util}, in particular {@link com.intellij.psi.util.PsiTreeUtil PsiTreeUtil}
 * and {@link com.intellij.psi.util.PsiUtil PsiUtil}.
 */
public final class InstrumentUtil {
    private static final String NAPPA_PACKAGE_NAME = "nl.vu.cs.s2group.nappa";
    private static final String NAPPA_SAMPLE_APP_PACKAGE_NAME = "nl.vu.cs.s2group.nappa.sample.app";

    private InstrumentUtil() {
        throw new IllegalStateException("InstrumentUtil is a utility class and should be instantiated!");
    }

    /**
     * Scan the project directory for all source files to search for all Java source files
     *
     * @param project An object representing an IntelliJ project.
     * @return A list of all Java source files in the project
     */
    public static @NotNull
    List<PsiFile> getAllJavaFilesInProjectAsPsi(Project project) {
        List<PsiFile> psiFiles = new LinkedList<>();
        String[] fileNames = FilenameIndex.getAllFilenames(project);

        fileNames = Arrays.stream(fileNames)
                .filter(fileName -> fileName.contains(".java"))
                .toArray(String[]::new);

        for (String fileName : fileNames) {
            PsiFile[] psiJavaFiles = FilenameIndex.getFilesByName(project, fileName, GlobalSearchScope.projectScope(project));
            // Remove the files from the NAPPA library from the list to process
            psiJavaFiles = Arrays.stream(psiJavaFiles)
                    .filter(psiJavaFile -> {
                        String packageName = ((PsiJavaFile) psiJavaFile).getPackageName();
                        return !packageName.contains(NAPPA_PACKAGE_NAME) || packageName.contains(NAPPA_SAMPLE_APP_PACKAGE_NAME);
                    })
                    .toArray(PsiFile[]::new);

            psiFiles.addAll(Arrays.asList(psiJavaFiles));
        }

        return psiFiles;
    }

    /**
     * @param project    An object representing an IntelliJ project.
     * @param psiElement The reference element to add the library import to
     */
    public static void addLibraryImport(Project project, @NotNull PsiElement psiElement) {
        PsiJavaFile psiJavaFile = (PsiJavaFile) getAncestorPsiElementFromElement(psiElement, PsiJavaFile.class);

        if (psiJavaFile == null) return;
        PsiImportList importList = psiJavaFile.getImportList();
        if (importList == null || importList.findOnDemandImportStatement(NAPPA_PACKAGE_NAME) != null) return;
        WriteCommandAction.runWriteCommandAction(project, () -> {
            importList.add(PsiElementFactory.getInstance(project).createImportStatementOnDemand(NAPPA_PACKAGE_NAME));
        });
    }

    /**
     * Iterate the Java files structure until reaching the statement level (e.g. {@code String a = "text"}).
     * Upon reaching a statement, invokes the {@code callback} function passing the statement as parameter
     *
     * @param psiFiles    A list of all Java files within a project
     * @param fileFilter  Skip all files that does not contain any of the strings in the provided array
     * @param classFilter Skip all classes that does not contain any of the strings in the provided array
     * @param callback    A callback function invoked for each statement found in all files
     */
    public static void runScanOnJavaFile(@NotNull List<PsiFile> psiFiles, String[] fileFilter, String[] classFilter, Consumer<PsiElement> callback) {
        for (PsiFile psiFile : psiFiles) {
            if (Arrays.stream(fileFilter).noneMatch(psiFile.getText()::contains)) continue;
            PsiClass[] psiClasses = ((PsiJavaFile) psiFile).getClasses();
            for (PsiClass psiClass : psiClasses) {
                runFullScanOnJavaClass(psiClass, classFilter, callback);
            }
        }
    }

    /**
     * Auxiliary method for {@link InstrumentUtil#runScanOnJavaFile} to be able to scan inner classes
     *
     * @param psiClass    A Java class
     * @param classFilter Skip all classes that does not contain any of the strings in the provided array
     * @param callback    A callback function invoked for each statement found in all files
     */
    private static void runFullScanOnJavaClass(@NotNull PsiClass psiClass, String[] classFilter, Consumer<PsiElement> callback) {
        if (Arrays.stream(classFilter).noneMatch(psiClass.getText()::contains)) return;

        PsiMethod[] psiMethods = psiClass.getMethods();
        for (PsiMethod psiMethod : psiMethods) {
            if (psiMethod.getBody() == null) continue;
            PsiStatement[] psiStatements = psiMethod.getBody().getStatements();
            for (PsiStatement statement : psiStatements) {
                callback.accept(statement);
            }
        }

        PsiClassInitializer[] psiClassInitializers = psiClass.getInitializers();
        for (PsiClassInitializer psiClassInitializer : psiClassInitializers) {
            for (PsiStatement statement : psiClassInitializer.getBody().getStatements()) {
                callback.accept(statement);
            }
        }

        PsiField[] psiFields = psiClass.getAllFields();
        for (PsiField psiField : psiFields) {
            callback.accept(psiField);
        }

        PsiClass[] psiClasses = psiClass.getInnerClasses();
        for (PsiClass innerPsiClass : psiClasses) {
            runFullScanOnJavaClass(innerPsiClass, classFilter, callback);
        }
    }

    /**
     * Traverse the Psi tree from the {@code element} in direction to the root until finding a Psi element representing
     * the Psi element class provided in {@code classType}
     * <p>
     * This method is similar to {@link com.intellij.psi.util.PsiTreeUtil#findFirstParent(PsiElement, Condition)},
     * both return the same object, however, this method executes faster.
     *
     * @param element   The reference element to find a parent Psi element from
     * @param classType The class of the desired Psi element
     * @return The Psi representation of the first {@code element} of the type {@code classType}. {@code null} if no Java class is found.
     */
    public static @Nullable
    PsiElement getAncestorPsiElementFromElement(PsiElement element, Class<? extends PsiElement> classType) {
        PsiElement el = element;
        while (true) {
            if (el == null || el instanceof PsiDirectory) return null;
            if (classType.isInstance(el)) return el;
            el = el.getParent();
        }
    }

    /**
     * Verifies if a class is the main public class
     *
     * @param psiClass Represents a Java class or interface.
     * @return {@code True} if it is the main class or {@code False} otherwise
     */
    public static boolean isMainPublicClass(@NotNull PsiClass psiClass) {
        PsiModifierList classModifier = psiClass.getModifierList();
        return classModifier != null && classModifier.getText().contains("public");
    }

    /**
     * Verifies if a class is the main public class
     *
     * @param ktClass Represents a Java class or interface.
     * @return {@code True} if it is the main class or {@code False} otherwise
     */
    public static boolean isMainPublicClassKt(@NotNull KtClass ktClass) {
        KtModifierList classModifier = ktClass.getModifierList();
        return classModifier == null || classModifier.getText().contains("public");
    }

    /**
     * Verifies if there is a variable with the name {@code variableName} in an ancestor with shared context
     * where the {@code referenceElement} is located in the PSI tree. If a variable is found, then append
     * a number to the variable name to avoid creating a variable with the same name.
     *
     * @param referenceElement Represents the {@link PsiElement} used as reference in the PSI tree
     * @param variableName     Represents the name of the variable to search for
     * @return A unique name for the new variable in the reference context
     */
    public static String getUniqueVariableName(PsiElement referenceElement, String variableName) {
        int number = 0;
        String numberAsStr = "";
        PsiElement elementToSearch = PsiTreeUtil.getParentOfType(referenceElement, PsiMethod.class);
        if (elementToSearch == null)
            elementToSearch = PsiTreeUtil.getParentOfType(referenceElement, PsiCodeBlock.class);
        if (elementToSearch == null) return variableName;
        while (true) {
            String[] variableToSearch = new String[]{
                    " " + variableName + numberAsStr + "=",
                    " " + variableName + numberAsStr + " ="
            };
            if (Arrays.stream(variableToSearch).noneMatch(elementToSearch.getText()::contains))
                return variableName + numberAsStr;
            number++;
            numberAsStr = Integer.toString(number);
        }
    }
}
