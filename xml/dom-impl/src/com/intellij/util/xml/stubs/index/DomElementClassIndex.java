/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.util.xml.stubs.index;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWithId;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.stubs.StringStubIndexExtension;
import com.intellij.psi.stubs.StubIndex;
import com.intellij.psi.stubs.StubIndexKey;
import com.intellij.util.CommonProcessors;
import com.intellij.util.indexing.IdFilter;
import com.intellij.util.xml.DomElement;
import com.intellij.util.xml.DomFileElement;
import org.jetbrains.annotations.NotNull;

/**
 * @see com.intellij.util.xml.StubbedOccurrence
 * @since 13
 */
public class DomElementClassIndex extends StringStubIndexExtension<PsiFile> {

  public static final StubIndexKey<String, PsiFile> KEY = StubIndexKey.createIndexKey("dom.elementClass");

  private static final DomElementClassIndex ourInstance = new DomElementClassIndex();

  public static DomElementClassIndex getInstance() {
    return ourInstance;
  }

  public boolean hasStubElementsOfType(final DomFileElement domFileElement,
                                       final Class<? extends DomElement> clazz) {
    final VirtualFile file = domFileElement.getFile().getVirtualFile();
    if (!(file instanceof VirtualFileWithId)) return false;

    final String clazzName = clazz.getName();
    final int virtualFileId = ((VirtualFileWithId)file).getId();

    CommonProcessors.FindFirstProcessor<? super PsiFile> processor =
      new CommonProcessors.FindFirstProcessor<PsiFile>();
    StubIndex.getInstance().process(KEY, clazzName,
                                    domFileElement.getFile().getProject(),
                                    GlobalSearchScope.fileScope(domFileElement.getFile()),
                                    new IdFilter() {
                                      @Override
                                      public boolean containsFileId(int id) {
                                        return id == virtualFileId;
                                      }
                                    },
                                    processor
    );

    return processor.isFound();
  }

  @NotNull
  @Override
  public StubIndexKey<String, PsiFile> getKey() {
    return KEY;
  }

  @Override
  public int getVersion() {
    return 0;
  }
}