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
package alix.lucene.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.SparseFixedBitSet;

import alix.fr.Tag;
import alix.fr.Tag.TagFilter;
import alix.lucene.analysis.FrDics;
import alix.lucene.analysis.FrDics.LexEntry;
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.util.Char;
import alix.util.TopArray;

/**
 * <p>
 * An object recording different stats for a lucene text field.
 * Is stable according to a state of the index, could be cached.
 * Record all counts useful for stats.
 * For performances, all fields of the class are visible, so it is unsafe.
 * </p>
 * <p>
 * Provide slices of stats for Terms as s sorted Iterator
 * </p>
 * @author fred
 *
 */
public class FieldText
{
  /** The reader from which to get freqs */
  final DirectoryReader reader;
  /** Name of the indexed field */
  public final String fieldName;
  /** Number of different search */
  public final int size;
  /** Global number of occurrences for this field */
  public final long occsAll;
  /** Global number of docs relevant for this field */
  public final int docsAll;
  /** Count of occurrences by document for this field (for stats) */
  public final int[] docOccs;
  /** Store and populate the search and get the id */
  public final BytesRefHash formDic;
  /** Count of docs by formId */
  public int[] formAllDocs;
  /** Count of occurrences by formId */
  public final long[] formAllOccs;
  /** A tag by formId (maybe used for filtering) */
  public int[] formTag;
  /** Stop words known as a bitSet, according to formId (growable java.util.BitSet is prefered to lucene fixed ones)  */
  protected final BitSet stops; 
  // No internal pointer on a term, not thread safe
  


