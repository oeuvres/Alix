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

import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.FixedBitSet;

import alix.lucene.Alix;
import alix.lucene.DocType;
import alix.util.IntList;
import alix.util.TopArray;
import alix.web.Distrib.Scorer;

/**
 * A dedicated dictionary for facets, to allow similarity scores.
 * 
 * <p>
 * Example scenario: search "science God" among lots of books. Which author
 * should be the more relevant result? The one with the more occurrences could
 * be a good candidate, but if he signed lots of big books in the corpus, he has
 * a high probability to use the searched words. Different formulas are known
 * for such scoring.
 * </p>
 * 
 * <p>
 * Different variables could be used in such formulas. Some are only relative to
 * all index, some are cacheable for each facet value, other are search
 * dependent.
 * <p>
 * 
 * <ul>
 * <li>index, total document count</li>
 * <li>index, total occurrences count</li>
 * <li>facet, total document count</li>
 * <li>facet, total occurrences count</li>
 * <li>search, matching document count</li>
 * <li>search, matching occurrences count</li>
 * </ul>
 * 
 * <p>
 * This facet dic is backed on a lucene hash of search. This handy object provide
 * a sequential int id for each term. This is used as a pointer in different
 * growing arrays. On creation, object is populated with data non dependent of a
 * search. Those internal vectors are stored as arrays with facetId index.
 * <p>
 *
 */
public class FieldFacet
{
  /** Name of the field for facets, source key for this dictionary */
  public final String fieldName;
  /** The field type */
  public final DocValuesType type;
  /** Name of the field for text, source of different value counts */
  public final FieldText fieldText;
  /** Store and populate the search */
  protected final BytesRefHash facetDic;
  /** All facetId in alphabetic order */
  protected final int[] alpha;
  /** Global number of docs relevant for this facet */
  protected final int allDocs;
  /** Global number of occurrences in the text field */
  protected final long allOccs;
  /** Global number of values for this facet */
  public final int size;
  /** Count of docs by facet */
  protected final int[] facetDocs;
  /** By facet, count of occurrences in a text field */
  protected final long[] facetOccs;
  /** A table docId => facetId*n, used to get freqs */
  private final int[][] docFacets;
  /** A docId by facet uses as a “cover“ doc (not counted  */
  protected final int[] facetCover;
  /** A cached vector for each docId, size in occurrences  */
  private final int[] docOccs;
  /** Cache the state of a reader from which all freqs are counted */
  private IndexReader reader;

