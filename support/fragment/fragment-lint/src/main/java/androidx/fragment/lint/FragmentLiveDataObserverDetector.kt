/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.fragment.lint

import androidx.fragment.lint.FragmentLiveDataObserverDetector.Companion.ISSUE
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.LintFix
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.isKotlin
import com.intellij.psi.util.PsiTypesUtil
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Lint check for detecting calls to [LiveData.observe] with a [Fragment] instance as the
 * lifecycle owner inside the [Fragment]'s [Fragment.onCreateView], [Fragment.onViewCreated],
 * [Fragment.onActivityCreated], or [Fragment.onViewStateRestored].
 */
class FragmentLiveDataObserverDetector : Detector(), SourceCodeScanner {

    companion object {
        val ISSUE = Issue.create(
            id = "FragmentLiveDataObserve",
            briefDescription = "Use getViewLifecycleOwner() as the LifecycleOwner instead of " +
                    "a Fragment instance when observing a LiveData object.",
            explanation = """When observing a LiveData object from a fragment's onCreateView,
                | onViewCreated, onActivityCreated, or onViewStateRestored method
                | getViewLifecycleOwner() should be used as the LifecycleOwner rather than the
                | Fragment instance. The Fragment lifecycle can result in the Fragment being
                | active longer than its view. This can lead to unexpected behavior from
                | LiveData objects being observed longer than the Fragment's view is active.""",
            category = Category.CORRECTNESS,
            severity = Severity.ERROR,
            implementation = Implementation(
                FragmentLiveDataObserverDetector::class.java, Scope.JAVA_FILE_SCOPE
            ),
            androidSpecific = true
        )
    }

    private val lifecycleMethods = setOf("onCreateView", "onViewCreated", "onActivityCreated",
        "onViewStateRestored")

    override fun applicableSuperClasses(): List<String>? = listOf(FRAGMENT_CLASS)

    override fun visitClass(context: JavaContext, declaration: UClass) {
        declaration.methods.forEach {
            if (lifecycleMethods.contains(it.name)) {
                val visitor = RecursiveMethodVisitor(context, declaration.name, it.name)
                it.uastBody?.accept(visitor)
            }
        }
    }
}

/**
 * A UAST Visitor that recursively explores all method calls within a method to check for a call to
 * [LiveData.observe] with a [Fragment] instance as the lifecycle owner.
 *
 * @param context The context of the lint request.
 * @param originFragmentName The name of the Fragment class being checked.
 * @param lifecycleMethod The name of the originating Fragment lifecycle method.
 */
private class RecursiveMethodVisitor(
    private val context: JavaContext,
    private val originFragmentName: String?,
    private val lifecycleMethod: String
) : AbstractUastVisitor() {
    private val visitedMethods = mutableSetOf<UCallExpression>()

    override fun visitCallExpression(node: UCallExpression): Boolean {
        if (visitedMethods.contains(node)) {
            return super.visitCallExpression(node)
        }
        if (node.isLiveDataObserve(context)) {
            val lifecycleOwner = node.valueArguments[0]
            val lifecycleOwnerType = PsiTypesUtil.getPsiClass(lifecycleOwner.getExpressionType())
            if (lifecycleOwner.getExpressionType().extends(context, FRAGMENT_CLASS)) {
                if (lifecycleOwnerType == node.getContainingUClass()?.javaPsi) {
                    val methodFix = if (isKotlin(context.psiFile)) {
                        "viewLifecycleOwner"
                    } else {
                        "getViewLifecycleOwner()"
                    }
                    context.report(ISSUE, context.getLocation(lifecycleOwner),
                        "Use $methodFix as the LifecycleOwner.",
                        LintFix.create()
                            .replace()
                            .with(methodFix)
                            .build())
                } else {
                    context.report(ISSUE, context.getLocation(node),
                        "Unsafe call to observe with Fragment instance from $originFragmentName" +
                                ".$lifecycleMethod.")
                }
            }
        } else if (node.isInteresting(context)) {
            visitedMethods.add(node)
            val psiMethod = node.resolve() ?: return super.visitCallExpression(node)
            val uastNode = context.uastContext.getMethod(psiMethod)
            uastNode.uastBody?.accept(this)
            visitedMethods.remove(node)
        }
        return super.visitCallExpression(node)
    }
}

/**
 * Checks if the [UCallExpression] is a call that should be explored. If the call chain
 * will exit the current class without reference to the [Fragment] instance then the call chain
 * does not need to be explored further.
 *
 * @return Whether this [UCallExpression] is to a call within the Fragment class or has a
 *         reference to the Fragment passed as a parameter.
 */
internal fun UCallExpression.isInteresting(context: JavaContext): Boolean {
    if (PsiTypesUtil.getPsiClass(receiverType) == this.getContainingUClass()?.javaPsi) {
        return true
    }
    if (valueArgumentCount > 0) {
        valueArguments.forEach {
            if (it.getExpressionType().extends(context, FRAGMENT_CLASS)) {
                return true
            }
        }
    }
    return false
}

/**
 * Checks if the [UCallExpression] is a [LiveData.observe] call.
 */
internal fun UCallExpression.isLiveDataObserve(context: JavaContext): Boolean {
    if (methodName != "observe" ||
            !receiverType.extends(context, "androidx.lifecycle.LiveData") ||
            valueArgumentCount != 2) {
        return false
    }
    val psiParameters = resolve()?.parameterList?.parameters ?: return false
    return psiParameters[0].type.extends(context, "androidx.lifecycle.LifecycleOwner") &&
            psiParameters[1].type.extends(context, "androidx.lifecycle.Observer")
}

private const val FRAGMENT_CLASS = "androidx.fragment.app.Fragment"
