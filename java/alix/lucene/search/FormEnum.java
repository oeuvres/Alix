/*
 * Alix, A Lucene Indexer for XML documents.
 * 
 * Copyright 2016 Frédéric Glorieux <frederic.glorieux@fictif.org>
 * Copyright 2009 Pierre Dittgen <pierre@dittgen.org> 
 *                Frédéric Glorieux <frederic.glorieux@fictif.org>
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
package alix.lucene.search;

import java.text.CollationKey;
import java.text.Collator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.UnicodeUtil;

import alix.fr.Tag;
import alix.fr.Tag.TagFilter;
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.util.TopArray;
import alix.web.Distrib.Scorer;
import alix.web.MI;

/**
 * This object is build to collect list of forms with 
 * useful stats for semantic interpretations and score calculations.
 * This class is a wrapper around different pre-calculated arrays,
 * and is also used to record search specific counts like freqs and hits.
 * 
 * @author glorieux-f
 */
public class FormEnum {
  /** An array of formId in the order we want to iterate on, should be set before iteration */
  private int[] sorter;
  /** Field dictionary */
  final private BytesRefHash formDic;
  /** Count of docs by formId */
  protected int[] formDocs;
  /** Count of occurrences by formId, used for counts */
  protected long[] formOccs;
  /** A docId by term used as a cover (example: metas for books or authors) */
  final private int[] formCover;
  /** An optional tag for each search (relevant for textField) */
  final private int[] formTag;
  /** Count of occurrences for the part explored */
  public long partOccs;
  /** Number of documents matched, index by formId */
  protected int[] hits;
  /** Number of occurrences matched, index by formId */
  protected long[] freqs;
  /** Scores, index by formId */
  protected double[] scores;
  /** Cursor, to iterate in the sorter */
  private int cursor = -1;
  /** Current formId, set by next */
  private int formId = -1; // break if no next
  /** used to read in the dic */
  BytesRef bytes = new BytesRef();
  /** Limit for this iterator */
  public int limit;
  /** Optional, for a co-occurrence search, count of occurrences to capture on the left */
  public int left;
  /** Optional, for a co-occurrence search, count of occurrences to capture on the right */
  public int right;
  /** Optional, for a co-occurrence search, pivot words */
  public String[] search;
  /** Optional, a set of documents to limit occurrences collect */
  public BitSet filter;
  /** Optional, a set of tags to filter form to collect */
  public TagFilter tags;
  /** Reverse order of sorting */
  public boolean reverse;
  /** Optional, a sort algorithm to select specific words according a norm (ex: compare formOccs / freqs) */
  public Scorer scorer;
  /** Optional, a sort algorithm for coocs */
  public MI mi;

  
  /** Build a form iterator from a text field */
  public FormEnum(final FieldText field)
  {
    this.formDic = field.formDic;
    this.formDocs = field.formAllDocs;
    this.formOccs = field.formAllOccs;
    this.formCover = null;
    this.formTag = field.formTag;
  }

  /** Build an iterator from a facet field */
  public FormEnum(final FieldFacet field)
  {
    this.formDic = field.facetDic;
    this.formDocs = field.facetDocs;
    this.formOccs = field.facetOccs;
    this.formCover = field.facetCover;
    this.formTag = null;
  }
  
  /**
   * Set a vector for scores, and prepare the sorter
   */
  public void scores(final double[] scores, final int limit, boolean reverse)
  {
    this.scores = scores;
    TopArray top;
    int flags = TopArray.NO_ZERO;
    if (reverse) flags |= TopArray.REVERSE;
    if (limit < 1) top = new TopArray(scores, flags); // all search
    else top = new TopArray(limit, scores, flags);
    this.sorter(top.toArray());
  }

  /**
   * Set the sorted vector of ids
   */
  public void sorter(final int[] sorter) {
    this.sorter = sorter;
    this.limit = sorter.length;
    cursor = -1;
  }
  
  /**
   * Limit enumeration
   * @return
   */
  public int limit()
  {
    return limit;
  }
  /**
   * Count of occurrences for this search
   * @return
   */
  public long partOccs()
  {
    return partOccs;
  }
  /**
   * Global number of occurrences for this term
   * 
   * @return
   */
  public long formOccs(final int formId)
  {
    return formOccs[formId];
  }

  /**
   * Global number of occurrences for this term
   * 
   * @return
   */
  public long formOccs()
  {
    return formOccs[formId];
  }

  
  /**
   * Get the total count of documents relevant for the current term.
   * @return
   */
  public int formDocs()
  {
    return formDocs[formId];
  }
  
  /**
   * Get the total count of documents relevant for the current term.
   * @return
   */
  public int formDocs(final int formId)
  {
    return formDocs[formId];
  }

  /**
   * Get the count of matching occureences
   * @return
   */
  public long freq()
  {
    return freqs[formId];
  }

  /**
   * Get the count of matching occureences
   * @return
   */
  public long freq(final int formId)
  {
    return freqs[formId];
  }

  /**
   * Get the count of matched documents for the current term.
   * @return
   */
  public int hits()
  {
    return hits[formId];
  }