  /**
   * Build the dictionaries and stats. Each form indexed for the field will be identified by an int (formid).
   * This id will be in freq order, so that the form with formid=1 is the most frequent for the index
   * (formId=0 is the empty string). This order allows optimizations for co-occurrences matrix,
   * 
   * 
   * @param reader
   * @param fieldName
   * @throws IOException
   */
  public FieldText(final DirectoryReader reader, final String fieldName) throws IOException
  {
    FieldInfos fieldInfos = FieldInfos.getMergedFieldInfos(reader);
    FieldInfo info = fieldInfos.fieldInfo(fieldName);
    if (info == null) {
      throw new IllegalArgumentException("Field \"" + fieldName + "\" is not known in this index");
    }
    IndexOptions options = info.getIndexOptions();
    if (options == IndexOptions.NONE || options == IndexOptions.DOCS) {
      throw new IllegalArgumentException("Field \"" + fieldName + "\" of type " + options + " has no FREQS (see IndexOptions)");
    }
    this.reader = reader;
    final int[] docOccs = new int[reader.maxDoc()];
    final FixedBitSet docs =  new FixedBitSet(reader.maxDoc()); // used to count exactly docs with more than one term
    this.fieldName = fieldName;
    
    
    long occsAll = 0;
    ArrayList<FormRecord> stack = new ArrayList<FormRecord>();
    BytesRef bytes;

    // read in IndexReader.totalTermFreq(Term term)
    // «Note that, like other term measures, this measure does not take deleted documents into account.»
    // So it is more efficient to use a term iterator on all terms
    final int NO_MORE_DOCS = DocIdSetIterator.NO_MORE_DOCS;
    // a tmp dic used as an id provider, is needed if there are more than on leave to the index
    // is also used to remmeber the UTF8 bytes to reorder
    BytesRefHash tmpDic = new BytesRefHash();
    // loop on the index leaves to get all terms and freqs
    for (LeafReaderContext context : reader.leaves()) {
      LeafReader leaf = context.reader();
      int docBase = context.docBase;
      Terms terms = leaf.terms(fieldName);
      if (terms == null) continue;
      TermsEnum tenum = terms.iterator(); // org.apache.lucene.codecs.blocktree.SegmentTermsEnum
      PostingsEnum docsEnum = null;
      while ((bytes = tenum.next()) != null) {
        if (bytes.length == 0) continue; // maybe an empty position, do not count
        FormRecord rec;
        int tmpId = tmpDic.add(bytes);
        // form already encountered, probabbly another leave
        if (tmpId < 0) {
          tmpId = -tmpId - 1;
          rec = stack.get(tmpId); // should be OK, but has not be tested
        }
        else {
          rec = new FormRecord(tmpId);
          stack.add(tmpId, rec);
        }
        // termLength[formId] += tenum.totalTermFreq(); // not faster if not yet cached
        docsEnum = tenum.postings(docsEnum, PostingsEnum.FREQS);
        int docLeaf;
        Bits live = leaf.getLiveDocs();
        boolean hasLive = (live != null);
        while ((docLeaf = docsEnum.nextDoc()) != NO_MORE_DOCS) {
          if (hasLive && !live.get(docLeaf)) continue; // deleted doc
          int freq = docsEnum.freq();
          if (freq == 0) continue; // strange, is’n it ? Will probably not arrive
          rec.docs++;
          rec.occs += freq;
          occsAll += freq;
          docOccs[docBase + docLeaf] += freq;
          docs.set(docBase + docLeaf);
        }
      }
    }
    this.size = stack.size()+1; // should be the stack of non empty term + empty term
    // here we should have all we need to affect a freq formId
    // sort forms, and reloop on them to get optimized things
    java.util.BitSet stopRecord = new java.util.BitSet(); // record StopWords to build an optimized BitSet
    BytesRefHash hashDic = new BytesRefHash(); // populate a new hash dic with values
    hashDic.add(new BytesRef("")); // add empty string as formId=0 for empty positions
    long[] formOccs = new long[this.size];
    int[] formDocs = new int[this.size];
    int[] tags = new int[this.size];
    Collections.sort(stack); // should sort by frequences
    CharsAtt chars = new CharsAtt(); // to test against indexation dicos
    bytes = new BytesRef();
    for (FormRecord rec: stack)
    {
      tmpDic.get(rec.tmpId, bytes); // get the term
      final int formId = hashDic.add(bytes); // copy it and get an id for it
      formOccs[formId] = rec.occs;
      formDocs[formId] = rec.docs;
      if (FrDics.isStop(bytes)) stopRecord.set(formId);
      chars.copy(bytes); // not a lot efficient, but better than have a copy of the stop words dictionary as UTF8 bytes
      LexEntry entry = FrDics.word(chars);
      if (entry != null) {
        tags[formId] = entry.tag;
        continue;
      }
      entry = FrDics.name(chars);
      if (entry != null) {
        tags[formId] = entry.tag;
        continue;
      }
      if (chars.length() < 1) continue; // ?
      if (Char.isUpperCase(chars.charAt(0))) tags[formId] = Tag.NAME;
    }
    // here we should be happy and set class fields
    BitSet stops = new FixedBitSet(stopRecord.length()); // because most common words are probably stop words, the bitset maybe optimized
    // quite a dangerous loop but should work
    for (int formId = stopRecord.nextSetBit(0); formId != -1; formId = stopRecord.nextSetBit(formId + 1)) {
      stops.set(formId);
    }
    this.stops = stops;
    this.occsAll = occsAll;
    this.docsAll = docs.cardinality();
    this.formDic = hashDic;
    this.formAllDocs = formDocs;
    this.formAllOccs = formOccs;
    this.docOccs = docOccs;
    this.formTag = tags;
  }
  
  /**
   * A temporary record used to sort collected terms from global index.
   */
  private class FormRecord implements Comparable<FormRecord>
  {
    final int tmpId;
    int docs;
    long occs;
    
    FormRecord(final int tmpId) {
      this.tmpId = tmpId;
    }

    @Override
    public int compareTo(FormRecord o)
    {
      int cp = Long.compare(o.occs, occs);
      if (cp != 0) return cp;
      // not the nicest alpha sort order
      return Integer.compare(tmpId, o.tmpId);
    }
    
  }

  /**
   * Returns formId &gt;= 0 if exists, or &lt; 0 if not.
   * @param bytes
   * @return 
   */
  public int formId(final BytesRef bytes)
  {
    return formDic.find(bytes);
  }

