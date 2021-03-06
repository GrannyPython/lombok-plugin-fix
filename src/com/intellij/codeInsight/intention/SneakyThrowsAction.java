package com.intellij.codeInsight.intention;

import com.intellij.codeInsight.ExceptionUtil;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.util.RefactoringUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

@NonNls
public class SneakyThrowsAction extends PsiElementBaseIntentionAction implements IntentionAction {

    private static final String LOMBOK_SNEAKY_THROWS_PACKAGE = "lombok.SneakyThrows";
    private static final String SNEAKY_THROWS_NAME = "SneakyThrows";
    private static final String LOMBOK_SHORT_NAME = "lombok";
    private static final String MARK_SNEAKY_THROWS = "Mark sneaky throws";

    @NotNull
    public String getText() {
        return MARK_SNEAKY_THROWS;
    }


    @NotNull
    public String getFamilyName() {
        return getText();
    }

    public boolean isAvailable(@NotNull Project project, Editor editor, @Nullable PsiElement element) {

        PsiMethod psiMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        Library[] libraries = LibraryTablesRegistrar.getInstance().getLibraryTable(project).getLibraries();
        boolean lombokExists = libraries.length != 0 && Objects.requireNonNull(libraries[0].getName()).contains(LOMBOK_SHORT_NAME);

        if (psiMethod != null && lombokExists) {
            PsiAnnotation[] annotations = psiMethod.getAnnotations();
            for (PsiAnnotation annotation : annotations) {
                boolean sneakyThrowsExist = annotation.getQualifiedName().endsWith(SNEAKY_THROWS_NAME);
                if (sneakyThrowsExist) {
                    return false;
                }
            }
            List<? super PsiClassType> unhandledExceptions = collectExceptions(element);
            return !unhandledExceptions.isEmpty();
        }

        return false;

    }

    @Nullable
    private List<? super PsiClassType> collectExceptions(PsiElement myWrongElement) {
        PsiElement targetElement = null;
        PsiMethod targetMethod = null;
        List<? super PsiClassType> unhandled = new ArrayList<>();

        final PsiElement psiElement;
        if (myWrongElement instanceof PsiMethodReferenceExpression) {
            psiElement = myWrongElement;
        } else {
            PsiElement parentStatement = RefactoringUtil.getParentStatement(myWrongElement, false);
            if (parentStatement instanceof PsiDeclarationStatement) {
                PsiElement[] declaredElements = ((PsiDeclarationStatement) parentStatement).getDeclaredElements();
                if (declaredElements.length > 0 && declaredElements[0] instanceof PsiClass) {
                    return null;
                }
            }

            psiElement = PsiTreeUtil.getParentOfType(myWrongElement, PsiFunctionalExpression.class, PsiMethod.class);
        }
        if (psiElement instanceof PsiFunctionalExpression) {
            targetMethod = LambdaUtil.getFunctionalInterfaceMethod(psiElement);
            targetElement = psiElement instanceof PsiLambdaExpression ? ((PsiLambdaExpression) psiElement).getBody() : psiElement;
        } else if (psiElement instanceof PsiMethod) {
            targetMethod = (PsiMethod) psiElement;
            targetElement = psiElement;
        }

        if (targetElement == null || targetMethod == null || !targetMethod.getThrowsList().isPhysical()) return null;
        List<PsiClassType> exceptions = getUnhandledExceptions(myWrongElement, targetElement, targetMethod);
        if (exceptions == null || exceptions.isEmpty()) return exceptions;
        unhandled.addAll(exceptions);
        return exceptions;
    }

    @Nullable
    private static List<PsiClassType> getUnhandledExceptions(@Nullable PsiElement element, PsiElement topElement, PsiMethod targetMethod) {
        if (element == null || element == topElement && !(topElement instanceof PsiMethodReferenceExpression))
            return null;
        List<PsiClassType> unhandledExceptions = ExceptionUtil.getUnhandledExceptions(element);
        if (!filterInProjectExceptions(targetMethod, unhandledExceptions).isEmpty()) {
            return unhandledExceptions;
        }
        if (topElement instanceof PsiMethodReferenceExpression) {
            return null;
        }
        return getUnhandledExceptions(element.getParent(), topElement, targetMethod);
    }


    @NotNull
    private static Set<PsiClassType> filterInProjectExceptions(@Nullable PsiMethod targetMethod, @NotNull List<? extends PsiClassType> unhandledExceptions) {
        if (targetMethod == null) return Collections.emptySet();

        Set<PsiClassType> result = new HashSet<>();

        if (targetMethod.getManager().isInProject(targetMethod)) {
            PsiMethod[] superMethods = targetMethod.findSuperMethods();
            for (PsiMethod superMethod : superMethods) {
                Set<PsiClassType> classTypes = filterInProjectExceptions(superMethod, unhandledExceptions);
                result.addAll(classTypes);
            }

            if (superMethods.length == 0) {
                result.addAll(unhandledExceptions);
            }
        } else {
            PsiClassType[] referencedTypes = targetMethod.getThrowsList().getReferencedTypes();
            for (PsiClassType referencedType : referencedTypes) {
                PsiClass psiClass = referencedType.resolve();
                if (psiClass == null) continue;
                for (PsiClassType exception : unhandledExceptions) {
                    if (referencedType.isAssignableFrom(exception)) result.add(exception);
                }
            }
        }

        return result;
    }

    public void invoke(@NotNull Project project, Editor editor, PsiElement element) throws
            IncorrectOperationException {

        int offset = editor.getCaretModel().getOffset();
        PsiElement elementAt = element.findElementAt(offset);
        PsiMethod psiMethod = PsiTreeUtil.getParentOfType(elementAt, PsiMethod.class);

        new WriteCommandAction.Simple(psiMethod.getProject(), psiMethod.getContainingFile()) {

            @Override
            protected void run() {
                generateAnnotation(psiMethod);
            }

        }.execute();

    }

    private void generateAnnotation(PsiMethod psiMethod) {
        psiMethod.getModifierList().addAnnotation(LOMBOK_SNEAKY_THROWS_PACKAGE);
        JavaCodeStyleManager.getInstance(psiMethod.getProject()).shortenClassReferences(psiMethod);
    }

    public boolean startInWriteAction() {
        return true;
    }


}
