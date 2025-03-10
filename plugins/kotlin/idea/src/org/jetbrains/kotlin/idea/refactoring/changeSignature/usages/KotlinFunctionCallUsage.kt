// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.refactoring.changeSignature.usages

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.base.projectStructure.languageVersionSettings
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.codeInsight.shorten.addDelayedImportRequest
import org.jetbrains.kotlin.idea.core.moveFunctionLiteralOutsideParentheses
import org.jetbrains.kotlin.idea.base.psi.replaced
import org.jetbrains.kotlin.idea.intentions.RemoveEmptyParenthesesFromLambdaCallIntention
import org.jetbrains.kotlin.idea.refactoring.changeSignature.KotlinChangeInfo
import org.jetbrains.kotlin.idea.refactoring.changeSignature.KotlinParameterInfo
import org.jetbrains.kotlin.idea.refactoring.changeSignature.isInsideOfCallerBody
import org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.createNameCounterpartMap
import org.jetbrains.kotlin.idea.refactoring.introduce.introduceVariable.KotlinIntroduceVariableHandler
import org.jetbrains.kotlin.idea.refactoring.replaceListPsiAndKeepDelimiters
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.load.java.descriptors.JavaMethodDescriptor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.*
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.resolve.scopes.receivers.*
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker
import org.jetbrains.kotlin.types.expressions.OperatorConventions
import org.jetbrains.kotlin.util.kind
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.kotlin.utils.sure

