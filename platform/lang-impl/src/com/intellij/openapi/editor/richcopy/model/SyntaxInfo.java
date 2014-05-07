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
package com.intellij.openapi.editor.richcopy.model;

import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * @author Denis Zhdanov
 * @since 3/25/13 1:19 PM
 */
public class SyntaxInfo {
  private final int myOutputInfoCount;
  private final byte[] myOutputInfosSerialized;
  @NotNull private final ColorRegistry    myColorRegistry;
  @NotNull private final FontNameRegistry myFontNameRegistry;

  private final int myDefaultForeground;
  private final int myDefaultBackground;
  private final int mySingleFontSize;

  private SyntaxInfo(int outputInfoCount,
                     byte[] outputInfosSerialized,
                    int defaultForeground,
                    int defaultBackground,
                    int singleFontSize,
                    @NotNull FontNameRegistry fontNameRegistry,
                    @NotNull ColorRegistry colorRegistry)
  {
    myOutputInfoCount = outputInfoCount;
    myOutputInfosSerialized = outputInfosSerialized;
    myDefaultForeground = defaultForeground;
    myDefaultBackground = defaultBackground;
    mySingleFontSize = singleFontSize;
    myFontNameRegistry = fontNameRegistry;
    myColorRegistry = colorRegistry;
  }

  @NotNull
  public ColorRegistry getColorRegistry() {
    return myColorRegistry;
  }

  @NotNull
  public FontNameRegistry getFontNameRegistry() {
    return myFontNameRegistry;
  }

  public int getDefaultForeground() {
    return myDefaultForeground;
  }

  public int getDefaultBackground() {
    return myDefaultBackground;
  }

  /**
   * @return    positive value if all tokens have the same font size (returned value);
   *            non-positive value otherwise
   */
  public int getSingleFontSize() {
    return mySingleFontSize;
  }

  @Override
  public String toString() {
    final StringBuilder b = new StringBuilder();
    b.append("default colors: foreground=").append(myDefaultForeground).append(", background=").append(myDefaultBackground).append("; output infos: ");
    boolean first = true;
    MarkupIterator it = new MarkupIterator();
    try {
      while(it.hasNext()) {
        if (first) {
          b.append(',');
        }
        it.processNext(new MarkupHandler() {
          @Override
          public void handleText(int startOffset, int endOffset) throws Exception {
            b.append("text(").append(startOffset).append(",").append(endOffset).append(")");
          }

          @Override
          public void handleForeground(int foregroundId) throws Exception {
            b.append("foreground(").append(foregroundId).append(")");
          }

          @Override
          public void handleBackground(int backgroundId) throws Exception {
            b.append("background(").append(backgroundId).append(")");
          }

          @Override
          public void handleFont(int fontNameId) throws Exception {
            b.append("font(").append(fontNameId).append(")");
          }

          @Override
          public void handleStyle(int style) throws Exception {
            b.append("style(").append(style).append(")");
          }
        });
        first = false;
      }
      return b.toString();
    }
    finally {
      it.dispose();
    }
  }

  public static class Builder {
    private final ColorRegistry myColorRegistry = new ColorRegistry();
    private final FontNameRegistry myFontNameRegistry = new FontNameRegistry();
    private final int myDefaultForeground;
    private final int myDefaultBackground;
    private final int myFontSize;
    private final ByteArrayOutputStream myStream = new ByteArrayOutputStream();
    private final OutputInfoSerializer.OutputStream myOutputInfoStream;
    private int myOutputInfoCount;

    public Builder(Color defaultForeground, Color defaultBackground, int fontSize) {
      myDefaultForeground = myColorRegistry.getId(defaultForeground);
      myDefaultBackground = myColorRegistry.getId(defaultBackground);
      myFontSize = fontSize;
      try {
        myOutputInfoStream = new OutputInfoSerializer.OutputStream(myStream);
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    public void addFontStyle(int fontStyle) {
      try {
        myOutputInfoStream.handleStyle(fontStyle);
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
      myOutputInfoCount++;
    }

    public void addFontFamilyName(String fontFamilyName) {
      try {
        myOutputInfoStream.handleFont(myFontNameRegistry.getId(fontFamilyName));
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
      myOutputInfoCount++;
    }

    public void addForeground(Color foreground) {
      try {
        myOutputInfoStream.handleForeground(myColorRegistry.getId(foreground));
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
      myOutputInfoCount++;
    }

    public void addBackground(Color background) {
      try {
        myOutputInfoStream.handleBackground(myColorRegistry.getId(background));
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
      myOutputInfoCount++;
    }

    public void addText(int startOffset, int endOffset) {
      try {
        myOutputInfoStream.handleText(startOffset, endOffset);
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
      myOutputInfoCount++;
    }

    public SyntaxInfo build() {
      myColorRegistry.seal();
      myFontNameRegistry.seal();
      try {
        myOutputInfoStream.close();
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
      return new SyntaxInfo(myOutputInfoCount, myStream.toByteArray(), myDefaultForeground, myDefaultBackground, myFontSize, myFontNameRegistry, myColorRegistry);
    }
  }

  public class MarkupIterator {
    private int pos;
    private final OutputInfoSerializer.InputStream myOutputInfoStream;

    public MarkupIterator() {
      try {
        myOutputInfoStream = new OutputInfoSerializer.InputStream(new ByteArrayInputStream(myOutputInfosSerialized));
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    public boolean hasNext() {
      return pos < myOutputInfoCount;
    }

    public void processNext(MarkupHandler handler) {
      if (!hasNext()) {
        throw new IllegalStateException();
      }
      pos++;
      try {
        myOutputInfoStream.read(handler);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    public void dispose() {
      try {
        myOutputInfoStream.close();
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}