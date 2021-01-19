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
package alix.web;


public enum Distance implements Option
{
  none("Occurrences", "m11") {
    @Override
    public double score(final double m11, final double m10, final double m01, final double m00)
    {
      return m11;
    }
  },
  jaccard("Jaccard", "m11 / (m10 + m01 + m11)") {
    @Override
    public double score(final double m11, final double m10, final double m01, final double m00)
    {
      return m11 / (m01 + m10 + m11);
    }
  },
  dice("Dice", "2*m11 / (m10² + m01²)") {
    @Override
    public double score(final double m11, final double m10, final double m01, final double m00)
    {
      return 2 * m11 / (m10 * m10 + m01 * m01);
    }
  }
  ;
  
  abstract public double score(final double m11, final double m10, final double m01, final double m00);

  final public String label;
  public String label() { return label; }
  final public String hint;
  public String hint() { return hint; }
  private Distance(final String label, final String hint)
  {
    this.label = label;
    this.hint = hint;
  }
  
  


}