  /**
   * Build data to have frequencies on a facet field.
   * Access by an Alix instance, to allow cache on an IndexReader state.
   * 
   * @param alix
   * @param facet
   * @param text
   * @throws IOException
   */
  public FieldFacet(final Alix alix, final String facet, final String text, final Term coverTerm) throws IOException
  {
    // final int[] docOccs = new int[reader.maxDoc()];
    this.fieldText = alix.fieldText(text);
    FieldInfo info = alix.info(facet);
    if (info == null) {
      throw new IllegalArgumentException("Field \"" + facet + "\" is not known in this index.");
    }
    type = info.getDocValuesType();
    if (type != DocValuesType.SORTED_SET && type != DocValuesType.SORTED) {
      throw new IllegalArgumentException("Field \"" + facet + "\", the type "+type+" is not supported as a facet.");
    }
    final BytesRefHash hashDic = new BytesRefHash();
    // get a vector of possible docids used as a cover for a facetId
    BitSet coverBits = null;
    if (coverTerm != null) {
      IndexSearcher searcher = alix.searcher(); // ensure reader or decache
      Query coverQuery = new TermQuery(coverTerm);
      CollectorBits coverCollector = new CollectorBits(searcher);
      searcher.search(coverQuery, coverCollector);
      coverBits = coverCollector.bits();
    }
    this.fieldName = facet;
    this.reader = alix.reader();
    docFacets = new int[reader.maxDoc()][];
    int docsAll = 0;
    long occsAll = 0;
    // prepare local arrays to populate with leaf data
    long[] facetOccs = new long[32];
    int[] facetDocs = new int[32];
    int[] facetCover = new int[32];

    int[] docOccs = alix.docOccs(text); // length of each doc for the text field
    this.docOccs = docOccs;
    // this.docLength = docLength;
    // max int for an array collecttor
    int ordMax = -1;
    final int NO_MORE_DOCS = DocIdSetIterator.NO_MORE_DOCS;
    for (LeafReaderContext context: reader.leaves()) { // loop on the reader leaves
      int docBase = context.docBase;
      LeafReader leaf = context.reader();
      // get a doc iterator for the facet field
      DocIdSetIterator docs4terms = null;
      if (type == DocValuesType.SORTED) {
        docs4terms = leaf.getSortedDocValues(facet);
        if (docs4terms == null) continue;
        ordMax = (int)((SortedDocValues)docs4terms).getValueCount();
      }
      else if (type == DocValuesType.SORTED_SET) {
        docs4terms = leaf.getSortedSetDocValues(facet);
        if (docs4terms == null) continue;
        ordMax = (int)((SortedSetDocValues)docs4terms).getValueCount();
      }
      // record doc counts for each term by a temp ord index
      int[] leafDocs = new int[ordMax];
      // record occ counts for each term by a temp ord index
      long[] leafOccs = new long[ordMax];
      // record cover docId for each term by a temp ord index
      int[] leafCover = new int[ordMax];
      // loop on docs
      int docLeaf;
      Bits live = leaf.getLiveDocs();
      boolean hasLive = (live != null);
      while ((docLeaf = docs4terms.nextDoc()) != NO_MORE_DOCS) {
        if (hasLive && !live.get(docLeaf)) continue; // deleted doc
        final int docId = docBase + docLeaf;
        final long docLen = docOccs[docId];
        int ord;
        if (type == DocValuesType.SORTED) {
          ord = ((SortedDocValues)docs4terms).ordValue();
          // doc is a cover
          if (coverBits != null && coverBits.get(docId)) {
            leafCover[ord] = docId;
          }
          // do not add stats for empty docs
          if(docLen > 0) { 
            leafDocs[ord]++;
            leafOccs[ord] += docLen;
          }
        }
        else if (type == DocValuesType.SORTED_SET) {
          SortedSetDocValues it = (SortedSetDocValues)docs4terms;
          while ((ord = (int)it.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
            // doc is a cover, record it and do not add to stats
            if (coverBits != null && coverBits.get(docId)) {
              leafCover[ord] = docId;
            }
            // do not add stats for empty docs
            if(docLen > 0) { 
              leafDocs[ord]++;
              leafOccs[ord] += docLen;
            }
          }
        }
        if(docLen <= 0) continue;
        docsAll++; // one more doc for this facet
        occsAll += docLen; // count of tokens for this doc
      }
      BytesRef bytes = null;
      // build a local map for this leaf to record the ord -> facetId
      int[] ordFacetId = new int[ordMax];
      // copy the data fron this leaf to the global dic, and get facetId for it
      for (int ord = 0; ord < ordMax; ord++) {
        if (type == DocValuesType.SORTED) bytes = ((SortedDocValues)docs4terms).lookupOrd(ord);
        else if (type == DocValuesType.SORTED_SET) bytes = ((SortedSetDocValues)docs4terms).lookupOrd(ord);
        int facetId = hashDic.add(bytes);
        // value already given
        if (facetId < 0) facetId = -facetId - 1;
        facetCover = ArrayUtil.grow(facetCover, facetId + 1);
        // if more than one cover by facet, last will replace previous
        facetCover[facetId] = leafCover[ord];
        facetDocs = ArrayUtil.grow(facetDocs, facetId + 1);
        facetDocs[facetId] += leafDocs[ord];
        facetOccs = ArrayUtil.grow(facetOccs, facetId + 1);
        facetOccs[facetId] += leafOccs[ord];
        ordFacetId[ord] = facetId;
      }
      // global dic has set a unified int id for search
      // build a map docId -> facetId*n, used to get freqs from docs found
      // restart the loop on docs
      if (type == DocValuesType.SORTED) docs4terms = leaf.getSortedDocValues(facet);
      else if (type == DocValuesType.SORTED_SET) docs4terms = leaf.getSortedSetDocValues(facet);
      IntList row = new IntList(); // a growable imt array
      while ((docLeaf = docs4terms.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
        if (live != null && !live.get(docLeaf)) continue; // deleted doc
        int ord;
        if (type == DocValuesType.SORTED) {
          ord = ((SortedDocValues)docs4terms).ordValue();
          docFacets[docBase + docLeaf] = new int[]{ordFacetId[ord]};
        }
        else if (type == DocValuesType.SORTED_SET) {
          row.reset();
          SortedSetDocValues it = (SortedSetDocValues)docs4terms;
          while ((ord = (int)it.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
            row.push(ordFacetId[ord]);
          }
          docFacets[docBase + docLeaf] = row.toArray();
        }
      }
    }
    // this should avoid some opcode upper
    this.allDocs = docsAll;
    this.allOccs = occsAll;
    this.facetDic = hashDic;
    this.alpha = FormEnum.sortAlpha(hashDic);
    this.size = hashDic.size();
    this.facetOccs = facetOccs;
    this.facetDocs = facetDocs;
    this.facetCover = facetCover;
  }

  /** 
   * Get first available facetId for this docId or -1 if nothing found
   */
  public int facetId(final int docId)
  {
    int[] results = docFacets[docId];
    if (results == null || results.length < 1) return -1;
    return results[0];
  }
  
  /**
   * Returns a new enumerator on all search for this facet in orthographic order
   * @return
   * @throws IOException
   */
  public FormEnum iterator() throws IOException {
    FormEnum it = new FormEnum(this);
    it.sorter(alpha);
    return it;
  }

  /**
   * Use a list of search as a navigator for a list of doc ids.
   * The list is supposed to be sorted in a relevant order for this facet
   * ex : (author, title) or (author, date) for an author facet.
   * Get the index of the first relevant document for each faceted term.
   */
  public int[] nos(final TopDocs topDocs) {
    int[] nos = new int[size];
    Arrays.fill(nos, Integer.MIN_VALUE);
    ScoreDoc[] scoreDocs = topDocs.scoreDocs;
    // loop on doc in order
    for (int n = 0, docs = scoreDocs.length; n < docs ; n ++) {
      final int docId = scoreDocs[n].doc;
      int[] facets = docFacets[docId]; // get the facets of this doc
      if (facets == null) continue; // could be null if doc not faceted
      for (int i = 0, length = facets.length; i < length; i++) {
        final int facetId = facets[i];
        if (nos[facetId] > -0) continue; // already set
        nos[facetId] = n;
      }
    }
    return nos;
  }
  
  /**
   * Results of a text search according to a facet field search.
   * Query maybe restricted by a doc filter (a corpus).
   * If there are no search in the search, will cry.
   * Returns an iterator on search of this facet, with scores and other stats.   
   * @return
   * @throws IOException
   */
  public FormEnum iterator(final String[] search, final BitSet filter, Scorer scorer, final int limit) throws IOException
  {
    ArrayList<Term> terms = new ArrayList<Term>();
    if (search != null && search.length != 0) { 
      for (String f: search) {
        if (f == null) continue;
        if (f.isEmpty()) continue;
        terms.add(new Term(fieldText.fieldName, f));
      }
    }
    long[] formAllOccs = fieldText.formAllOccs; // localize
    int[] formAllDocs = fieldText.formAllDocs; // localize
    boolean hasScorer = (scorer != null);
    boolean hasFilter = (filter != null);

    // Crawl index to get stats by facet term about the text search
    final int NO_MORE_DOCS = DocIdSetIterator.NO_MORE_DOCS;
    BitSet docMap = new FixedBitSet(reader.maxDoc()); // keep memory of already counted docs
    double[] scores = new double[size];
    int[] hits = new int[size];
    long[] occs = new long[size]; // a vector to count matched occurrences by facet
    // loop on each term of the search to update the score vector
    int facetMatch = 0; // number of matched facets by this search
    long occsMatch = 0; // total occurrences matched
    // loop on search, this order of loops may be not efficient for a big list of search
    // loop by term is better for some stats
    for (Term term : terms) {
      long[] formPartOccs = new long[size]; // a vector to count matched occurrences for this term, by facet
      final int formId = fieldText.formId(term.bytes());
      if (hasScorer) scorer.idf(allOccs, allDocs, formAllOccs[formId], formAllDocs[formId]);
      // loop on the reader leaves (opening may have disk cost)
      for (LeafReaderContext context : reader.leaves()) {
        LeafReader leaf = context.reader();
        // get the ocurrence count for the term
        PostingsEnum postings = leaf.postings(term);
        if (postings == null) continue;
        

        
        final int docBase = context.docBase;
        int docLeaf;
        // loop on the docs for this term
        while ((docLeaf = postings.nextDoc()) != NO_MORE_DOCS) {
          int docId = docBase + docLeaf;
          if (hasFilter && !filter.get(docId)) continue; // document not in the metadata filter
          final int freq = postings.freq();
          if (freq == 0) continue; // no occurrence for this term (why found with no freqs ?)
          final boolean docSeen = docMap.get(docId); // doc has been seen for another term in the search
          int[] facets = docFacets[docId]; // get the facets of this doc
          if (facets == null) continue; // could be null if doc matching but not faceted
          occsMatch += freq;
          for (int i = 0, length = facets.length; i < length; i++) {
            int facetId = facets[i];
            // first match for this facet, increment the counter of matched facets
            if (occs[facetId] == 0) {
              facetMatch++;
            }
            if (!docSeen) hits[facetId]++; // if doc not already counted for another, increment hits for this facet
            occs[facetId] += freq; // add the matched freqs for this doc to the facet
            formPartOccs[facetId] += freq;
            // term frequency
            if (hasScorer) scores[facetId] += scorer.tf(freq, docOccs[docId]);

          }
          if (!docSeen) docMap.set(docId); // do not recount this doc as hit for another term
        }
      }

    }
    FormEnum it;
    // a scorer 
    if (hasScorer) {
      TopArray top;
      if (limit < 1) top = new TopArray(scores); // all search
      else top = new TopArray(limit, scores);
      it = new FormEnum(this);
      it.sorter(top.toArray());
      
      
    } 
    // no scorer, search desired in alphabetic order with stats
    else {
      it = new FormEnum(this);
      it.sorter(alpha);
    }
    
    it.hits = hits;
    it.freqs = occs; // freqs is bad here
    it.scores = scores;
    // it.occsCount = occsCount;
    return it;
  }
    
  /**
   * Crawl the index to find relevant documents according to a filter.
   * Will cry if filter is null. Prefers {#iterator(int, Scorer)} if you want to iterate on 
   * all search for this facet;
   * 
   * @param limit
   * @param filter
   * @param scorer
   * @return
   * @throws IOException
   */
    public FormEnum iterator(final BitSet filter, final int limit) throws IOException
    {
      int[] hits = new int[size];
      long[] occs = new long[size];
      final int NO_MORE_DOCS = DocIdSetIterator.NO_MORE_DOCS;
      // loop on the docs of the filter
      // loop on the reader leaves
      for (LeafReaderContext ctx : reader.leaves()) { // loop on the reader leaves
        int docBase = ctx.docBase;
        LeafReader leaf = ctx.reader();
        // get a doc iterator for the facet field
        SortedSetDocValues docs4terms = leaf.getSortedSetDocValues(fieldName);
        if (docs4terms == null) continue;

        // loop on the docs with a facet
        int docLeaf;
        while ((docLeaf = docs4terms.nextDoc()) != NO_MORE_DOCS) {
          int docId = docBase + docLeaf;
          if (!filter.get(docId)) continue;// not in the filter do not count
          final int docLen = docOccs[docId];
          int[] facets = docFacets[docId]; // get the facets of this doc
          // if (facets == null) continue; // should not arrive, wait and see
          for (int i = 0, length = facets.length; i < length; i++) {
            int facetId = facets[i];
            hits[facetId]++; 
            occs[facetId] += docLen;
          }
        }
      }
      // build the ordered array of facetId
      FormEnum it = new FormEnum(this);
      it.sorter(alpha);
      it.hits = hits;
      it.freqs = occs;
      return it;
    }


  /**
   * Number of search in the list.
   * 
   * @return
   */
  public int size()
  {
    return facetDic.size();
  }

  @Override
  public String toString()
  {
    StringBuilder string = new StringBuilder();
    BytesRef ref = new BytesRef();
    for (int i = 0; i < size; i++) {
      facetDic.get(i, ref);
      string.append(ref.utf8ToString() + ": " + facetOccs[i] + "\n");
    }
    return string.toString();
  }

}