class KotlinFunctionCallUsage(
    element: KtCallElement,
    private val callee: KotlinCallableDefinitionUsage<*>,
    forcedResolvedCall: ResolvedCall<*>? = null
) : KotlinUsageInfo<KtCallElement>(element) {
    private val context = element.analyze(BodyResolveMode.FULL)
    private val resolvedCall = forcedResolvedCall ?: element.getResolvedCall(context)
    private val skipUnmatchedArgumentsCheck = forcedResolvedCall != null

    override fun processUsage(changeInfo: KotlinChangeInfo, element: KtCallElement, allUsages: Array<out UsageInfo>): Boolean {
        processUsageAndGetResult(changeInfo, element, allUsages)
        return true
    }

    fun processUsageAndGetResult(
        changeInfo: KotlinChangeInfo,
        element: KtCallElement,
        allUsages: Array<out UsageInfo>,
        skipRedundantArgumentList: Boolean = false,
    ): KtElement {
        if (shouldSkipUsage(element)) return element

        var result: KtElement = element

        changeNameIfNeeded(changeInfo, element)

        if (element.valueArgumentList == null && changeInfo.isParameterSetOrOrderChanged && element.lambdaArguments.isNotEmpty()) {
            val anchor = element.typeArgumentList ?: element.calleeExpression
            if (anchor != null) {
                element.addAfter(KtPsiFactory(element).createCallArguments("()"), anchor)
            }
        }
        if (element.valueArgumentList != null) {
            if (changeInfo.isParameterSetOrOrderChanged) {
                result = updateArgumentsAndReceiver(changeInfo, element, allUsages, skipRedundantArgumentList)
            } else {
                changeArgumentNames(changeInfo, element)
            }
        }

        if (changeInfo.getNewParametersCount() == 0 && element is KtSuperTypeCallEntry) {
            val enumEntry = element.getStrictParentOfType<KtEnumEntry>()
            if (enumEntry != null && enumEntry.initializerList == element.parent) {
                val initializerList = enumEntry.initializerList
                enumEntry.deleteChildRange(enumEntry.getColon() ?: initializerList, initializerList)
            }
        }

        return result
    }

    private fun shouldSkipUsage(element: KtCallElement): Boolean {
        // TODO: We probable need more clever processing of invalid calls, but for now default to Java-like behaviour
        if (resolvedCall == null && element !is KtSuperTypeCallEntry) return true
        if (resolvedCall == null || resolvedCall.isReallySuccess()) return false

        // TODO: investigate why arguments are not recorded for enum constructor call
        if (element is KtSuperTypeCallEntry && element.parent.parent is KtEnumEntry && element.valueArguments.isEmpty()) return false

        if (skipUnmatchedArgumentsCheck) return false

        if (!resolvedCall.call.valueArguments.all { resolvedCall.getArgumentMapping(it) is ArgumentMatch }) return true

        val arguments = resolvedCall.valueArguments
        return !resolvedCall.resultingDescriptor.valueParameters.all { arguments.containsKey(it) }
    }

    private val isPropertyJavaUsage: Boolean
        get() {
            val calleeElement = this.callee.element
            if (calleeElement !is KtProperty && calleeElement !is KtParameter) return false
            return resolvedCall?.resultingDescriptor is JavaMethodDescriptor
        }

    private fun changeNameIfNeeded(changeInfo: KotlinChangeInfo, element: KtCallElement) {
        if (!changeInfo.isNameChanged) return

        val callee = element.calleeExpression as? KtSimpleNameExpression ?: return

        var newName = changeInfo.newName
        if (isPropertyJavaUsage) {
            val currentName = callee.getReferencedName()
            if (JvmAbi.isGetterName(currentName))
                newName = JvmAbi.getterName(newName)
            else if (JvmAbi.isSetterName(currentName)) newName = JvmAbi.setterName(newName)
        }

        callee.replace(KtPsiFactory(project).createSimpleName(newName))
    }

    private fun getReceiverExpressionIfMatched(
        receiverValue: ReceiverValue?,
        originalDescriptor: DeclarationDescriptor,
        psiFactory: KtPsiFactory
    ): KtExpression? {
        if (receiverValue == null) return null

        // Replace descriptor of extension function/property with descriptor of its receiver
        // to simplify checking against receiver value in the corresponding resolved call
        val adjustedDescriptor = if (originalDescriptor is CallableDescriptor && originalDescriptor !is ReceiverParameterDescriptor) {
            originalDescriptor.extensionReceiverParameter ?: return null
        } else originalDescriptor

        val currentIsExtension = resolvedCall!!.extensionReceiver == receiverValue
        val originalIsExtension = adjustedDescriptor is ReceiverParameterDescriptor && adjustedDescriptor.value is ExtensionReceiver
        if (currentIsExtension != originalIsExtension) return null

        val originalType = when (adjustedDescriptor) {
            is ReceiverParameterDescriptor -> adjustedDescriptor.type
            is ClassDescriptor -> adjustedDescriptor.defaultType
            else -> null
        }
        if (originalType == null || !KotlinTypeChecker.DEFAULT.isSubtypeOf(receiverValue.type, originalType)) return null

        return getReceiverExpression(receiverValue, psiFactory)
    }

    private fun needSeparateVariable(element: PsiElement): Boolean {
        return when {
            element is KtConstantExpression || element is KtThisExpression || element is KtSimpleNameExpression -> false
            element is KtBinaryExpression && OperatorConventions.ASSIGNMENT_OPERATIONS.contains(element.operationToken) -> true
            element is KtUnaryExpression && OperatorConventions.INCREMENT_OPERATIONS.contains(element.operationToken) -> true
            element is KtCallExpression -> element.getResolvedCall(context)?.resultingDescriptor is ConstructorDescriptor
            else -> element.children.any { needSeparateVariable(it) }
        }
    }

    private fun substituteReferences(
        expression: KtExpression,
        referenceMap: Map<PsiReference, DeclarationDescriptor>,
        psiFactory: KtPsiFactory
    ): KtExpression {
        if (referenceMap.isEmpty() || resolvedCall == null) return expression

        var newExpression = expression.copy() as KtExpression

        val nameCounterpartMap = createNameCounterpartMap(expression, newExpression)

        val valueArguments = resolvedCall.valueArguments

        val replacements = ArrayList<Pair<KtExpression, KtExpression>>()
        loop@ for ((ref, descriptor) in referenceMap.entries) {
            var argumentExpression: KtExpression?
            val addReceiver: Boolean
            if (descriptor is ValueParameterDescriptor) {
                // Ordinary parameter
                // Find corresponding parameter in the current function (may differ from 'descriptor' if original function is part of override hierarchy)
                val parameterDescriptor = resolvedCall.resultingDescriptor.valueParameters[descriptor.index]
                val resolvedValueArgument = valueArguments[parameterDescriptor] as? ExpressionValueArgument ?: continue
                val argument = resolvedValueArgument.valueArgument ?: continue

                addReceiver = false
                argumentExpression = argument.getArgumentExpression()
            } else {
                addReceiver = descriptor !is ReceiverParameterDescriptor
                argumentExpression =
                    getReceiverExpressionIfMatched(resolvedCall.extensionReceiver, descriptor, psiFactory)
                        ?: getReceiverExpressionIfMatched(resolvedCall.dispatchReceiver, descriptor, psiFactory)
            }
            if (argumentExpression == null) continue

            if (needSeparateVariable(argumentExpression)
                && PsiTreeUtil.getNonStrictParentOfType(
                    element,
                    KtConstructorDelegationCall::class.java,
                    KtSuperTypeListEntry::class.java,
                    KtParameter::class.java
                ) == null
            ) {

                KotlinIntroduceVariableHandler.doRefactoring(
                    project, null, argumentExpression,
                    isVar = false,
                    occurrencesToReplace = listOf(argumentExpression),
                    onNonInteractiveFinish = {
                        argumentExpression = psiFactory.createExpression(it.name!!)
                    })
            }

            var expressionToReplace: KtExpression = nameCounterpartMap[ref.element] ?: continue
            val parent = expressionToReplace.parent

            if (parent is KtThisExpression) {
                expressionToReplace = parent
            }

            if (addReceiver) {
                val callExpression = expressionToReplace.getParentOfTypeAndBranch<KtCallExpression>(true) { calleeExpression }
                when {
                    callExpression != null -> expressionToReplace = callExpression
                    parent is KtOperationExpression && parent.operationReference == expressionToReplace -> continue@loop
                }

                val replacement = psiFactory.createExpression("${argumentExpression!!.text}.${expressionToReplace.text}")
                replacements.add(expressionToReplace to replacement)
            } else {
                replacements.add(expressionToReplace to argumentExpression!!)
            }
        }

        // Sort by descending offset so that call arguments are replaced before call itself
        ContainerUtil.sort(replacements, REVERSED_TEXT_OFFSET_COMPARATOR)
        for ((expressionToReplace, replacingExpression) in replacements) {
            val replaced = expressionToReplace.replaced(replacingExpression)
            if (expressionToReplace == newExpression) {
                newExpression = replaced
            }
        }

        return newExpression
    }

    class ArgumentInfo(
        val parameter: KotlinParameterInfo,
        val parameterIndex: Int,
        val resolvedArgument: ResolvedValueArgument?,
        val receiverValue: ReceiverValue?
    ) {
        private val mainValueArgument: ValueArgument?
            get() = resolvedArgument?.arguments?.firstOrNull()

        val wasNamed: Boolean
            get() = mainValueArgument?.isNamed() ?: false

        var name: String? = null
            private set

        fun makeNamed(callee: KotlinCallableDefinitionUsage<*>) {
            name = parameter.getInheritedName(callee)
        }

        fun shouldSkip() = parameter.defaultValue != null && mainValueArgument == null
    }

    private fun getResolvedValueArgument(oldIndex: Int): ResolvedValueArgument? {
        if (oldIndex < 0) return null

        val parameterDescriptor = resolvedCall!!.resultingDescriptor.valueParameters[oldIndex]
        return resolvedCall.valueArguments[parameterDescriptor]
    }

    private fun ArgumentInfo.getArgumentByDefaultValue(
        element: KtCallElement,
        allUsages: Array<out UsageInfo>,
        psiFactory: KtPsiFactory
    ): KtValueArgument {
        val isInsideOfCallerBody = element.isInsideOfCallerBody(allUsages)
        val defaultValueForCall = parameter.defaultValueForCall
        val argValue = when {
            isInsideOfCallerBody -> psiFactory.createExpression(parameter.name)
            defaultValueForCall != null -> substituteReferences(
                defaultValueForCall,
                parameter.defaultValueParameterReferences,
                psiFactory,
            ).asMarkedForShortening()

            else -> null
        }

        val argName = (if (isInsideOfCallerBody) null else name)?.let { Name.identifier(it) }
        return psiFactory.createArgument(argValue ?: psiFactory.createExpression("0"), argName).apply {
            if (argValue == null) {
                getArgumentExpression()?.delete()
            }
        }
    }

    private fun ExpressionReceiver.wrapInvalidated(element: KtCallElement): ExpressionReceiver = object : ExpressionReceiver by this {
        override val expression = element.getQualifiedExpressionForSelector()!!.receiverExpression
    }

    private fun updateArgumentsAndReceiver(
        changeInfo: KotlinChangeInfo,
        element: KtCallElement,
        allUsages: Array<out UsageInfo>,
        skipRedundantArgumentList: Boolean,
    ): KtElement {
        if (isPropertyJavaUsage) return updateJavaPropertyCall(changeInfo, element)

        val fullCallElement = element.getQualifiedExpressionForSelector() ?: element

        val oldArguments = element.valueArguments
        val newParameters = changeInfo.getNonReceiverParameters()

        val purelyNamedCall = element is KtCallExpression && oldArguments.isNotEmpty() && oldArguments.all { it.isNamed() }

        val newReceiverInfo = changeInfo.receiverParameterInfo
        val originalReceiverInfo = changeInfo.methodDescriptor.receiver

        val extensionReceiver = resolvedCall?.extensionReceiver
        val dispatchReceiver = resolvedCall?.dispatchReceiver

        // Do not add extension receiver to calls with explicit dispatch receiver except for objects
        if (newReceiverInfo != null &&
            fullCallElement is KtQualifiedExpression &&
            dispatchReceiver is ExpressionReceiver
        ) {
            if (isObjectReceiver(dispatchReceiver)) {
                //It's safe to replace object reference with a new receiver, but we shall import the function
                addDelayedImportRequest(changeInfo.method, element.containingKtFile)
            } else {
                return element
            }
        }

        val newArgumentInfos = newParameters.asSequence().withIndex().map {
            val (index, param) = it
            val oldIndex = param.oldIndex
            val resolvedArgument = if (oldIndex >= 0) getResolvedValueArgument(oldIndex) else null
            var receiverValue = if (param == originalReceiverInfo) extensionReceiver else null
            // Workaround for recursive calls where implicit extension receiver is transformed into ordinary value argument
            // Receiver expression retained in the original resolved call is no longer valid at this point
            if (receiverValue is ExpressionReceiver && !receiverValue.expression.isValid) {
                receiverValue = receiverValue.wrapInvalidated(element)
            }
            ArgumentInfo(param, index, resolvedArgument, receiverValue)
        }.toList()

        val lastParameterIndex = newParameters.lastIndex
        val canMixArguments = element.languageVersionSettings.supportsFeature(LanguageFeature.MixedNamedArgumentsInTheirOwnPosition)
        var firstNamedIndex = newArgumentInfos.firstOrNull {
            !canMixArguments && it.wasNamed ||
                    it.parameter.isNewParameter && it.parameter.defaultValue != null ||
                    it.resolvedArgument is VarargValueArgument && it.parameterIndex < lastParameterIndex
        }?.parameterIndex

        if (firstNamedIndex == null) {
            val lastNonDefaultArgIndex = (lastParameterIndex downTo 0).firstOrNull { !newArgumentInfos[it].shouldSkip() } ?: -1
            firstNamedIndex = (0..lastNonDefaultArgIndex).firstOrNull { newArgumentInfos[it].shouldSkip() }
        }

        val lastPositionalIndex = if (firstNamedIndex != null) firstNamedIndex - 1 else lastParameterIndex
        val namedRange = lastPositionalIndex + 1..lastParameterIndex
        for ((index, argument) in newArgumentInfos.withIndex()) {
            if (purelyNamedCall || argument.wasNamed || index in namedRange) {
                argument.makeNamed(callee)
            }
        }

        val psiFactory = KtPsiFactory(element.project)

        val newArgumentList = psiFactory.createCallArguments("()").apply {
            for (argInfo in newArgumentInfos) {
                if (argInfo.shouldSkip()) continue

                val name = argInfo.name?.let { Name.identifier(it) }

                if (argInfo.receiverValue != null) {
                    val receiverExpression = getReceiverExpression(argInfo.receiverValue, psiFactory) ?: continue
                    addArgument(psiFactory.createArgument(receiverExpression, name))
                    continue
                }

                when (val resolvedArgument = argInfo.resolvedArgument) {
                    null, is DefaultValueArgument -> addArgument(argInfo.getArgumentByDefaultValue(element, allUsages, psiFactory))

                    is ExpressionValueArgument -> {
                        val valueArgument = resolvedArgument.valueArgument
                        val newValueArgument: KtValueArgument = when {
                            valueArgument == null -> argInfo.getArgumentByDefaultValue(element, allUsages, psiFactory)
                            valueArgument is KtLambdaArgument -> psiFactory.createArgument(valueArgument.getArgumentExpression(), name)
                            valueArgument is KtValueArgument && valueArgument.getArgumentName()?.asName == name -> valueArgument
                            else -> psiFactory.createArgument(valueArgument.getArgumentExpression(), name)
                        }
                        addArgument(newValueArgument)
                    }

                    // TODO: Support Kotlin varargs
                    is VarargValueArgument -> resolvedArgument.arguments.forEach {
                        if (it is KtValueArgument) addArgument(it)
                    }

                    else -> return element
                }
            }
        }

        newArgumentList.arguments.singleOrNull()?.let {
            if (it.getArgumentExpression() == null) {
                newArgumentList.removeArgument(it)
            }
        }

        val lastOldArgument = oldArguments.lastOrNull()
        val lastNewParameter = newParameters.lastOrNull()
        val lastNewArgument = newArgumentList.arguments.lastOrNull()
        val oldLastResolvedArgument = getResolvedValueArgument(lastNewParameter?.oldIndex ?: -1) as? ExpressionValueArgument
        val lambdaArgumentNotTouched = lastOldArgument is KtLambdaArgument && oldLastResolvedArgument?.valueArgument == lastOldArgument
        val newLambdaArgumentAddedLast = lastNewParameter != null
                && lastNewParameter.isNewParameter
                && lastNewParameter.defaultValueForCall is KtLambdaExpression
                && lastNewArgument?.getArgumentExpression() is KtLambdaExpression
                && !lastNewArgument.isNamed()

        if (lambdaArgumentNotTouched) {
            newArgumentList.removeArgument(newArgumentList.arguments.last())
        } else {
            val lambdaArguments = element.lambdaArguments
            if (lambdaArguments.isNotEmpty()) {
                element.deleteChildRange(lambdaArguments.first(), lambdaArguments.last())
            }
        }

        val oldArgumentList = element.valueArgumentList.sure { "Argument list is expected: " + element.text }
        for (argument in replaceListPsiAndKeepDelimiters(changeInfo, oldArgumentList, newArgumentList) { arguments }.arguments) {
            if (argument.getArgumentExpression() == null) argument.delete()
        }

        var newElement: KtElement = element
        if (newReceiverInfo != originalReceiverInfo) {
            val replacingElement: PsiElement = if (newReceiverInfo != null) {
                val receiverArgument = getResolvedValueArgument(newReceiverInfo.oldIndex)?.arguments?.singleOrNull()
                val extensionReceiverExpression = receiverArgument?.getArgumentExpression()
                val defaultValueForCall = newReceiverInfo.defaultValueForCall
                val receiver = extensionReceiverExpression?.let { psiFactory.createExpression(it.text) }
                    ?: defaultValueForCall?.asMarkedForShortening()
                    ?: psiFactory.createExpression("_")

                psiFactory.createExpressionByPattern("$0.$1", receiver, element)
            } else {
                element.copy()
            }

            newElement = fullCallElement.replace(replacingElement) as KtElement
        }

        val newCallExpression = newElement.safeAs<KtExpression>()?.getPossiblyQualifiedCallExpression()
        if (!lambdaArgumentNotTouched && newLambdaArgumentAddedLast) {
            newCallExpression?.moveFunctionLiteralOutsideParentheses()
        }

        if (!skipRedundantArgumentList) {
            newCallExpression?.valueArgumentList?.let(RemoveEmptyParenthesesFromLambdaCallIntention::applyToIfApplicable)
        }

        newElement.flushElementsForShorteningToWaitList()
        return newElement
    }

    private fun isObjectReceiver(dispatchReceiver: ReceiverValue) = dispatchReceiver.safeAs<ClassValueReceiver>()?.classQualifier?.descriptor?.kind == ClassKind.OBJECT

    private fun changeArgumentNames(changeInfo: KotlinChangeInfo, element: KtCallElement) {
        for (argument in element.valueArguments) {
            val argumentName = argument.getArgumentName()
            val argumentNameExpression = argumentName?.referenceExpression ?: continue
            val oldParameterIndex = changeInfo.getOldParameterIndex(argumentNameExpression.getReferencedName()) ?: continue
            val newParameterIndex = if (changeInfo.receiverParameterInfo != null) oldParameterIndex + 1 else oldParameterIndex
            val parameterInfo = changeInfo.newParameters[newParameterIndex]
            changeArgumentName(argumentNameExpression, parameterInfo)
        }
    }

    private fun changeArgumentName(argumentNameExpression: KtSimpleNameExpression?, parameterInfo: KotlinParameterInfo) {
        val identifier = argumentNameExpression?.getIdentifier() ?: return
        val newName = parameterInfo.getInheritedName(callee)
        identifier.replace(KtPsiFactory(project).createIdentifier(newName))
    }

    companion object {
        private val REVERSED_TEXT_OFFSET_COMPARATOR = Comparator<Pair<KtElement, KtElement>> { p1, p2 ->
            val offset1 = p1.first.startOffset
            val offset2 = p2.first.startOffset
            when {
                offset1 < offset2 -> 1
                offset1 > offset2 -> -1
                else -> 0
            }
        }

        private fun updateJavaPropertyCall(changeInfo: KotlinChangeInfo, element: KtCallElement): KtElement {
            val newReceiverInfo = changeInfo.receiverParameterInfo
            val originalReceiverInfo = changeInfo.methodDescriptor.receiver
            if (newReceiverInfo == originalReceiverInfo) return element

            val arguments = element.valueArgumentList.sure { "Argument list is expected: " + element.text }
            val oldArguments = element.valueArguments

            val psiFactory = KtPsiFactory(element.project)

            val firstArgument = oldArguments.firstOrNull() as KtValueArgument?

            when {
                newReceiverInfo != null -> {
                    val defaultValueForCall = newReceiverInfo.defaultValueForCall ?: psiFactory.createExpression("_")
                    val newReceiverArgument = psiFactory.createArgument(defaultValueForCall, null, false)

                    if (originalReceiverInfo != null) {
                        firstArgument?.replace(newReceiverArgument)
                    } else {
                        arguments.addArgumentAfter(newReceiverArgument, null)
                    }
                }

                firstArgument != null -> arguments.removeArgument(firstArgument)
            }

            return element
        }

        private fun getReceiverExpression(receiver: ReceiverValue, psiFactory: KtPsiFactory): KtExpression? {
            return when (receiver) {
                is ExpressionReceiver -> receiver.expression
                is ImplicitReceiver -> {
                    val descriptor = receiver.declarationDescriptor
                    val thisText = if (descriptor is ClassDescriptor && !DescriptorUtils.isAnonymousObject(descriptor)) {
                        "this@" + descriptor.name.asString()
                    } else {
                        "this"
                    }
                    psiFactory.createExpression(thisText)
                }
                else -> null
            }
        }
    }
}