  /**
   * Returns formId &gt;= 0 if exists, or &lt; 0 if not.
   * @param bytes
   * @return 
   */
  public int formId(final String term)
  {
    BytesRef bytes = new BytesRef(term);
    return formDic.find(bytes);
  }

  /**
   * How many freqs for this term ?
   * @param formId
   * @return
   */
  public long occs(int formId)
  {
    return formAllOccs[formId];
  }

  /**
   * How many docs for this formId ?
   * @param formId
   * @return
   */
  public int docs(int formId)
  {
    return formAllDocs[formId];
  }
  
  /**
   * Is this formId a StopWord ?
   * @param formId
   * @return
   */
  public boolean isStop(int formId)
  {
    return stops.get(formId);
  }

  /**
   * Get String value for a formId.
   * @param formId
   * @return
   */
  public String label(final int formId)
  {
    BytesRef bytes = new BytesRef();
    this.formDic.get(formId, bytes);
    return bytes.utf8ToString();
  }

  /**
   * Get a String value for formId, using a mutable array of bytes.
   * @param formId
   * @param bytes
   * @return
   */
  public BytesRef label(int formId, BytesRef bytes)
  {
    return this.formDic.get(formId, bytes);
  }

  /**
   * Get global length (occurrences) for a term
   * 
   * @param s
   */
  public long occs(final String s)
  {
    final BytesRef bytes = new BytesRef(s);
    final int id = formDic.find(bytes);
    if (id < 0) return -1;
    return formAllOccs[id];
  }

  /**
   * Get global length (occurrences) for a term
   * 
   * @param bytes
   */
  public long occs(final BytesRef bytes)
  {
    final int id = formDic.find(bytes);
    if (id < 0) return -1;
    return formAllOccs[id];
  }

  public FormEnum iterator(final int limit, final Specif specif, final BitSet filter, final TagFilter tags) throws IOException
  {
    return iterator(limit, specif, filter, tags, false);
  }

