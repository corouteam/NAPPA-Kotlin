package nl.vu.cs.s2group.nappa.plugin.action;


import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import nl.vu.cs.s2group.nappa.plugin.util.InstrumentResultMessage;
import nl.vu.cs.s2group.nappa.plugin.util.InstrumentUtil;
import nl.vu.cs.s2group.nappa.plugin.util.InstrumentUtilKt;
import org.apache.commons.lang.ObjectUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.asJava.elements.KtLightIdentifier;
import org.jetbrains.kotlin.lexer.KtKeywordToken;
import org.jetbrains.kotlin.lexer.KtSingleValueToken;
import org.jetbrains.kotlin.lexer.KtToken;
import org.jetbrains.kotlin.parsing.KotlinWhitespaceAndCommentsBindersKt;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.kotlin.psi.psiUtil.KtPsiUtilKt;

import java.util.Arrays;
import java.util.List;

/**
 * Will Instrument the startActivity(Intent) method in order to notify NAPPA of ALL extras that have been added for
 * a given activity.
 * <p>
 * NOTE: This action relies on the intent.getExtras() method, which will return NULL if there are NO extras added to the
 * intent.  NAPPA will ignore this instrumentation if the extras bundle is NULL.
 *
 * <p>
 * File {@link PsiFile}
 * |--->Class {@link PsiClass}
 * |-------|--->Method {@link PsiMethod}
 * |-------|----|------> Statement {@link PsiStatement}
 * <p>
 * The plugin considers the following Activity Transition Scenario:
 * <p>
 * intent.putExtra(EXTRA_MESSAGE, message);
 * Nappa.notifyExtras(intent.getAllExtras)
 * startActivity(intent);
 */

public class InstrumentIntentExtrasActionKt extends AnAction {
    private static final int HAS_NO_INLINE_IF = 0;
    private static final int HAS_INLINE_THEN_BRANCH = 1;
    private static final int HAS_INLINE_ELSE_BRANCH = 2;

    private Project project;
    private InstrumentResultMessage resultMessage;

    /**
     * Will find the location of the startActivity(...) method, and from there it will
     * prepend a call to Nappa.notifyExtras(intent.getAllExtras).
     *
     * @param event {@inheritDoc}
     */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        project = event.getProject();
        resultMessage = new InstrumentResultMessage();
        String[] fileFilter = new String[]{"android.content.Intent"};
        String[] classFilter = new String[]{"Intent"};

