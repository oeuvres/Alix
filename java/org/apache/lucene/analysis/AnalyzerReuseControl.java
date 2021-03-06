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
package org.apache.lucene.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Analyzer.ReuseStrategy;

/**
 * This analyzer wrap another and allow to force 
 * its {@link ReuseStrategy}.
 * Most analyzers do not implement the constructor 
 * allowing to modify the reuse strategy.
 * This could be needed in some indexing context 
 * where the default strategy is confused.
 */
public class AnalyzerReuseControl extends Analyzer
{
  Analyzer analyzer;
  
  public AnalyzerReuseControl(Analyzer analyzer, ReuseStrategy reuseStrategy)
  {
    super(reuseStrategy);
    this.analyzer = analyzer;
  }

  
  @Override
  protected TokenStreamComponents createComponents(String fieldName)
  {
    return analyzer.createComponents(fieldName);
  }

  @Override
  protected TokenStream normalize(String fieldName, TokenStream in)
  {
    return analyzer.normalize(fieldName, in);
  }
}