  /**
   * Global termlist, maybe filtered but not scored. More efficient than a scorer
   * that loop on each term for global.
   * @return
   */
  public FormEnum iterator(final int limit, final TagFilter tags, final boolean reverse)
  {
    boolean hasTags = (tags != null);
    boolean noStop = (tags != null && tags.noStop());
    double[] scores = new double[size];
    for (int formId=0; formId < size; formId++) {
      if (noStop && isStop(formId)) continue;
      if (hasTags && !tags.accept(formTag[formId])) continue;
      // specif.idf(formOccs[formId], formDocs[formId] );
      // loop on all docs containing the term ?
      scores[formId] = formAllOccs[formId];
    }
    // now we have all we need to build a sorted iterator on entries
    TopArray top;
    if (limit < 1) top = new TopArray(scores); // all search
    else top = new TopArray(limit, scores);
    FormEnum it = new FormEnum(this);
    it.scores = scores;
    it.hits = formAllDocs;
    it.freqs = formAllOccs;
    it.sorter(top.toArray());
    return it;
  }
  
  
  /**
   * Count of occurrences by term for a subset of the index,
   * defined as a BitSet. Returns an iterator sorted according 
   * to a scorer. If scorer is null, default is count of occurences.
   */
  public FormEnum iterator(final int limit, Specif specif, final BitSet filter, final TagFilter tags, final boolean reverse) throws IOException
  {
    boolean hasTags = (tags != null);
    boolean noStop = (tags != null && tags.noStop());
    if (specif == null) specif = new SpecifOccs();
    boolean hasSpecif = (specif!= null);
    double[] scores = new double[size];
    boolean hasFilter = (filter != null && filter.cardinality() > 0);
    
    if (hasSpecif) specif.all(occsAll, docsAll);
    
    BitSet hitsVek = new FixedBitSet(reader.maxDoc());
    
    long[] freqs = new long[size];
    int[] hits = new int[size];
    BytesRef bytes;
    final int NO_MORE_DOCS = DocIdSetIterator.NO_MORE_DOCS;
    // loop an all index to calculate a score for each term before build a more expensive object
    for (LeafReaderContext context : reader.leaves()) {
      int docBase = context.docBase;
      LeafReader leaf = context.reader();
      Terms terms = leaf.terms(fieldName);
      if (terms == null) continue;
      TermsEnum tenum = terms.iterator();
      PostingsEnum docsEnum = null;
      while ((bytes = tenum.next()) != null) {
        int formId = formDic.find(bytes);
        // filter some tags
        if (noStop && isStop(formId)) continue;
        if (hasTags && !tags.accept(formTag[formId])) continue;
        // if formId is negative, let the error go, problem in reader
        // for each term, set scorer with global stats
        if (hasSpecif) specif.idf(formAllOccs[formId], formAllDocs[formId] );
        docsEnum = tenum.postings(docsEnum, PostingsEnum.FREQS);
        int docLeaf;
        while ((docLeaf = docsEnum.nextDoc()) != NO_MORE_DOCS) {
          int docId = docBase + docLeaf;
          if (hasFilter && !filter.get(docId)) continue; // document not in the filter
          int freq = docsEnum.freq();
          if (freq < 1) throw new ArithmeticException("??? field="+fieldName+" docId=" + docId+" term="+bytes.utf8ToString()+" freq="+freq);
          hitsVek.set(docId);
          hits[formId]++;
          // if it’s a tf-idf like score
          if (hasSpecif) scores[formId] += specif.tf(freq, docOccs[docId]);
          freqs[formId] += freq;
        }
      }
    }
    
    
    // We have counts, calculate scores
    // loop on the bitSet to have the part Size
    long partOccs = 0;
    for (int docId = hitsVek.nextSetBit(0); docId != NO_MORE_DOCS; docId = hitsVek.nextSetBit(docId + 1)) {
      partOccs += docOccs[docId];
    }
    if (hasSpecif) {
      specif.part(partOccs, hitsVek.cardinality());
      // loop on all form to calculate scores
      for (int formId = 0; formId < size; formId++) {
        long formPartOccs = freqs[formId];
        if (formPartOccs < 1) continue;
        double p = specif.prob(scores[formId], formPartOccs, formAllOccs[formId]);
        scores[formId] = p;
      }
    }
    
    
    // now we have all we need to build a sorted iterator on entries
    TopArray top;
    int flags = TopArray.NO_ZERO;
    if (reverse) flags |= TopArray.REVERSE;
    if (limit < 1) top = new TopArray(scores, flags); // all search
    else top = new TopArray(limit, scores, flags);
    FormEnum it = new FormEnum(this);
    it.sorter(top.toArray());
    // add some more stats on this iterator
    
    it.hits = hits;
    it.freqs = freqs;
    it.scores = scores;
    it.partOccs = partOccs;

    return it;
  }
  

  /**
   * Get a dictionary of search, without statistics.
   * @param reader
   * @param field
   * @return
   * @throws IOException
   */
  static public BytesRefHash terms(DirectoryReader reader, String field) throws IOException
  {
    BytesRefHash hashDic = new BytesRefHash();
    BytesRef ref;
    for (LeafReaderContext context : reader.leaves()) {
      LeafReader leaf = context.reader();
      // int docBase = context.docBase;
      Terms terms = leaf.terms(field);
      if (terms == null) continue;
      TermsEnum tenum = terms.iterator();
      while ((ref = tenum.next()) != null) {
        int formId = hashDic.add(ref);
        if (formId < 0) formId = -formId - 1; // value already given
      }
    }
    return hashDic;
  }


  @Override
  public String toString()
  {
    StringBuilder string = new StringBuilder();
    BytesRef ref = new BytesRef();
    int len = Math.min(size, 200);
    for (int i = 0; i < len; i++) {
      formDic.get(i, ref);
      string.append(ref.utf8ToString() + ": " + formAllOccs[i] + "\n");
    }
    return string.toString();
  }

}
