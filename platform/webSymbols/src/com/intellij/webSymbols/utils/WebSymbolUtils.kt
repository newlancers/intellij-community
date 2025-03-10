// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:JvmName("WebSymbolUtils")

package com.intellij.webSymbols.utils

import com.intellij.navigation.EmptyNavigatable
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.navigation.NavigationTarget
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.containers.Stack
import com.intellij.webSymbols.*
import com.intellij.webSymbols.WebSymbolReferenceProblem.ProblemKind
import com.intellij.webSymbols.impl.sortSymbolsByPriority
import java.util.*
import javax.swing.Icon
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

val Project.psiModificationCount get() = PsiModificationTracker.getInstance(this).modificationCount

@OptIn(ExperimentalContracts::class)
inline fun <T : Any, P : Any> T.applyIfNotNull(param: P?, block: T.(P) -> T): T {
  contract {
    callsInPlace(block, InvocationKind.EXACTLY_ONCE)
  }
  return if (param != null)
    block(this, param)
  else this
}

fun List<WebSymbol>.hasOnlyExtensions(): Boolean =
  all { it.extension }

fun List<WebSymbol>.asSingleSymbol(): WebSymbol? =
  if (isEmpty())
    null
  else if (size == 1)
    this[0]
  else {
    val first = this[0]
    WebSymbolMatch.create(first.name, listOf(WebSymbol.NameSegment(0, first.name.length, sortSymbolsByPriority())),
                          first.namespace, first.kind, first.origin)
  }

fun WebSymbol.withMatchedName(matchedName: String) =
  if (matchedName != name) {
    WebSymbolMatch.create(matchedName, listOf(WebSymbol.NameSegment(0, matchedName.length, this)), namespace, kind, origin)
  }
  else this

fun WebSymbol.unwrapMatchedSymbols(): Sequence<WebSymbol> =
  Sequence {
    object : Iterator<WebSymbol> {
      private var next: WebSymbol? = null
      val fifo = LinkedList<WebSymbol>()

      init {
        fifo.addLast(this@unwrapMatchedSymbols)
        advance()
      }

      private fun advance() {
        while (fifo.isNotEmpty()) {
          val symbol = fifo.removeFirst()
          if (symbol is WebSymbolMatch) {
            symbol.nameSegments.forEach {
              fifo.addAll(it.symbols)
            }
          }
          else {
            next = symbol
            return
          }
        }
        next = null
      }

      override fun hasNext(): Boolean =
        next != null

      override fun next(): WebSymbol =
        next!!.also { advance() }
    }
  }

fun WebSymbol.match(nameToMatch: String,
                    context: Stack<WebSymbolsContainer>,
                    params: WebSymbolsNameMatchQueryParams): List<WebSymbol> {
  pattern?.let { pattern ->
    context.push(this)
    try {
      return pattern
        .match(this, context, nameToMatch, params)
        .mapNotNull { matchResult ->
          if ((matchResult.segments.lastOrNull()?.end ?: 0) < nameToMatch.length) {
            null
          }
          else {
            WebSymbolMatch.create(nameToMatch, matchResult.segments,
                                  this.namespace, kind, this.origin)
          }
        }
    }
    finally {
      context.pop()
    }
  }

  val registry = params.registry
  val queryNames = registry.namesProvider.getNames(this.namespace, this.kind,
                                                   nameToMatch, WebSymbolNamesProvider.Target.NAMES_QUERY)
  val symbolNames = registry.namesProvider.getNames(this.namespace, this.kind, this.matchedName,
                                                    WebSymbolNamesProvider.Target.NAMES_MAP_STORAGE).toSet()
  return if (queryNames.any { symbolNames.contains(it) }) {
    listOf(this.withMatchedName(nameToMatch))
  }
  else {
    emptyList()
  }
}

fun WebSymbol.NameSegment.getProblemKind(): ProblemKind? =
  when (problem) {
    WebSymbol.MatchProblem.MISSING_REQUIRED_PART -> ProblemKind.MissingRequiredPart
    WebSymbol.MatchProblem.UNKNOWN_ITEM ->
      if (start == end)
        ProblemKind.MissingRequiredPart
      else
        ProblemKind.UnknownSymbol
    WebSymbol.MatchProblem.DUPLICATE -> ProblemKind.DuplicatedPart
    null -> null
  }

val WebSymbol.hideFromCompletion
  get() =
    properties[WebSymbol.PROP_HIDE_FROM_COMPLETION] == true

fun List<WebSymbol.NameSegment>.withOffset(offset: Int): List<WebSymbol.NameSegment> =
  if (offset != 0) map { it.withOffset(offset) }
  else this

fun Sequence<WebSymbol.AttributeValue?>.merge(): WebSymbol.AttributeValue? {
  var kind: WebSymbol.AttributeValueKind? = null
  var type: WebSymbol.AttributeValueType? = null
  var required: Boolean? = null
  var default: String? = null
  var langType: Any? = null

  for (value in this) {
    if (value == null) continue
    if (kind == null) {
      kind = value.kind
    }
    if (type == null) {
      type = value.type
    }
    if (required == null) {
      required = value.required
    }
    if (default == null) {
      default = value.default
    }
    if (langType == null) {
      langType = value.langType
    }
    if (kind != null && type != null && required != null) {
      break
    }
  }
  return if (kind != null
             || type != null
             || required != null
             || langType != null
             || default != null)
    WebSymbolHtmlAttributeValueData(kind, type, required, default, langType)
  else null
}

fun NavigationTarget.createPsiRangeNavigationItem(element: PsiElement, offsetWithinElement: Int): Navigatable {
  val vf = element.containingFile.virtualFile
           ?: return EmptyNavigatable.INSTANCE
  val targetPresentation = this.targetPresentation
  val descriptor = OpenFileDescriptor(
    element.project, vf, element.textRange.startOffset + offsetWithinElement)

  return object : NavigationItem, ItemPresentation {
    override fun navigate(requestFocus: Boolean) {
      descriptor.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = descriptor.canNavigate()

    override fun canNavigateToSource(): Boolean = descriptor.canNavigateToSource()

    override fun getName(): String = targetPresentation.presentableText

    override fun getPresentation(): ItemPresentation = this

    override fun getPresentableText(): String = targetPresentation.presentableText

    override fun getIcon(unused: Boolean): Icon? = targetPresentation.icon

    override fun getLocationString(): String? {
      val container = targetPresentation.containerText
      val location = targetPresentation.locationText
      return if (container != null || location != null) {
        sequenceOf(container, location).joinToString(", ", "(", ")")
      }
      else null
    }

    override fun toString(): String =
      descriptor.file.name + " [" + descriptor.offset + "]"

  }
}