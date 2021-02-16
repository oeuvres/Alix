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

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import alix.fr.Tag;
import alix.lucene.analysis.tokenattributes.CharsLemAtt;

/**
 * A token Filter to plug after a Lemmatizer.
 * Merge flows of forms, lemmas and pos in same line of search.
 * 
 * @author fred
 *
 */
public class FlagAllFilter extends TokenFilter
{
  /** The term provided by the Tokenizer */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A linguistic category as a short number, from Tag */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** A lemma when possible */
  private final CharsLemAtt charsLemAtt = addAttribute(CharsLemAtt.class); 
  /** A lemma when possible */
  private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class); 
  /** Flag to say a tag has to be send */
  int tag;
  /** Flag to say */
  boolean lem;
  
  /**
   * 
   * @param in
   */
  public FlagAllFilter(TokenStream in)
  {
    super(in);
  }
  @Override
  public boolean incrementToken() throws IOException
  {
    CharTermAttribute term = this.termAtt;
    // purge things
    if (lem) {
      posIncAtt.setPositionIncrement(0);
      term.setEmpty().append(charsLemAtt);
      lem = false;
      return true;
    }
    if (tag != Tag.NULL.flag) {
      posIncAtt.setPositionIncrement(0);
      term.setEmpty().append(Tag.label(tag));
      tag = Tag.NULL.flag;
      return true;
    }
    
    // end of stream
    if (!input.incrementToken()) return false;
    tag = flagsAtt.getFlags();
    if (this.charsLemAtt.length() != 0) lem = true;
    return true;
  }

}
