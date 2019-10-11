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
package alix.grep.query;

import alix.fr.Tag;
import alix.util.Occ;

public class TestOrthTagPrefix extends TestTerm
{
  int prefix;

  public TestOrthTagPrefix(final String term, final int prefix) {
    super(term);
    this.prefix = prefix;
  }

  @Override
  public boolean test(Occ occ)
  {
    if (occ.tag().group() != prefix)
      return false;
    return chain.glob(occ.orth());
  }

  @Override
  public String label()
  {
    return chain + ":" + Tag.label(prefix);
  }

}
