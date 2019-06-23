/*
 * © Pierre DITTGEN <pierre@dittgen.org> 
 * 
 * Alix : [A] [L]ucene [I]ndexer for [X]ML documents
 * 
 * Alix is a command-line tool intended to parse XML documents and to index
 * them into a Lucene Index
 * 
 * This software is governed by the CeCILL-C license under French law and
 * abiding by the rules of distribution of free software.  You can  use, 
 * modify and/ or redistribute the software under the terms of the CeCILL-C
 * license as circulated by CEA, CNRS and INRIA at the following URL
 * "http://www.cecill.info". 
 * 
 * As a counterpart to the access to the source code and  rights to copy,
 * modify and redistribute granted by the license, users are provided only
 * with a limited warranty  and the software's author,  the holder of the
 * economic rights,  and the successive licensors  have only  limited
 * liability. 
 * 
 * In this respect, the user's attention is drawn to the risks associated
 * with loading,  using,  modifying and/or developing or reproducing the
 * software by the user in light of its specific status of free software,
 * that may mean  that it is complicated to manipulate,  and  that  also
 * therefore means  that it is reserved for developers  and  experienced
 * professionals having in-depth computer knowledge. Users are therefore
 * encouraged to load and test the software's suitability as regards their
 * requirements in conditions enabling the security of their systems and/or 
 * data to be ensured and,  more generally, to use and operate it in the 
 * same conditions as regards security. 
 * 
 * The fact that you are presently reading this means that you have had
 * knowledge of the CeCILL-C license and that you accept its terms.
 * 
 */
package alix.lucene;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.PointValues.IntersectVisitor;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.Bits;

import alix.fr.Tag;
import alix.lucene.analysis.AnalyzerAlix;
import alix.lucene.analysis.CharsLemAtt;
import alix.lucene.analysis.TokenCompound;
import alix.lucene.analysis.TokenLem;
import alix.lucene.analysis.TokenizerFr;
import alix.lucene.search.Facet;
import alix.lucene.search.QueryBits;
import alix.lucene.search.Scorer;
import alix.lucene.search.TermList;
import alix.lucene.search.TopTerms;
import alix.lucene.search.TermFreqs;

/**
 * Alix entry-point
 * 
 * @author Pierre DITTGEN (2012, original idea, creation)
 * @author glorieux-f
 */
public class Alix
{
  /** Mandatory field, XML file name, used for update */
  public static final String FILENAME = "alix:filename";
  /** Mandatory field, to query the last document of a book series */
  public static final String BOOK = "alix:book";
  /** A global cache for objects */
  private final ConcurrentHashMap<String, SoftReference<Object>> cache = new ConcurrentHashMap<>();
  /** Current filename proceded */
  public static final FieldType ftypeAll = new FieldType();
  static {
    // inverted index
    ftypeAll.setTokenized(true);
    // keep norms for Similarity, http://makble.com/what-is-lucene-norms
    ftypeAll.setOmitNorms(false);
    // position needed for phrase query, take also
    ftypeAll.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
    ftypeAll.setStoreTermVectors(true);
    ftypeAll.setStoreTermVectorOffsets(true);
    ftypeAll.setStoreTermVectorPositions(true);
    // do not store in this field
    ftypeAll.setStored(false);
    ftypeAll.freeze();
  }
  /** Pool of instances, unique by path */
  public static final HashMap<Path, Alix> pool = new HashMap<Path, Alix>();
  /** Normalized path */
  public final Path path;
  /** The lucene directory, kept private, to avoid closing by a thread */
  private Directory dir;
  /** The IndexReader if requested */
  private IndexReader reader;
  /** The infos on field if reader is requested */
  private FieldInfos fieldInfos;
  /** Shared Similarity for indexation and searching */
  public final Similarity similarity;
  /** The IndexSearcher if requested */
  private IndexSearcher searcher;
  /** The IndexWriter if requested */
  private IndexWriter writer;

  /**
   * Avoid construction, maintain a pool by file path to ensure unicity.
   * 
   * @param path
   * @throws IOException
   */
  private Alix(final Path path) throws IOException
  {
    this.path = path;
    Files.createDirectories(path);
    this.similarity = new BM25Similarity(); // default similarity
    // dir = FSDirectory.open(indexPath);
    dir = MMapDirectory.open(path); // https://dzone.com/articles/use-lucene’s-mmapdirectory
  }

  /**
   * Get a wrapper on a lucene index by file path.
   * 
   * @param path
   * @throws IOException
   */
  public static Alix instance(final String path) throws IOException
  {
    return instance(Paths.get(path));
  }

