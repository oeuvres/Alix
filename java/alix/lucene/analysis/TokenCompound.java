/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix is a java library to index and search XML text documents
 * with Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French,
 * available under Apache license.
 * 
 * Alix has been started in 2009 under the javacrim project
 * https://sf.net/projects/javacrim/
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under another licence
 * «Système de Documentation XML»
 * 2000-2010  Ministère de la culture et de la communication (France), AJLSM.
 * http://savannah.nongnu.org/projects/sdx/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package alix.lucene.analysis;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedList;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;

import alix.lucene.analysis.tokenattributes.CharsAtt;

/**
 * Plug behind TokenLem
 * @author fred
 *
 */
public class TokenCompound extends TokenFilter
{
  /**
   * French, « vois-tu » hyphen is breakable before these words, exc: arc-en-ciel
   */
  public static final HashSet<CharsAtt> PARTICLES = new HashSet<CharsAtt>();
  static {
    for (String w : new String[] { "d'", "D'", "de", "De", "du", "Du", "l'", "L'", "le", "Le", "la", "La", "von", "Von" })
      PARTICLES.add(new CharsAtt(w));
  }
  /** Increment position of a token */
  private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);
  /** Position length of a token */
  private final PositionLengthAttribute posLenAtt = addAttribute(PositionLengthAttribute.class);
  /** Current char offset */
  private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
  /** Current Flags */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** Current term */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A lemma when possible */
  // private final CharsLemAtt lemAtt = addAttribute(CharsLemAtt.class);
  /** A stack of sates  */
  private LinkedList<State> stack = new LinkedList<State>();
  /** A term used to concat names */
  private CharsAtt name = new CharsAtt();

  /** Number of tokens to cache for compounds */
  final int size;
  public TokenCompound(TokenStream input, final int size)
  {
    super(input);
    this.size = size;
  }

  @Override
  public boolean incrementToken() throws IOException
  {
    if (!stack.isEmpty()) {
      restoreState(stack.removeLast());
      return true;
    }
    if (!input.incrementToken()) {
      return false;
    }
    CharTermAttribute term = termAtt;
    PositionIncrementAttribute posInc = posIncAtt;
    OffsetAttribute offset = offsetAtt;
    FlagsAttribute flags = flagsAtt;
    final int tag = flags.getFlags();
    
    // tet
    
    /*
    // test compound names : NAME (particle|NAME)* NAME
    if (Tag.isName(tag)) {
      final int startOffset = offsetAtt.startOffset();
      int endOffset = offsetAtt.endOffset();
      int pos = posInc.getPositionIncrement(); 
      name.copy(term);
      int lastlen = name.length();
      boolean notlast;
      while ((notlast = input.incrementToken())) {
        if (Tag.isName(flags.getFlags())) {
          endOffset = offset.endOffset();
          if (name.charAt(name.length()-1) != '\'') name.append(' ');
          name.append(term);
          lastlen = name.length(); // store the last length of name
          stack.clear(); // empty the stored paticles
          pos += posInc.getPositionIncrement(); // incremen tposition
          continue;
        }
        // test if it is a particle, but store it, avoid [Europe de l']atome
        if (PARTICLES.contains(term)) {
          stack.addFirst(captureState());
          name.append(' ').append(term);
          pos += posInc.getPositionIncrement();
          continue;
        }
        break;
      }
      // are there particles to exhaust ?
      if (!stack.isEmpty()) {
        pos = pos - stack.size();
        name.setLength(lastlen);
      }
      if (notlast) stack.addFirst(captureState());
      offsetAtt.setOffset(startOffset, endOffset);
      posIncAtt.setPositionIncrement(pos);
      posLenAtt.setPositionLength(pos);
      // get tag
      NameEntry entry = FrDics.name(name);
      if (entry == null) {
        flagsAtt.setFlags(Tag.NAME);
        term.setEmpty().append(name);
      }
      else {
        flagsAtt.setFlags(entry.tag);
        if (entry.orth != null) term.setEmpty().append(entry.orth);
        else term.setEmpty().append(name);
      }
      return true;
    }
    */
    
    /*
    // compounds start by lem, ex : faire comme si
    else if (lem.length() != 0) {
      if (!FrDics.compound1(lem)) return true;
    }
    else {
      if (!FrDics.compound1(term)) return true;
    }
    
    
    while (input.incrementToken()) {
      if (!cache.isFull()) {
        State state = captureState();
        cache.push(state);
      }
      if cha
      if (accept()) {
        if (skippedPositions != 0) {
          posIncrAtt.setPositionIncrement(posIncrAtt.getPositionIncrement() + skippedPositions);
        }
        return true;
      }
      skippedPositions += posIncrAtt.getPositionIncrement();
    }
    // reached EOS -- return false
     */
    return true;
  }
  @Override
  public void reset() throws IOException {
    super.reset();
  }

  @Override
  public void end() throws IOException {
    super.end();
  }
}