        try {
            List<PsiFile> psiFiles = InstrumentUtilKt.getAllKotlinFilesInProjectAsPsi(project);
            InstrumentUtilKt.runScanOnKotlinFile(psiFiles, fileFilter, classFilter, this::processPsiStatement);
            resultMessage.showResultDialog(project, "Intent Extras Instrumentation Result");
        } catch (Exception exception) {
            resultMessage.showErrorDialog(project, exception, "Failed to Instrument Intent Extras");
        }


    }



    private void processPsiStatement(@NotNull KtExpression rootPsiElement) {
        // Defines all variations of the method startActivity in the Android API
        String[] identifierFilter = new String[]{
                // https://developer.android.com/reference/android/app/Activity#startActivity(android.content.Intent)
                "startActivity",

                // https://developer.android.com/reference/android/app/Activity#startActivityForResult(android.content.Intent,%20int)
                "startActivityForResult",

                // https://developer.android.com/reference/android/app/Activity#startActivityFromChild(android.app.Activity,%20android.content.Intent,%20int)
                // This method was deprecated in API level 30.
                "startActivityFromChild",

                // https://developer.android.com/reference/android/app/Activity#startActivityFromFragment(android.app.Fragment,%20android.content.Intent,%20int,%20android.os.Bundle)
                // This method was deprecated in API level 28.
                "startActivityFromFragment",

                // https://developer.android.com/reference/android/app/Activity#startActivityIfNeeded(android.content.Intent,%20int,%20android.os.Bundle)
                "startActivityIfNeeded",
        };
        rootPsiElement.accept(new KtTreeVisitorVoid(){
            @Override
            public void visitKtElement(@NotNull KtElement element) {
                String text = element.getText();
                resultMessage.incrementProcessedElementsCount();

                // This verification is done here to reduce the number of recursive calls
                if (!element.getText().contains("startActivity")) return ;

                // Verifies if it is a identifier of a startActivity method PsiIdentifier
                if (!(element instanceof KtNameReferenceExpression) || Arrays.stream(identifierFilter).noneMatch(element.getText()::equals)) {
                    super.visitElement(element);
                    return ;
                }

                // Verifies if this identifier refers to a method call
                PsiElement parent =  element.getParent();
                if (parent == null || !(parent instanceof KtCallExpression)) return ;

                KtCallExpression methodCall = (KtCallExpression) parent;
                resultMessage.incrementPossibleInstrumentationCount();

                //TODO: translate this
                /*
                // Verifies if the startActivity method call is declared inside an inline lambda function
                // or in a IF statement with an inline THEN or ELSe branches
                int hasInlineIfStatement = isProcessingInlineIf(methodCall);
                boolean hasInlineLambdaFunction = methodCall.getParent() instanceof PsiLambdaExpression;
                boolean requiresToEncapsulateInCodeBlock = hasInlineLambdaFunction || hasInlineIfStatement != HAS_NO_INLINE_IF;

                // Verifies if the processed element is in an inline THEN branch of a IF statement and if
                // the IF has a ELSE branch. If this is the case, the the ELSE branch is processed first.
                // In the current implementation, if the THEN branch is processed before the ELSE branch,
                // then the ELSE branch is skipped.
                if (hasInlineIfStatement == HAS_INLINE_THEN_BRANCH) {
                    PsiIfStatement ifStatement = PsiTreeUtil.getParentOfType(methodCall, PsiIfStatement.class, false, PsiCodeBlock.class);
                    if (ifStatement != null && ifStatement.getElseBranch() != null)
                        super.visitElement(ifStatement.getElseBranch());
                }
                */

                KtValueArgument intentParameter = findElementSentAsIntentParameter((KtNameReferenceExpression)element, methodCall);
                KtExpression referenceStatement = (KtExpression) KtPsiUtil.getParentCallIfPresent(intentParameter.getArgumentExpression());
                if (referenceStatement == null || intentParameter == null) return;

                //TODO: Translate this
                /*
                 // Verifies if this element is already instrumented. The requiresToEncapsulateInCodeBlock flag
                // is considered in the verification since any inline block instrumented by this action will
                // always be replaced with a code block. Thus, if a inline statement is found, the method has
                // not been instrumented yet. Furthermore, the previous statement of a inline block might contain
                // a instrumented statement referent to another startActivity method.
                PsiStatement previousStatement = PsiTreeUtil.getPrevSiblingOfType(referenceStatement, PsiStatement.class);
                if (previousStatement != null && !requiresToEncapsulateInCodeBlock && previousStatement.getText().contains("Nappa.notifyExtras")) {
                    resultMessage.incrementAlreadyInstrumentedCount();
                    return;
                }
                 */
                KtClass ktClass = (KtClass) KtPsiUtil.getTopmostParentOfTypes(rootPsiElement, KtClass.class);
                //noinspection ConstantConditions --> To arrive here we looped through Java clasees
                InstrumentUtilKt.addLibraryImportToKt(project, ktClass);
                String instrumentedText = "Nappa.notifyExtras(INTENT.extras)";

                //TODO remove this
                boolean requiresToEncapsulateInCodeBlock = false;

                //TODO: translate injectExtraProbeForVariableReference
                //intentParameter.getArgumentExpression() = KtNAmeReference Expression
                if (intentParameter.getArgumentExpression() instanceof KtNameReferenceExpression)
                    injectExtraProbeForVariableReference(ktClass,
                            referenceStatement,
                            methodCall,
                            intentParameter,
                            instrumentedText,
                            requiresToEncapsulateInCodeBlock);
                else
                    injectExtraProbeForMethodCallOrNewExpression(ktClass,
                            referenceStatement,
                            methodCall,
                            intentParameter,
                            instrumentedText,
                            requiresToEncapsulateInCodeBlock);
                return;
            }
        });
    }

    /**
     * Verifies if the method call is declared in an inline THEN/ELSE branch of an IF statement
     *
     * @param methodCall The {@code startActivity} method
     * @return {@code True} if the {@code startActivity} method is declared either in the THEN or ELSE branch
     * of an IF statement and the branch is an inline branch
     */
    private int isProcessingInlineIf(PsiMethodCallExpression methodCall) {
        // Verifies if the methodCall is inside an IF statement
        PsiIfStatement ifStatement = PsiTreeUtil.getParentOfType(methodCall, PsiIfStatement.class, false, PsiCodeBlock.class);
        if (ifStatement == null) return HAS_NO_INLINE_IF;

        // Verifies if there is an THEN branch -- It should always have, but just in case...
        PsiStatement thenBranch = ifStatement.getThenBranch();
        if (thenBranch == null) return HAS_NO_INLINE_IF;

        // Verifies if the THEN branch is inline and if it contains the methodCall
        PsiMethodCallExpression methodCallInThenBranch = PsiTreeUtil.getChildOfType(ifStatement.getThenBranch(), PsiMethodCallExpression.class);
        if (methodCallInThenBranch != null && methodCallInThenBranch.equals(methodCall)) return HAS_INLINE_THEN_BRANCH;

        // Verifies if there is an ELSE branch
        PsiStatement elseBranch = ifStatement.getElseBranch();
        if (elseBranch == null) return HAS_NO_INLINE_IF;

        // Verifies if the ELSE branch is inline and if it contains the methodCall
        PsiMethodCallExpression methodCallInElseBranch = PsiTreeUtil.getChildOfType(ifStatement.getElseBranch(), PsiMethodCallExpression.class);
        if (methodCallInElseBranch != null && methodCallInElseBranch.equals(methodCall)) return HAS_INLINE_ELSE_BRANCH;

        return HAS_NO_INLINE_IF;
    }

    /**
     * Scan the parameter list and return the {@link PsiElement} representing the object sent as the
     * {@link android.content.Intent Intent} parameter for the method {@code startActivity} and its variants
     *
     * @param methodIdentifier     Represent the identifier of the method {@code startActivity}
     * @param methodCallExpression Represents the list of parameter send to the method {@code startActivity}
     * @return The {@link PsiElement} object representing the parameter {@link android.content.Intent Intent}
     * or {@code null} otherwise
     */
    @Nullable
    private KtValueArgument findElementSentAsIntentParameter(@NotNull KtNameReferenceExpression methodIdentifier, KtCallExpression methodCallExpression) {
        // Verifies in which position the method receives a Intent parameter

        //TODO: Translate this
        String[] identifierFilter = new String[]{
                "startActivityFromChild",
                "startActivityFromFragment",
        };
        int parameterPosition = Arrays.asList(identifierFilter).contains(methodIdentifier.getText()) ? 2 : 1;
        int currentParameterPosition = 0;

        // Fetch the list of parameters
        KtValueArgumentList parameterList = methodCallExpression.getValueArgumentList();
        if (parameterList == null) return null;

        // Loop through the method parameters list
        int pSize = parameterList.getArguments().size();
        for (KtValueArgument child : parameterList.getArguments()) {
            //if (!(child instanceof KotlinWhitespaceAndCommentsBindersKt || child instanceof PsiWhiteSpace)) {
            if (true) {
                currentParameterPosition++;
                if (currentParameterPosition == parameterPosition) return child;
            }

        }
        return null;
    }

    /**
     * Instrument the simplest case when the method {@code startActivity} receives a existing
     * {@link android.content.Intent Intent} object. The target source code and resulting instrumentation
     * are the follow"
     *
     * <pre>{@code
     * // Target
     * Intent myIntent = ...
     * startActivity(myIntent);
     *
     * // Result
     * Intent myIntent = ...
     * Nappa.notifyExtras(myIntent.getExtras());
     * startActivity(myIntent);
     * }</pre>
     *
     * @param psiClass           Represents a Java class
     * @param referenceStatement Represents the {@link PsiElement} used as reference to inject a new {@link PsiElement}
     * @param methodCall         Represents the method {@code startActivity}
     * @param intentParameter    Represent the object send as the parameter {@link android.content.Intent Intent} in
     *                           the method {@code startActivity}
     * @param instrumentedText   Represents the template source code to inject
     */

    private void injectExtraProbeForVariableReference(KtClass psiClass,
                                                      PsiElement referenceStatement,
                                                      @NotNull KtExpression methodCall,
                                                      @NotNull KtValueArgument intentParameter,
                                                      @NotNull String instrumentedText,
                                                      boolean requiresToEncapsulateInCodeBlock) {
        // Construct the element to inject
        KtPsiFactory factory = new KtPsiFactory(project);
        PsiElement instrumentedElementLibrary = factory.createExpression(instrumentedText.replace("INTENT", intentParameter.getText()));

        PsiElement instrumentedElement = PsiElementFactory
                .getInstance(project)
                .createStatementFromText(instrumentedText.replace("INTENT", intentParameter.getText()), psiClass);

        //TODO: translate
        // Verifies if we are instrumenting a inline statement
        if (requiresToEncapsulateInCodeBlock) {
            injectExtraProbesForInlineLambdaFunction(methodCall, new PsiElement[]{
                    instrumentedElement,
                    PsiElementFactory
                            .getInstance(project)
                            .createStatementFromText(methodCall.getText() + ";", psiClass),
            });
            return;
        }

        // Inject the instrumented notifier of extra changes
        WriteCommandAction.runWriteCommandAction(project, () -> {
            PsiElement stat = referenceStatement.getParent().addBefore(instrumentedElement, referenceStatement);
            referenceStatement.addAfter(factory.createNewLine(),stat );
        });
    }

    /**
     * Instrument the simplest case when the method {@code startActivity} receives a new
     * {@link android.content.Intent Intent} object, either via a instantiation with keyword {@code new}
     * or a method call. The target source code and resulting instrumentation are the follow:
     * <br/><br/>
     *
     * <p> Case 1. Instantiation
     *
     * <pre>{@code
     * // Target
     * startActivity(new Intent(...));
     *
     * // Result
     * Intent intent = new Intent(...);
     * Nappa.notifyExtras(intent.getExtras());
     * startActivity(intent)
     * }</pre>
     *
     * <p> Case 1. Method call
     *
     * <pre>{@code
     * // Target
     * startActivity(Intent.createChooser(...));
     *
     * // Result
     * Intent intent = Intent.createChooser(...);
     * Nappa.notifyExtras(intent.getExtras());
     * startActivity(intent)
     * }</pre>
     *
     * @param psiClass           Represents a Java class
     * @param referenceStatement Represents the {@link PsiElement} used as reference to inject a new {@link PsiElement}
     * @param methodCall         Represents the method {@code startActivity}
     * @param intentParameter    Represent the object send as the parameter {@link android.content.Intent Intent} in
     *                           the method {@code startActivity}
     * @param instrumentedText   Represents the template source code to inject
     */
    private void injectExtraProbeForMethodCallOrNewExpression(KtClass psiClass,
                                                              PsiElement referenceStatement,
                                                              @NotNull KtExpression methodCall,
                                                              @NotNull PsiElement intentParameter,
                                                              @NotNull String instrumentedText,
                                                              boolean requiresToEncapsulateInCodeBlock) {
        // Construct the source code text to inject
        String variableName = InstrumentUtilKt.getUniqueVariableName(methodCall, "intent");
        String methodCallText = methodCall.getText().replace(intentParameter.getText(), variableName);


        // Construct the elements to inject -- The declaration of an Intent object and the call to the Prefetch Library
        KtPsiFactory factory = new KtPsiFactory(project);
        PsiElement instrumentedElementIntent = factory.createProperty(variableName, "Intent", false,intentParameter.getText());
        PsiElement instrumentedElementLibrary = factory.createExpression(instrumentedText.replace("INTENT", variableName));

        //TODO: translate this
        // Verifies if we are instrumenting a inline statement
        /*
        if (requiresToEncapsulateInCodeBlock) {
            injectExtraProbesForInlineLambdaFunction(methodCall, new PsiElement[]{
                    instrumentedElementIntent,
                    instrumentedElementLibrary,
                    PsiElementFactory
                            .getInstance(project)
                            .createStatementFromText(methodCallText + ";", psiClass),
            });
            return;
        }

         */

        // Construct the elements to inject -- The call to the method startActivity
        PsiElement instrumentedElementMethodCall = factory.createExpression(methodCallText);

        // Inject the instrumented notifier of extra changes and the new Intent object
        WriteCommandAction.runWriteCommandAction(project, () -> {
            PsiElement stat = referenceStatement.getParent().addBefore(instrumentedElementIntent, referenceStatement);
            referenceStatement.addAfter(factory.createNewLine(),stat );
            PsiElement stat2 = referenceStatement.getParent().addBefore(instrumentedElementLibrary, referenceStatement);
            referenceStatement.getParent().addAfter(factory.createNewLine(), stat2);
            methodCall.replace(instrumentedElementMethodCall);
        });
    }

    /**
     * This method provides an extension to the methods {@link #injectExtraProbeForMethodCallOrNewExpression}
     * and {@link #injectExtraProbeForVariableReference} for cases where the startActivity method to instrument
     * is declared within an inline statement (e.g. lambda function, inline THEN/ELSE branches in IFs statements).
     * It replaces the {@code methodCall} element with a new {@link PsiCodeBlock} containing all elements
     * in the list {@code elementsToInject}
     * <br/><br/>
     *
     * <p> Case 1. Lambda functions
     *
     * <pre>{@code
     * // Target
     * someMethod((someParams) -> startActivity(intent));
     * someMethod2((someParams2) -> startActivity(createsNewIntent()));
     *
     * // Result
     * someMethod((someParams) -> {
     *     Nappa.notifyExtras(intent.getExtras());
     *     startActivity(intent);
     * });
     * someMethod2((someParams2) -> {
     *     Intent intent1 = createsNewIntent();
     *     Nappa.notifyExtras(intent1.getExtras());
     *     startActivity(intent1);
     * });
     * }</pre>
     *
     * <p> Case 1. IF statements
     *
     * <pre>{@code
     * // Target
     * if(condition) startActivity(new Intent(....));
     * else startActivity(new Intent(....);
     *
     * // Result
     * if(condition) {
     *     Intent intent1 = new Intent(....);
     *     Nappa.notifyExtras(intent1.getExtras());
     *     startActivity(intent1);
     * } else {
     *     Intent intent = new Intent(....);
     *     Nappa.notifyExtras(intent.getExtras());
     *     startActivity(intent);
     * }
     * }</pre>
     *
     * @param methodCall       Represents the startActivity method to instrument
     * @param elementsToInject Represents the list of {@link PsiElement} to inject in this instrumentation
     */
    private void injectExtraProbesForInlineLambdaFunction(KtExpression methodCall, PsiElement[] elementsToInject) {
        // Fetches the ancestor with possible inline statement
        PsiLambdaExpression lambdaExpression = PsiTreeUtil.getParentOfType(methodCall, PsiLambdaExpression.class, false, PsiCodeBlock.class);
        PsiIfStatement ifStatement = PsiTreeUtil.getParentOfType(methodCall, PsiIfStatement.class, false, PsiCodeBlock.class);

        // Construct a empty code block to inject
        PsiElement newCodeBlock = PsiElementFactory
                .getInstance(project)
                .createCodeBlock();

        // Inject the instrumented statements within a code block
        WriteCommandAction.runWriteCommandAction(project, () -> {
            for (PsiElement psiElement : elementsToInject) {
                newCodeBlock.add(psiElement);
            }

            // Verifies what type of inline statement is being instrumented
            if (lambdaExpression != null) methodCall.replace(newCodeBlock);
            else if (ifStatement != null) methodCall.getParent().replace(newCodeBlock);
        });
    }
}

