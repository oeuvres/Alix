/*
 * Copyright 2008 Pierre DITTGEN <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix, A Lucene Indexer for XML documents
 * Alix is a tool to index XML text documents
 * in Lucene https://lucene.apache.org/core/
 * including linguistic expertise for French.
 * Project has been started in 2008 under the javacrim project (sf.net)
 * for a java course at Inalco  http://www.er-tim.fr/
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

import alix.fr.Tag;

/**
 * A token Filter to plug after a lemmatizer filter. Positions of striped tokens
 * are deleted. 
 * 
 * @author fred
 *
 */
public class TokenLemCloud extends TokenFilter
{
  // no sense to record stats here if filter is not behind a caching filer
  // exhausting tokens before the index is writed.
  /** The term provided by the Tokenizer */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A linguistic category as a short number, from Tag */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** A lemma when possible */
  private final CharsLemAtt lemAtt = addAttribute(CharsLemAtt.class); // ? needs to be declared in the tokenizer

  public TokenLemCloud(TokenStream in)
  {
    super(in);
  }

  protected boolean accept() throws IOException
  {
    int tag = flagsAtt.getFlags();
    // filter some non semantic token
    if (Tag.isPun(tag) || Tag.isNum(tag)) return false;
    // filter some names
    if (Tag.isName(tag)) {
      if (termAtt.length() < 3) return false;
      // filter first names ?
      return true;
    }
    // replace term by lemma for adjectives and verbs
    if (Tag.isAdj(tag) || Tag.isVerb(tag) || Tag.isSub(tag))
      if (lemAtt.length() != 0) termAtt.setEmpty().append(lemAtt);
    return true;
  }

  @Override
  public final boolean incrementToken() throws IOException
  {
    while (input.incrementToken()) {
      if (accept()) return true;
    }
    return false;
  }

  @Override
  public void reset() throws IOException
  {
    super.reset();
  }

  @Override
  public void end() throws IOException
  {
    super.end();
  }

}