  /**
   * Get the count of matched documents for the current term.
   * @return
   */
  public int hits(final int formId)
  {
    return hits[formId];
  }

  /**
   * There are search left
   * @return
   */
  public boolean hasNext()
  {
    
    return (cursor < limit - 1);
  }

  /**
   * Advance the cursor to next element
   */
  public void next()
  {
    cursor++;
    formId = sorter[cursor];
  }

  /**
   * Reset the internal cursor if we want to rplay the list.
   */
  public void reset()
  {
    cursor = -1;
    formId = -1;
  }

  public void first()
  {
    cursor = 0;
    formId = sorter[cursor];
  }

  public void last()
  {
    cursor = sorter.length - 1;
    formId = sorter[cursor];
  }


  /**
   * Current term, get the formId for the global dic.
   */
  public int formId()
  {
    return formId;
  }

  /**
   * Populate reusable bytes with current term
   * 
   * @param ref
   */
  public void form(BytesRef bytes)
  {
    formDic.get(formId, bytes);
  }
  
  /**
   * Get the current term as a String     * 
   * @return
   */
  public String form()
  {
    formDic.get(formId, bytes);
    return bytes.utf8ToString();
  }

  

  /**
   * Copy the current term in a reusable char array  * 
   * @return
   */
  public CharsAtt form(CharsAtt term)
  {
    formDic.get(formId, bytes);
    // ensure limit of the char array
    int length = bytes.length;
    char[] chars = term.resizeBuffer(length);
    final int len = UnicodeUtil.UTF8toUTF16(bytes.bytes, bytes.offset, length, chars);
    term.setLength(len);
    return term;
  }

  /**
   * Value used for sorting for current term.
   * 
   * @return
   */
  public double score()
  {
    return scores[formId];
  }
  
  /**
   * Cover docid for current term
   * @return
   */
  public int cover()
  {
    return formCover[formId];
  }

  /**
   * An int tag for term if it’s coming from a text field.
   * @return
   */
  public int tag()
  {
    return formTag[formId];
  }

  /**
   * Returns an array of formId in alphabetic order for all search
   * of dictionary. 
   *
   * @param hashDic
   * @return
   */
  static public int[] sortAlpha(BytesRefHash hashDic)
  {
    Collator collator = Collator.getInstance(Locale.FRANCE);
    collator.setStrength(Collator.TERTIARY);
    collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
    int size = hashDic.size();
    BytesRef bytes = new BytesRef();
    Entry[] sorter = new Entry[size];
    for (int formId = 0; formId < size; formId++) {
      hashDic.get(formId, bytes);
      sorter[formId] = new Entry(formId, collator.getCollationKey(bytes.utf8ToString()));
    }
    Arrays.sort(sorter,  new Comparator<Entry>() {
        @Override
        public int compare(Entry arg0, Entry arg1)
        {
          return arg0.key.compareTo(arg1.key);
        }
      }
    );
    int[] terms = new int[size];
    for (int i = 0, max = size; i < max; i++) {
      terms[i] = sorter[i].formId;
    }
    return terms;
  }
  
  static private class Entry
  {
    final CollationKey key;
    final int formId;
    Entry (final int formId, final CollationKey key) {
      this.key = key;
      this.formId = formId;
    }
  }

  /**
   * For the current term, get a number set by {@link #setNos(int[])}.
   * @return
   */
  /* Very specific to some fields type
  public int n()
  {
    return nos[formId];
  }
  */

  @Override
  public String toString()
  {
    int limit = 100;
    StringBuilder sb = new StringBuilder();
    int[] sorter = this.sorter;
    if (sorter == null) {
      limit = Math.min(formDic.size(), limit);
      sb.append("No sorter, limit="+formDic.size()+" limit=" + limit + "\n");
      sorter = new int[limit];
      for (int i = 0; i < limit; i++) sorter[i] = i;
    }
    boolean hasScore = (scores != null);
    boolean hasTag = (formTag != null);
    boolean hasHits = (hits != null);
    boolean hasDocs = (formDocs != null);
    boolean hasToccs = (formOccs != null);
    boolean hasOccs = (freqs != null);
    for(int pos = 0; pos < limit; pos++) {
      int formId = sorter[pos];
      formDic.get(formId, bytes);
      sb.append((pos+1) + ". [" + formId + "] " + bytes.utf8ToString());
      if (hasTag) sb.append( " "+Tag.label(formTag[formId]));
      if (hasScore) sb.append( " score=" + scores[formId]);
      
      if (hasToccs && hasOccs) sb.append(" freqs="+freqs[formId]+"/"+formOccs[formId]);
      else if(hasToccs) sb.append(" freqs="+formOccs[formId]);
      
      if (hasHits && hasDocs) sb.append(" hits="+hits[formId]+"/"+formDocs[formId]);
      else if(hasDocs) sb.append(" docs="+formDocs[formId]);
      else if(hasHits) sb.append(" hits="+hits[formId]);
      sb.append("\n");
    }
    return sb.toString();
  }

}
