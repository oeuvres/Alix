/*
 * Copyright 2009 Pierre DITTGEN <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 *
 * Alix, A Lucene Indexer for XML documents.
 * Alix is a tool to index and search XML text documents
 * in Lucene https://lucene.apache.org/core/
 * including linguistic expertness for French.
 * Alix has been started in 2009 under the javacrim project (sf.net)
 * for a java course at Inalco  http://www.er-tim.fr/
 * Alix continues the concepts of SDX under a non viral license.
 * SDX: Documentary System in XML.
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

import java.util.Arrays;
import java.util.HashMap;

import alix.fr.Tag;

/**
 * A dictionary optimized for lucene analysis with a custom
 */
public class CharsDic
{
  private HashMap<CharsAtt, Entry> tokens = new HashMap<CharsAtt, Entry>();

  public int inc(final CharsAtt token)
  {
    return inc(token, 0);
  }

  public int inc(final CharsAtt token, final int tag)
  {
    Entry entry = tokens.get(token);
    if (entry == null) {
      CharsAtt key = new CharsAtt(token);
      entry = new Entry(key, tag);
      tokens.put(key, entry);
    }
    return ++entry.count;
  }

  public class Entry implements Comparable<Entry>
  {
    private int count;
    private final CharsAtt key;
    private final int tag;

    public Entry(final CharsAtt key, final int tag)
    {
      this.key = key;
      this.tag = tag;
    }

    public CharsAtt key()
    {
      return key;
    }

    public int tag()
    {
      return tag;
    }

    public int count()
    {
      return count;
    }

    /**
     * Default comparator for chain informations,
     */
    @Override
    public int compareTo(Entry o)
    {
      return o.count - count;
    }

    @Override
    public String toString()
    {
      StringBuilder sb = new StringBuilder();
      sb.append(key).append(" (").append(Tag.label(tag)).append("): ").append(count);
      return sb.toString();
    }
  }

  public Entry[] sorted()
  {
    Entry[] entries = new Entry[tokens.size()];
    tokens.values().toArray(entries);
    Arrays.sort(entries);
    return entries;
  }
}
