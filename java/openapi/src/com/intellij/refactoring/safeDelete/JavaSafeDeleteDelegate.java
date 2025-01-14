// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.refactoring.safeDelete;

import com.intellij.lang.LanguageExtension;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiReference;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.SlowOperations;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The extension helps to encapsulate a custom logic for "Safe delete" refactoring.
 */
public interface JavaSafeDeleteDelegate {
  LanguageExtension<JavaSafeDeleteDelegate> EP =
    new LanguageExtension<>("com.intellij.refactoring.safeDelete.JavaSafeDeleteDelegate");

  /**
   * Method is used to create usage information according to the input <code>reference</code> to the method
   * and <code>parameter</code> that belongs to the <code>method</code>.
   * The result will be filled into the list of the usages.
   * <p> The method should be called under read action.
   *     A caller should be also aware that an implementation may use an index access,
   *     so using the method in EDT may lead to get the exception from {@link SlowOperations#assertSlowOperationsAreAllowed()}
   */
  void createUsageInfoForParameter(@NotNull PsiReference reference,
                                   @NotNull List<UsageInfo> usages,
                                   @NotNull PsiNamedElement parameter,
                                   int paramIdx, 
                                   boolean isVararg);
}