  /**
   * Get a wrapper on a lucene index by file path.
   * 
   * @param path
   * @throws IOException
   */
  public static Alix instance(Path path) throws IOException
  {
    path = path.toAbsolutePath();
    Alix alix = pool.get(path);
    if (alix == null) {
      alix = new Alix(path);
      pool.put(path, alix);
    }
    return alix;
  }

  public IndexWriter writer() throws IOException
  {
    return writer(null);
  }

  /**
   * Get a lucene writer
   * 
   * @throws IOException
   */
  public IndexWriter writer(final Similarity similarity) throws IOException
  {
    if (writer != null && writer.isOpen()) return writer;
    Analyzer analyzer = new AnalyzerAlix();
    IndexWriterConfig conf = new IndexWriterConfig(analyzer);
    conf.setUseCompoundFile(false); // show separate file by segment
    // may needed, increase the max heap size to the JVM (eg add -Xmx512m or
    // -Xmx1g):
    conf.setRAMBufferSizeMB(48);
    conf.setOpenMode(OpenMode.CREATE_OR_APPEND);
    //
    if (similarity != null) conf.setSimilarity(similarity);
    else conf.setSimilarity(this.similarity);
    // no effect found with modification ConcurrentMergeScheduler
    /*
     * int threads = Runtime.getRuntime().availableProcessors() - 1;
     * ConcurrentMergeScheduler cms = new ConcurrentMergeScheduler();
     * cms.setMaxMergesAndThreads(threads, threads); cms.disableAutoIOThrottle();
     * conf.setMergeScheduler(cms);
     */
    // order docid by a field after merge ? No functionality should rely on such
    // order
    // conf.setIndexSort(new Sort(new SortField(YEAR, SortField.Type.INT)));
    writer = new IndexWriter(dir, conf);
    return writer;
  }

  /**
   * Get the reader for this lucene index.
   * 
   * @return
   * @throws IOException
   */
  public IndexReader reader() throws IOException
  {
    return reader(false);
  }

  /**
   * Get a reader for this lucene index, allow to force renew if force is true.
   * 
   * @param force
   * @return
   * @throws IOException
   */
  public IndexReader reader(final boolean force) throws IOException
  {
    if (!force && reader != null) return reader;
    cache.clear(); // clean cache on renew the reader
    // near real time reader
    if (writer != null && writer.isOpen()) reader = DirectoryReader.open(writer, true, true);
    else reader = DirectoryReader.open(dir);
    fieldInfos = FieldInfos.getMergedFieldInfos(reader);
    return reader;
  }

  /**
   * Get the searcher for this lucene index.
   * 
   * @return
   * @throws IOException
   */
  public IndexSearcher searcher() throws IOException
  {
    return searcher(false);
  }

  /**
   * Get a searcher for this lucene index, allow to force renew if force is true.
   * 
   * @param force
   * @return
   * @throws IOException
   */
  public IndexSearcher searcher(final boolean force) throws IOException
  {
    if (!force && searcher != null) return searcher;
    reader(force);
    searcher = new IndexSearcher(reader);
    searcher.setSimilarity(similarity);
    return searcher;
  }

  /**
   * A simple cache. Will be cleared if index reader is renewed. Use SoftReference
   * as a value, so that Garbage Collector will silently delete object references
   * in case of Out of memory.
   * 
   * @param key
   * @param o
   */
  public void cache(String key, Object o)
  {
    cache.put(key, new SoftReference<Object>(o));
  }

  /**
   * Get a cached object
   * 
   * @param key
   * @return
   */
  public Object cache(String key)
  {
    SoftReference<Object> ref = cache.get(key);
    if (ref != null) return ref.get();
    return null;
  }

