// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.j2k.post.processing.inference.common

import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptorIfAny
import org.jetbrains.kotlin.idea.intentions.branchedTransformations.isNullExpression
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.util.getType
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.typeUtil.builtIns
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

interface BoundTypeCalculator {
    fun expressionsWithBoundType(): List<Pair<KtExpression, BoundType>>

    fun KtExpression.boundType(inferenceContext: InferenceContext): BoundType

    fun KotlinType.boundType(
      typeVariable: TypeVariable? = null,
      contextBoundType: BoundType? = null,
      call: ResolvedCall<*>? = null,
      isImplicitReceiver: Boolean = false,
      forceEnhance: Boolean = false,
      inferenceContext: InferenceContext
    ): BoundType
}

open class BoundTypeCalculatorImpl(
    private val resolutionFacade: ResolutionFacade,
    private val enhancer: BoundTypeEnhancer
) : BoundTypeCalculator {
    private val cache = mutableMapOf<KtExpression, BoundType>()

    final override fun expressionsWithBoundType() = cache.toList()

    final override fun KtExpression.boundType(inferenceContext: InferenceContext): BoundType = cache.getOrPut(this) {
        calculateBoundType(inferenceContext, this)
    }

    open fun interceptCalculateBoundType(inferenceContext: InferenceContext, expression: KtExpression): BoundType? =
        null

    private fun calculateBoundType(inferenceContext: InferenceContext, expression: KtExpression): BoundType =
      interceptCalculateBoundType(inferenceContext, expression) ?: when {
            expression.isNullExpression() -> BoundType.NULL
            expression is KtParenthesizedExpression -> expression.expression?.boundType(inferenceContext)
            expression is KtConstantExpression
                    || expression is KtStringTemplateExpression
                    || expression.node?.elementType == KtNodeTypes.BOOLEAN_CONSTANT
                    || expression is KtBinaryExpression ->
              BoundType.LITERAL
            expression is KtQualifiedExpression -> expression.toBoundTypeAsQualifiedExpression(inferenceContext)
            expression is KtBinaryExpressionWithTypeRHS -> expression.toBoundTypeAsCastExpression(inferenceContext)
            expression is KtNameReferenceExpression -> expression.toBoundTypeAsReferenceExpression(inferenceContext)
            expression is KtCallExpression -> expression.toBoundTypeAsCallableExpression(null, inferenceContext)
            expression is KtLambdaExpression -> expression.toBoundTypeAsLambdaExpression(inferenceContext)
            expression is KtLabeledExpression -> expression.baseExpression?.boundType(inferenceContext)
            expression is KtIfExpression -> expression.toBoundTypeAsIfExpression(inferenceContext)
            else -> null
        }?.let { boundType ->
            enhancer.enhance(expression, boundType, inferenceContext)
        } ?: BoundType.LITERAL

    private fun KtIfExpression.toBoundTypeAsIfExpression(inferenceContext: InferenceContext): BoundType? {
        val isNullLiteralPossible = then?.isNullExpression() == true || `else`?.isNullExpression() == true
        if (isNullLiteralPossible) {
            return BoundType.NULL
        }
        return (then ?: `else`)?.boundType(inferenceContext) //TODO handle both cases separatelly
    }

    private fun KtLambdaExpression.toBoundTypeAsLambdaExpression(inferenceContext: InferenceContext): BoundType? {
        val descriptor = functionLiteral.resolveToDescriptorIfAny(resolutionFacade).safeAs<FunctionDescriptor>() ?: return null
        val builtIns = getType(analyze())?.builtIns ?: return null
        val prototypeDescriptor = builtIns.getFunction(valueParameters.size)
        val parameterBoundTypes = if (descriptor.valueParameters.size == valueParameters.size) {
            valueParameters.map { parameter ->
                parameter.typeReference?.typeElement?.let { typeElement ->
                    inferenceContext.typeElementToTypeVariable[typeElement]
                }?.asBoundType() ?: return null
            }
        } else {
            descriptor.valueParameters.map { parameter ->
                parameter.type.boundType(inferenceContext = inferenceContext)
            }
        }
        val returnTypeTypeVariable = inferenceContext.declarationToTypeVariable[functionLiteral] ?: return null
        val returnTypeParameter =
            TypeParameter(
              BoundTypeImpl(
                TypeVariableLabel(returnTypeTypeVariable),
                returnTypeTypeVariable.typeParameters
                ),
              Variance.OUT_VARIANCE
            )
        return BoundTypeImpl(
          GenericLabel(prototypeDescriptor.classReference),
            parameterBoundTypes.map { TypeParameter(it, Variance.IN_VARIANCE) } + returnTypeParameter
        )
    }

    private fun KtNameReferenceExpression.toBoundTypeAsReferenceExpression(inferenceContext: InferenceContext): BoundType? =
        mainReference
            .resolve()
            ?.safeAs<KtDeclaration>()
            ?.let { declaration ->
                val boundType = inferenceContext.declarationToTypeVariable[declaration]?.asBoundType()
                    ?: return@let null
                if (declaration.safeAs<KtParameter>()?.isVarArg == true) {
                    val arrayClassReference =
                        declaration.resolveToDescriptorIfAny(resolutionFacade)
                            ?.safeAs<ValueParameterDescriptor>()
                            ?.returnType
                            ?.constructor
                            ?.declarationDescriptor
                            ?.safeAs<ClassDescriptor>()
                            ?.classReference ?: NoClassReference
                    BoundTypeImpl(
                      GenericLabel(arrayClassReference),
                      listOf(TypeParameter(boundType, Variance.INVARIANT))
                    )
                } else boundType
            }


    private fun KtBinaryExpressionWithTypeRHS.toBoundTypeAsCastExpression(inferenceContext: InferenceContext): BoundType? =
        right?.typeElement
            ?.let { inferenceContext.typeElementToTypeVariable[it]?.asBoundType() }


    private fun KtExpression.toBoundTypeAsCallableExpression(
      contextBoundType: BoundType?,
      inferenceContext: InferenceContext
    ): BoundType? {
        val call = getResolvedCall(analyze(resolutionFacade)) ?: return null
        val returnType = call.candidateDescriptor.original.returnType ?: return null
        val callDescriptor = call.candidateDescriptor.original
        val returnTypeVariable = inferenceContext.declarationDescriptorToTypeVariable[callDescriptor]
        val withImplicitContextBoundType = contextBoundType
            ?: call.dispatchReceiver
                ?.type
                ?.boundType(inferenceContext = inferenceContext)

        return returnType.boundType(
            returnTypeVariable,
            withImplicitContextBoundType,
            call,
            withImplicitContextBoundType != contextBoundType,
            false,
            inferenceContext
        )
    }

    private fun KtQualifiedExpression.toBoundTypeAsQualifiedExpression(inferenceContext: InferenceContext): BoundType? {
        val receiverBoundType = receiverExpression.boundType(inferenceContext)
        val selectorExpression = selectorExpression ?: return null
        return selectorExpression.toBoundTypeAsCallableExpression(receiverBoundType, inferenceContext)
    }

    final override fun KotlinType.boundType(
      typeVariable: TypeVariable?,
      contextBoundType: BoundType?,
      call: ResolvedCall<*>?,
      isImplicitReceiver: Boolean,
      forceEnhance: Boolean,
      inferenceContext: InferenceContext
    ) = boundTypeUnenhanced(
        typeVariable,
        contextBoundType,
        call,
        isImplicitReceiver,
        inferenceContext
    )?.let { boundType ->
        val needEnhance = run {
            if (forceEnhance) return@run true
            !inferenceContext.isInConversionScope(
                call?.call?.calleeExpression?.mainReference?.resolve() ?: return@run false
            )
        }
        if (needEnhance) enhancer.enhanceKotlinType(this, boundType, forceEnhance, inferenceContext)
        else boundType
    } ?: BoundType.LITERAL

    private fun KotlinType.boundTypeUnenhanced(
      typeVariable: TypeVariable?,
      contextBoundType: BoundType?,
      call: ResolvedCall<*>?,
      isImplicitReceiver: Boolean,
      inferenceContext: InferenceContext
    ): BoundType? {
        return when (val target = constructor.declarationDescriptor) {
            is ClassDescriptor ->
                BoundTypeImpl(
                    typeVariable?.let { TypeVariableLabel(it) } ?: GenericLabel(target.classReference),
                    arguments.mapIndexed { i, argument ->
                        val argumentBoundType = when {
                            argument.isStarProjection -> BoundType.STAR_PROJECTION
                            else -> argument.type.boundTypeUnenhanced(
                              typeVariable?.typeParameters?.getOrNull(i)?.boundType?.label?.safeAs<TypeVariableLabel>()?.typeVariable,
                              contextBoundType,
                              call,
                              isImplicitReceiver,
                              inferenceContext
                            ) ?: return null
                        }
                        TypeParameter(argumentBoundType, constructor.parameters.getOrNull(i)?.variance ?: return null)
                    }
                )

            is TypeParameterDescriptor -> {
                val containingDeclaration = target.containingDeclaration.let { container ->
                    when (container) {
                        is TypeAliasDescriptor -> container.classDescriptor
                        else -> container
                    } ?: container
                }
                when {
                    containingDeclaration == call?.candidateDescriptor?.original -> {
                        val returnTypeVariable = inferenceContext.typeElementToTypeVariable[
                                call.call.typeArguments.getOrNull(target.index)?.typeReference?.typeElement
                                    ?: return BoundType.STAR_PROJECTION
                        ] ?: return BoundType.STAR_PROJECTION
                        BoundTypeImpl(
                          TypeVariableLabel(returnTypeVariable),
                          returnTypeVariable.typeParameters
                        )
                    }
                    typeVariable != null && isImplicitReceiver ->
                        BoundTypeImpl(
                          TypeVariableLabel(typeVariable),
                          emptyList()
                        )
                    contextBoundType?.isReferenceToClass == true ->
                        contextBoundType.typeParameters.getOrNull(target.index)?.boundType

                    // `this`, `super`, or constructor call cases
                    containingDeclaration == call?.candidateDescriptor.safeAs<ConstructorDescriptor>()?.constructedClass -> {
                        val returnTypeVariable = inferenceContext.typeElementToTypeVariable[
                                call?.call?.typeArguments?.getOrNull(target.index)?.typeReference?.typeElement
                                    ?: return BoundType.STAR_PROJECTION
                        ] ?: return BoundType.STAR_PROJECTION
                        BoundTypeImpl(
                          TypeVariableLabel(returnTypeVariable),
                          returnTypeVariable.typeParameters
                        )
                    }
                    else -> BoundTypeImpl(
                      TypeParameterLabel(target),
                      emptyList()
                    )
                }
            }
            else -> null
        }
    }
}