  /**
   * Returns an array in docid order with the value of an intPoint field (year).
   * 
   * @return
   * @throws IOException
   */
  public int[] docInt(String field) throws IOException
  {
    IndexReader reader = reader(); // ensure reader, or decache
    String key = "AlixDocInt" + field;
    int[] ints = (int[]) cache(key);
    if (ints != null) return ints;
    // build the list
    FieldInfo info = fieldInfos.fieldInfo(field);
    // check infos
    if (info.getPointDataDimensionCount() <= 0 && info.getDocValuesType() != DocValuesType.NUMERIC) {
      throw new IllegalArgumentException(
          "Field \"" + field + "\", bad type to get an int vector by docId, is not an IntPoint or NumericDocValues.");
    }
    int maxDoc = reader.maxDoc();
    final int[] docInt = new int[maxDoc];
    // fill with min value for deleted docs or with no values
    Arrays.fill(docInt, Integer.MIN_VALUE);
    final int[] minMax = { Integer.MAX_VALUE, Integer.MIN_VALUE };
    if (info.getPointDataDimensionCount() > 0) {
      if (info.getPointDataDimensionCount() > 1) {
        throw new IllegalArgumentException("Field \"" + field + "\" " + info.getPointDataDimensionCount()
            + " dimensions, too much for an int tag by doc.");
      }
      for (LeafReaderContext context : reader.leaves()) {
        final int docBase = context.docBase;
        LeafReader leaf = context.reader();
        final Bits liveDocs = leaf.getLiveDocs();
        PointValues points = leaf.getPointValues(field);
        points.intersect(new IntersectVisitor()
        {

          @Override
          public void visit(int docid)
          {
            // visit if inside the compare();
          }

          @Override
          public void visit(int docLeaf, byte[] packedValue)
          {
            if (liveDocs != null && !liveDocs.get(docLeaf)) return;
            // in case of multiple values, take the lower one
            if (docInt[docBase + docLeaf] > Integer.MIN_VALUE) return;
            int v = IntPoint.decodeDimension(packedValue, 0);
            docInt[docBase + docLeaf] = v;
          }

          @Override
          public Relation compare(byte[] minPackedValue, byte[] maxPackedValue)
          {
            int v = IntPoint.decodeDimension(minPackedValue, 0);
            if (minMax[0] > v) minMax[0] = v;
            v = IntPoint.decodeDimension(maxPackedValue, 0);
            if (minMax[1] < v) minMax[1] = v;
            // Answer that the query needs further infornmation to visit doc with values
            return Relation.CELL_CROSSES_QUERY;
          }
        });
      }
    }
    else if (info.getDocValuesType() == DocValuesType.NUMERIC) {
      for (LeafReaderContext context : reader.leaves()) {
        LeafReader leaf = context.reader();
        NumericDocValues docs4num = leaf.getNumericDocValues(field);
        // no values for this leaf, go next
        if (docs4num == null) continue;
        final Bits liveDocs = leaf.getLiveDocs();
        final int docBase = context.docBase;
        int docLeaf;
        while ((docLeaf = docs4num.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
          if (liveDocs != null && !liveDocs.get(docLeaf)) continue;
          int v = (int) docs4num.longValue(); // long value is force to int;
          docInt[docBase + docLeaf] = v;
          if (minMax[0] > v) minMax[0] = v;
          if (minMax[1] < v) minMax[1] = v;
        }
      }
    }
    cache(key, docInt);
    cache("AlixMinMax" + field, minMax);
    return docInt;
  }

  /** Return the min value of an IntPoint field. */
  public int min(String field) throws IOException
  {
    return minMax(field, 0);
  }

  /** Returns the max value of an IntPoint field. */
  public int max(String field) throws IOException
  {
    return minMax(field, 1);
  }

  /** Get min-max from the cache. */
  private int minMax(String field, int i) throws IOException
  {
    int[] minMax = (int[]) cache("AlixMinMax" + field);
    if (minMax == null) {
      docInt(field); // ensure calculation
      minMax = (int[]) cache("AlixMinMax" + field);
    }
    return minMax[i];
  }

  /**
   * Get value by docid of a unique store field, desired type is given by the
   * array to load. Very slow, ~1.5 s. / 1000 books
   * 
   * @throws IOException
   */
  public int[] docStore(String field, int[] load) throws IOException
  {
    IndexReader reader = reader(); // ensure reader, or decache
    int maxDoc = reader.maxDoc();
    if (load == null || load.length < maxDoc) load = new int[maxDoc];
    Bits liveDocs = null;
    boolean hasDeletions = reader.hasDeletions();
    if (hasDeletions) {
      liveDocs = MultiBits.getLiveDocs(reader);
    }
    Set<String> fields = new HashSet<String>();
    fields.add(field);
    for (int i = 0; i < maxDoc; i++) {
      if (hasDeletions && !liveDocs.get(i)) {
        load[i] = Integer.MIN_VALUE;
        continue;
      }
      Document doc = reader.document(i, fields);
      int v = doc.getField(field).numericValue().intValue();
      load[i] = v;
    }
    return load;
  }

  /**
   * For a facet field, get an iterator in some order, according to a query
   * 
   * @throws IOException
   */
  public TopTerms facet(final String facetField, final String textField, final QueryBits filter, final TermList terms,
      final Scorer scorer) throws IOException
  {
    String key = "AlixFacet" + facetField + textField;
    Facet facet = (Facet) cache(key);
    if (facet == null) facet = new Facet(this, facetField, textField);
    return facet.new FacetResult(filter, terms, scorer);
  }

  /**
   * @throws IOException
   * 
   */
  public TermFreqs termFreqs(final String field) throws IOException
  {
    String key = "AlixFreqList" + field;
    TermFreqs termFreqs = (TermFreqs) cache(key);
    if (termFreqs == null) termFreqs = new TermFreqs(this, field);
    return termFreqs;
  }

  /**
   * For a field, return an array in docid order, with the total number of tokens
   * by doc. Is cached. Term vector cost 1 s. / 1000 books A norm after indexation
   * could be faster, but common similarity store such length on one byte. see
   * SimilarityBase.computeNorm()
   * https://github.com/apache/lucene-solr/blob/master/lucene/core/src/java/org/apache/lucene/search/similarities/SimilarityBase.java#L185
   * 
   * @param field
   * @return
   * @throws IOException
   */
  public long[] docLength(String field) throws IOException
  {
    IndexReader reader = reader(); // ensure reader or decache
    String key = "AlixDocLength" + field;
    long[] docLength = (long[]) cache(key);
    if (docLength != null) return docLength;

    FieldInfo info = fieldInfos.fieldInfo(field);
    if (info.getIndexOptions() == IndexOptions.NONE) {
      throw new IllegalArgumentException(
          "Field \"" + field + "\" is not indexed, get the number of tokens by doc (length) is not relevant.");
    }
    if (info.getDocValuesType() != DocValuesType.NUMERIC && !info.hasVectors()) {
      throw new IllegalArgumentException(
          "Field \"" + field + "\" has no vectors or numeric value with thi name to calculate lengths.");
    }
    int maxDoc = reader.maxDoc();
    // index by year
    docLength = new long[maxDoc];
    // A text field may be indexed with a parallel long value which is supposed to
    // be the length
    if (info.getDocValuesType() == DocValuesType.NUMERIC) {
      for (LeafReaderContext context : reader.leaves()) {
        LeafReader leaf = context.reader();
        NumericDocValues docs4num = leaf.getNumericDocValues(field);
        // no values for this leaf, go next
        if (docs4num == null) continue;
        final Bits liveDocs = leaf.getLiveDocs();
        final int docBase = context.docBase;
        int docLeaf;
        while ((docLeaf = docs4num.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
          if (liveDocs != null && !liveDocs.get(docLeaf)) continue;
          docLength[docBase + docLeaf] = docs4num.longValue();
        }
      }
    }
    else if (info.hasVectors()) {
      Bits liveDocs = null;
      boolean hasDeletions = reader.hasDeletions();
      if (hasDeletions) {
        liveDocs = MultiBits.getLiveDocs(reader);
      }

      // get sum of terms by doc
      for (int i = 0; i < maxDoc; i++) {
        if (hasDeletions && !liveDocs.get(i)) {
          docLength[i] = -1;
          continue;
        }
        Terms vector = reader.getTermVector(i, field);
        // maybe no vector for this docid (ex : toc, book...)
        if (vector == null) continue;
        docLength[i] = vector.getSumTotalTermFreq(); // expensive on first call
      }
    }
    cache(key, docLength);
    return docLength;
  }

  /**
   * An analyzer used for query parsing
   */
  static class QueryAnalyzer extends Analyzer
  {

    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new TokenizerFr();
      TokenStream result = new TokenLem(source);
      result = new TokenCompound(result, 5);
      return new TokenStreamComponents(source, result);
    }

  }

  public static QueryAnalyzer qAnalyzer = new QueryAnalyzer();

  public static Query qParse(String q, String field) throws IOException
  {
    // float[] boosts = { 2.0f, 1.5f, 1.0f, 0.7f, 0.5f };
    // int boostLength = boosts.length;
    // float boostDefault = boosts[boostLength - 1];
    TokenStream ts = qAnalyzer.tokenStream(field, q);
    CharTermAttribute token = ts.addAttribute(CharTermAttribute.class);
    FlagsAttribute flags = ts.addAttribute(FlagsAttribute.class);

    ts.reset();
    Query qTerm = null;
    BooleanQuery.Builder bq = null;
    try {
      while (ts.incrementToken()) {
        if (Tag.isPun(flags.getFlags())) continue;
        if (qTerm != null) {
          if (bq == null) bq = new BooleanQuery.Builder();
          bq.add(qTerm, Occur.SHOULD);
        }
        qTerm = new TermQuery(new Term(field, token.toString()));
        /*
         * float boost = boostDefault; if (i < boostLength) boost = boosts[i]; qTerm =
         * new BoostQuery(qTerm, boost);
         */
      }
      ts.end();
    }
    finally {
      ts.close();
    }
    if (bq != null) {
      bq.add(qTerm, Occur.SHOULD);
      return bq.build();
    }
    return qTerm;
  }

  /**
   * Provide tokens as a table of terms
   * 
   * @param q
   * @param field
   * @return
   * @throws IOException
   */
  public TermList qTerms(String q, String field) throws IOException
  {

    TokenStream ts = qAnalyzer.tokenStream(field, q);
    CharTermAttribute token = ts.addAttribute(CharTermAttribute.class);
    CharsLemAtt lem = ts.addAttribute(CharsLemAtt.class);
    FlagsAttribute flags = ts.addAttribute(FlagsAttribute.class);

    TermList terms = new TermList(termFreqs(field));
    ts.reset();
    try {
      while (ts.incrementToken()) {
        final int tag = flags.getFlags();
        if (Tag.isPun(tag)) {
          // start a new line
          if (token.equals(";") || tag == Tag.PUNsent) {
            terms.add(null);
          }
          continue;
        }
        if (lem.length() > 0) terms.add(new Term(field, lem.toString()));
        else terms.add(new Term(field, token.toString()));
      }
      ts.end();
    }
    finally {
      ts.close();
    }
    return terms;
  }

  /** A row of data for a crossing axis */
  public static class Tick
  {
    public final int docid;
    public final int rank;
    public final long length;
    public long cumul;

    public Tick(final int docid, final int rank, final long length)
    {
      this.docid = docid;
      this.rank = rank;
      this.length = length;
    }

    @Override
    public String toString()
    {
      return "docid=" + docid + " rank=" + rank + " length=" + length + " cumul=" + cumul;
    }
  }

  /**
   * Data for an axis across all index. Measure is token count of a text field.
   * Order is given by an intPoint field (ex : year). Is returned in order of
   * docid. Not too expensive to calculate (~1 ms/1000 docs), but good to cache to
   * avoid multiplication of objects in web context. Cache is not assured here,
   * because data could be used in different order (docid order, or int field
   * order).
   *
   * @param textField
   * @param intPoint
   * @return
   * @throws IOException
   */
  public Tick[] axis(String textField, String intPoint) throws IOException
  {
    // long total = reader.getSumTotalTermFreq(textField);
    int maxDoc = reader().maxDoc();
    long[] docLength = docLength(textField);
    int[] docInt = docInt(intPoint);
    Tick[] axis = new Tick[maxDoc];
    // populate the index
    for (int i = 0; i < maxDoc; i++) {
      axis[i] = new Tick(i, docInt[i], docLength[i]);
    }
    // sort the index by date
    Arrays.sort(axis, new Comparator<Tick>()
    {
      @Override
      public int compare(Tick tick1, Tick tick2)
      {
        if (tick1.rank < tick2.rank) return -1;
        if (tick1.rank > tick2.rank) return +1;
        if (tick1.docid < tick2.docid) return -1;
        if (tick1.docid > tick2.docid) return +1;
        return 0;
      }
    });
    // update cumul
    long cumul = 0;
    for (int i = 0; i < maxDoc; i++) {
      long length = axis[i].length;
      if (length > 0) cumul += length;
      axis[i].cumul = cumul;
    }
    // resort in doc order
    Arrays.sort(axis, new Comparator<Tick>()
    {
      @Override
      public int compare(Tick tick1, Tick row2)
      {
        if (tick1.docid < row2.docid) return -1;
        if (tick1.docid > row2.docid) return +1;
        return 0;
      }
    });
    // update
    return axis;
  }

  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    sb.append(path + "\n");
    try {
      reader(); // get FieldInfos
    }
    catch (Exception e) {
    }
    for (FieldInfo info : fieldInfos) {
      sb.append(info.name + " PointDataDimensionCount=" + info.getPointDataDimensionCount() + " DocValuesType="
          + info.getDocValuesType() + " IndexOptions=" + info.getIndexOptions() + "\n");
    }
    sb.append(fieldInfos.toString());
    return sb.toString();
  }
}
