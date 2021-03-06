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

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.stream.IntStream;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;

import alix.fr.Tag;
import alix.fr.Tag.TagFilter;
import alix.lucene.Alix;
import alix.util.Chain;
import alix.util.IntList;
import alix.util.IntPair;
import alix.util.Top;
import alix.util.TopArray;
import alix.web.MI;
import alix.web.Webinf;

/**
 * Persistent storage of full sequence of all document search for a field.
 * Used for co-occurrences stats.
 * Data structure of the file
 * <p>
 * int:maxDoc, maxDoc*[int:docLength], maxDoc*[docLength*[int:formId], int:-1]
 * 
 * 
 * @author fred
 *
 */
// ChronicleMap has been tested, but it is not more than x2 compared to lucene BinaryField.
public class FieldRail
{
  static Logger LOGGER = Logger.getLogger(FieldRail.class.getName());
  /** State of the index */
  private final Alix alix;
  /** Name of the reference text field */
  private final String fieldName;
  /** Keep the freqs for the field */
  private final FieldText fieldText;
  /** Dictionary of search for this field */
  private final BytesRefHash hashDic;
  /** The path of underlaying file store */
  private final Path path;
  /** Cache a fileChannel for read */
  private FileChannel channel;
  /** A buffer on file */
  private MappedByteBuffer channelMap;
  /** Max for docId */
  private int maxDoc;
  /** Max for formId */
  private final int maxForm;
  /** Size of file header */
  static final int headerInt = 3;
  /** Index of  positions for each doc im channel */
  private int[] posInt;
  /** Index of sizes for each doc */
  private int[] limInt;


  public FieldRail(Alix alix, String field) throws IOException
  {
    this.alix = alix;
    this.fieldName = field;
    this.fieldText = alix.fieldText(field); // build and cache the dictionary for the field
    this.hashDic = fieldText.formDic;
    this.maxForm = hashDic.size();
    this.path = Paths.get( alix.path.toString(), field+".rail");
    load();
  }
  
  /**
   * Reindex all documents of the text field as an int vector
   * storing search at their positions
   * {@link org.apache.lucene.document.BinaryDocValuesField}.
   * Byte ordering is the java default.
   * 
   * @throws IOException 
   */
  private void store() throws IOException
  {
    final FileLock lock;
    DirectoryReader reader = alix.reader();
    int maxDoc = reader.maxDoc();
    final FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.READ, StandardOpenOption.WRITE);
    lock = channel.lock(); // may throw OverlappingFileLockException if someone else has lock
    
    
    // get document sizes
    // fieldText.docOccs is not correct because of holes
    int[] docLen = new int[maxDoc];
    for (int docId = 0; docId < maxDoc; docId++) {
      Terms termVector = reader.getTermVector(docId, fieldName);
      docLen[docId] = length(termVector);
    }

    long capInt = headerInt + maxDoc;
    for (int i = 0; i < maxDoc; i++) {
      capInt += docLen[i] + 1 ;
    }
    
    MappedByteBuffer buf = channel.map(FileChannel.MapMode.READ_WRITE, 0, capInt * 4);
    // store the version 
    buf.putLong(reader.getVersion());
    // the intBuffer 
    IntBuffer bufint = buf.asIntBuffer();
    bufint.put(maxDoc);
    bufint.put(docLen);
    IntList ints = new IntList();
    
    for (int docId = 0; docId < maxDoc; docId++) {
      Terms termVector = reader.getTermVector(docId, fieldName);
      if (termVector == null) {
        bufint.put(-1);
        continue;
      }
      rail(termVector, ints);
      bufint.put(ints.data(), 0, ints.length());
      bufint.put(-1);
    }
    buf.force();
    channel.force(true);
    lock.close();
    channel.close();
  }
  
  /**
   * Load and calculate index for the rail file
   * @throws IOException 
   */
  private void load() throws IOException
  {
    // file do not exists, store()
    if (!path.toFile().exists()) store();
    channel = FileChannel.open(path, StandardOpenOption.READ);
    DataInputStream data = new DataInputStream(Channels.newInputStream(channel));
    long version = data.readLong();
    // bad version reproduce
    if (version != alix.reader().getVersion()) {
      data.close();
      channel.close();
      store();
      channel = FileChannel.open(path, StandardOpenOption.READ);
      data = new DataInputStream(Channels.newInputStream(channel));
    }
    
    int maxDoc = data.readInt();
    this.maxDoc = maxDoc;
    int[] posInt = new int[maxDoc];
    int[] limInt = new int[maxDoc];
    int indInt = headerInt + maxDoc;
    for(int i = 0; i < maxDoc; i++) {
      posInt[i] = indInt;
      int docLen = data.readInt();
      limInt[i] = docLen;
      indInt += docLen +1;
    }
    this.posInt = posInt;
    this.limInt = limInt;
    this.channelMap = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
  }
  
  public String toString(int docId) throws IOException
  {
    int limit = 100;
    StringBuilder sb = new StringBuilder();
    IntBuffer bufInt = channelMap.position(posInt[docId] * 4).asIntBuffer();
    bufInt.limit(limInt[docId]);
    BytesRef ref = new BytesRef();
    while (bufInt.hasRemaining()) {
      int formId = bufInt.get();
      this.hashDic.get(formId, ref);
      sb.append(ref.utf8ToString());
      sb.append(" ");
      if (limit-- <= 0) {
        sb.append("[…]");
      }
    }
    return sb.toString();
  }
  
  
  /**
   * From a set of documents provided as a BitSet,
   * return a freqlist as an int vector,
   * where index is the formId for the field,
   * the value is count of occurrences of the term.
   * Counts are extracted from stored <i>rails</i>.
   * @throws IOException 
   */
  public long[] freqs(final BitSet filter) throws IOException
  {
    long[] freqs = new long[hashDic.size()];
    final boolean hasFilter = (filter != null);
    int maxDoc = this.maxDoc;
    int[] posInt = this.posInt;
    int[] limInt = this.limInt;
    
    
    /* 
    // if channelMap is copied here as int[], same cost as IntBuffer
    // gain x2 if int[] is preloaded at class level, but cost mem
    int[] rail = new int[(int)(channel.size() / 4)];
    channelMap.asIntBuffer().get(rail);
     */
    // no cost in time and memory to take one int view, seems faster to loop
    IntBuffer bufInt = channelMap.rewind().asIntBuffer();
    for (int docId = 0; docId < maxDoc; docId++) {
      if (limInt[docId] == 0) continue; // deleted or with no value for this field
      if (hasFilter && !filter.get(docId)) continue; // document not in the filter
      bufInt.position(posInt[docId]);
      for (int i = 0, max = limInt[docId] ; i < max; i++) {
        int formId = bufInt.get();
        freqs[formId]++;
      }
    }
    return freqs;
  }

  /**
   * Build  a cooccurrence freqList in formId order, attached to a FormEnum object.
   * This oculd be sorted after in different manner accordin to Specif.
   * Returns the count of occurences found.
   */
  public long coocs(FormEnum results) throws IOException
  {
    if (results.search == null || results.search.length == 0) throw new IllegalArgumentException("Search term(s) missing, FormEnum.search should be not null");
    final int left = results.left;
    final int right = results.right;
    if (left < 0 || right < 0 || (left + right) < 1) throw new IllegalArgumentException("FormEnum.left=" + left + " FormEnum.right=" + right + " not enough context to extract cooccurrences.");
    
    // for future scoring, formOccs is global or relative to filter ? relative seems bad
    /*
    if (results.filter != null) {
      fieldText.filter(results);
      results.N = results.partOccs;
    }
    */
    results.formOccs = fieldText.formAllOccs;
    final BitSet filter = results.filter;
    final boolean hasFilter = (filter != null);
    
    
    // create or reuse freqs
    if (results.freqs == null || results.freqs.length != maxForm) results.freqs = new long[maxForm]; // by term, occurrences counts
    else Arrays.fill(results.freqs, 0);
    final long[] freqs = results.freqs; // localize
    // create or reuse hits
    if (results.hits == null || results.hits.length != maxForm) results.hits = new int[maxForm]; // by term, document counts
    else Arrays.fill(results.hits, 0);
    final int[] hits = results.hits; // localize
    
    
    // A vector needed to no recount doc
    boolean[] docSeen = new boolean[maxForm];
    long found = 0;
    final int END = DocIdSetIterator.NO_MORE_DOCS;
    DirectoryReader reader = alix.reader();
    // collector of scores
    int dicSize = this.hashDic.size();
    
    long partOccs = 0;

    // for each doc, a bit set is used to record the relevant positions
    // this will avoid counting interferences when search occurrences are close
    java.util.BitSet contexts = new java.util.BitSet();
    java.util.BitSet pivots = new java.util.BitSet();
    IntBuffer bufInt = channelMap.rewind().asIntBuffer();
    // loop on leafs
    for (LeafReaderContext context : reader.leaves()) {
      final int docBase = context.docBase;
      LeafReader leaf = context.reader();
      // collect all “postings” for the requested search
      ArrayList<PostingsEnum> termDocs = new ArrayList<PostingsEnum>();
      for (String word : results.search) {
        if (word == null) continue;
        Term term = new Term(fieldName, word); // do not try to reuse term, false optimisation
        PostingsEnum postings = leaf.postings(term, PostingsEnum.FREQS|PostingsEnum.POSITIONS);
        if (postings == null) continue;
        final int docPost = postings.nextDoc(); // advance cursor to the first doc
        if (docPost == END) continue;
        termDocs.add(postings);
      }
      // loop on all documents for this leaf
      final int max = leaf.maxDoc();
      final Bits liveDocs = leaf.getLiveDocs();
      final boolean hasLive = (liveDocs != null);
      for (int docLeaf = 0; docLeaf < max; docLeaf++) {
        final int docId = docBase + docLeaf;
        final int docLen = limInt[docId];
        if (hasFilter && !filter.get(docId)) continue; // document not in the document filter
        if (hasLive && !liveDocs.get(docLeaf)) continue; // deleted doc
        // reset the positions of the rail
        contexts.clear();
        pivots.clear();
        boolean hit = false;
        // loop on each term iterator to get positions for this doc
        for (PostingsEnum postings: termDocs) {
          int docPost = postings.docID(); // get current doc for these term postings
          if (docPost == docLeaf);
          else if (docPost == END) continue; // end of postings, try next term
          else if (docPost > docLeaf) continue; // postings ahead of current doc, try next term 
          else if (docPost < docLeaf) {
            docPost = postings.advance(docLeaf); // try to advance postings to this doc
            if (docPost > docLeaf) continue; // next doc for this term is ahead current term
          }
          if (docPost != docLeaf) System.out.println("BUG cooc, docLeaf=" + docLeaf + " docPost=" + docPost); // ? bug ?;
          int freq = postings.freq();
          if (freq == 0) System.out.println("BUG cooc, term=" + postings.toString() + " docId="+docId+" freq=0"); // ? bug ?
          
          hit = true;
          for (; freq > 0; freq--) {
            final int position = postings.nextPosition();
            final int fromIndex = Math.max(0, position - left);
            final int toIndex = Math.min(docLen, position + right + 1); // be careful of end Doc
            contexts.set(fromIndex, toIndex);
            pivots.set(position);
            found++;
          }
          // postings.advance(docLeaf);
        }
        if (!hit) continue;
        // count all freqs with pivot 
        // partOccs += contexts.cardinality(); // no, do not count holes
        // TODISCUSS substract search from contexts
        // contexts.andNot(search);
        // load the document rail and loop on the contexts to count co-occurrents
        final int posDoc = posInt[docId];
        int pos = contexts.nextSetBit(0);
        Arrays.fill(docSeen, false);
        while (pos >= 0) {
          int formId = bufInt.get(posDoc + pos);
          pos = contexts.nextSetBit(pos+1);
          if (formId == 0) continue;
          partOccs++;
          freqs[formId]++;
          if (!docSeen[formId]) {
            hits[formId]++;
            docSeen[formId] = true;
          }
        }
      }
    }
    results.partOccs = partOccs; // sum of coocs traversed
    results.reset();
    return found;
  }

  /**
   * Scores a {@link FormEnum} with freqs extracted from co-occurrences extraction in a  {@link #coocs(FormEnum)}.
   * Scoring uses a “mutual information” {@link MI} algorithm (probability like, not tf-idf like). Parameters
   * are 
   * <li>Oab: count of a form (a) observed in a co-occurrency context (b)
   * <li>Oa: count of a form in full corpus, or in a section (filter)
   * <li>Ob: count of occs of the co-occurrency context
   * <li>N: global count of occs from which is extracted the context (full corpus or filterd section)
   * @throws IOException 
   * 
   */
  public void score(FormEnum results, final long Ob) throws IOException
  {
    if (results.limit == 0) throw new IllegalArgumentException("How many sorted forms do you want? set FormEnum.limit");
    if (results.partOccs < 1) throw new IllegalArgumentException("Scoring this FormEnum need the count of occurrences in the part, set FormEnum.partOccs");
    if (results.freqs == null || results.freqs.length != maxForm) throw new IllegalArgumentException("Scoring this FormEnum required a freqList, set FormEnum.freqs");
    long[] freqs = results.freqs;
    // int[] hits = results.hits; // not significant for a transversal cooc
    TagFilter tags = results.tags;
    boolean hasTags = (tags != null);
    boolean noStop = (tags != null && tags.noStop());
    int length = freqs.length;
    // reuse score for multiple calculations
    if (results.scores == null || results.scores.length != length) results.scores = new double[length]; // by term, occurrences counts
    else Arrays.fill(results.scores, 0);
    double[] scores = results.scores; // localize
    
    // localize the scoring reference
    final long N = fieldText.allOccs; // global 
    final long[] formOccs = fieldText.formAllOccs; // global for all base
    // final long[] formOccs = results.formOccs; // do not restrict 
    final int[] formDocs = fieldText.formAllDocs;
    // final int[] formDocs = results.formDocs;
    final long partOccs = results.partOccs;
    MI mi = results.mi;
    if (mi == null) mi = MI.occs;
    
 
    
    for (int formId = 0; formId < length; formId++) {
      if (noStop && fieldText.isStop(formId)) continue;
      if (hasTags && !tags.accept(fieldText.formTag[formId])) continue;
      if (freqs[formId] == 0) continue;
      long Oab = freqs[formId];
      if (Oab > Ob) Oab = Ob; // // a form in a cooccurrence, may be more frequent than the pivot (repetition in a large context)
      scores[formId] = mi.score(Oab, formOccs[formId], Ob, N);
    }
    TopArray top;
    int flags = TopArray.NO_ZERO;
    if (results.reverse) flags |= TopArray.REVERSE;
    if (results.limit < 1) top = new TopArray(scores, flags); // all search
    else top = new TopArray(results.limit, scores, flags);
    results.sorter(top.toArray());
  }
  
  /**
   * Count document size by the positions in the term vector
   */
  public int length(Terms termVector) throws IOException
  {
    if (termVector == null) return 0;
    int len = Integer.MIN_VALUE;
    TermsEnum tenum = termVector.iterator();
    PostingsEnum postings = null;
    while (tenum.next() != null) {
      postings = tenum.postings(postings, PostingsEnum.POSITIONS);
      postings.nextDoc(); // always one doc
      int freq = postings.freq();
      for (int i = 0; i < freq; i++) {
        int pos = postings.nextPosition();
        if (pos > len) len = pos;
      }
    }
    return len + 1;
  }
  
  
  /**
   * Flatten search of a document in a position order, according to the dictionary of search.
   * Write it in a binary buffer, ready to to be stored in a BinaryField.
   * {@link org.apache.lucene.document.BinaryDocValuesField}
   * The buffer could be modified if resizing was needed.
   * @param termVector A term vector of a document with positions.
   * @param buf A reusable binary buffer to index.
   * @throws IOException
   */
  public void rail(Terms termVector, IntList buf) throws IOException
  {
    buf.reset(); // celan all
    BytesRefHash hashDic = this.hashDic;
    TermsEnum tenum = termVector.iterator();
    PostingsEnum postings = null;
    BytesRef bytes = null;
    int maxpos = -1;
    int minpos = Integer.MAX_VALUE;
    while ((bytes = tenum.next()) != null) {
      int formId = hashDic.find(bytes);
      if (formId < 0) System.out.println("unknown term? \""+bytes.utf8ToString() + "\"");
      postings = tenum.postings(postings, PostingsEnum.POSITIONS);
      postings.nextDoc(); // always one doc
      int freq = postings.freq();
      for (int i = 0; i < freq; i++) {
        int pos = postings.nextPosition();
        if (pos > maxpos) maxpos = pos;
        if (pos < minpos) minpos = pos;
        buf.put(pos, formId);
      }
    }
  }
  
  /**
   * Loop on the rail to find expression (2 plain words with possible stop words between 
   * but not holes or punctuation)
   * @return
   * @throws IOException
   */
  public Map<IntPair, Bigram> expressions(final BitSet filter, final boolean parceque) throws IOException
  {
    final boolean hasFilter = (filter != null);
    Map<IntPair, Bigram> expressions = new HashMap<IntPair, Bigram>();
    
    int maxDoc = this.maxDoc;
    int[] posInt = this.posInt;
    int[] limInt = this.limInt;
    BitSet stops = fieldText.formStop;
    int[] formTag = fieldText.formTag;
    BytesRefHash formDic = fieldText.formDic;
    // no cost in time and memory to take one int view, seems faster to loop
    IntBuffer bufInt = channelMap.rewind().asIntBuffer();
    // a vector to record formId events
    IntList slider = new IntList();
    Chain chain = new Chain();
    BytesRef bytes = new BytesRef();
    for (int docId = 0; docId < maxDoc; docId++) {
      if (limInt[docId] == 0) continue; // deleted or with no value for this field
      if (hasFilter && !filter.get(docId)) continue; // document not in the filter
      bufInt.position(posInt[docId]); // position cursor in the rail
      IntPair key = new IntPair();
      for (int i = 0, max = limInt[docId] ; i < max; i++) {
        int formId = bufInt.get();
        // pun or hole, reste expression
        int tag = formTag[formId];
        if (formId == 0 || Tag.PUN.sameParent(tag)) {
          slider.reset();
          continue;
        }
        final boolean isStop = (formId < stops.length() && stops.get(formId));
        if (!parceque && isStop) {
          if (slider.isEmpty()) continue;
          // être probably not iniside a compoud
          if (Tag.VERB.sameParent(tag)) {
            slider.reset();
            continue;
          }
          slider.push(formId);
          continue;
        }
        // should be a plain word here
        if (slider.isEmpty()) { // start of an expression
          slider.push(formId);
          continue;
        }
        // here we have something to test or to store
        slider.push(formId); // don’t forget the cuurent formId
        key.set(slider.first(), formId);
        Bigram bigram = expressions.get(key);
        if (bigram == null) { // new expression
          chain.reset();
          for (int jj = 0, len = slider.length(); jj < len; jj++) {
            if (jj > 0 && chain.last() != '\'') chain.append(' ');
            formDic.get(slider.get(jj), bytes);
            chain.append(bytes);
          }
          bigram = new Bigram(key.x(), key.y(), chain.toString());
          expressions.put(new IntPair(key), bigram);
        }
        bigram.inc();
        // reset candidate compound, and start by current form
        slider.reset().push(formId);
      }
    }
    return expressions;
  }
  
  /**
   * Loop on the rail to get bigrams without any intelligence.
   * @return
   * @throws IOException
   */
  public Map<IntPair, Bigram> bigrams(final BitSet filter) throws IOException
  {
    final boolean hasFilter = (filter != null);
    Map<IntPair, Bigram> dic = new HashMap<IntPair, Bigram>();
    IntPair key = new IntPair();
    int maxDoc = this.maxDoc;
    int[] posInt = this.posInt;
    int[] limInt = this.limInt;
    IntBuffer bufInt = channelMap.rewind().asIntBuffer();
    int lastId = 0;
    for (int docId = 0; docId < maxDoc; docId++) {
      if (hasFilter && !filter.get(docId)) continue; 
      if (limInt[docId] == 0) continue; // deleted or with no value for this field
      bufInt.position(posInt[docId]);
      for (int i = 0, max = limInt[docId] ; i < max; i++) {
        int formId = bufInt.get();
        // here we can skip holes, pun, or stop words
        // if (formStop.get(formId)) continue;
        key.set(lastId, formId);
        Bigram count = dic.get(key);
        if (count != null) count.inc();
        else dic.put(new IntPair(key), new Bigram(key.x(), key.y()));
        lastId = formId;
      }
    }
    return dic;
  }


  public class Bigram
  {
    public final int a;
    public final int b;
    public int count = 0;
    public double score;
    final public String label;
    Bigram (final int a, final int b) {
      this.a = a;
      this.b = b;
      this.label = null;
    }
    Bigram (final int a, final int b, final String label) {
      this.a = a;
      this.b = b;
      this.label = label;
    }
    public int inc()
    {
      return ++count;
    }
  }

  
  /**
   * Tokens of a doc as strings from a byte array
   * @param rail Binary version an int array
   * @param offset Start index in the array
   * @param length Length of bytes to consider from offset
   * @return
   * @throws IOException
   */
  public String[] strings(int[] rail) throws IOException
  {
    int len = rail.length;
    String[] words = new String[len];
    BytesRef ref = new BytesRef();
    for (int i = 0; i < len; i++) {
      int formId = rail[i];
      this.hashDic.get(formId, ref);
      words[i] = ref.utf8ToString();
    }
    return words;
  }
  
  /**
   * Parallel freqs calculation, it works but is more expensive than serial,
   * because of concurrency cost.
   * 
   * @param filter
   * @return
   * @throws IOException
   */
  protected AtomicIntegerArray freqsParallel(final BitSet filter) throws IOException
  {
    // may take big place in mem
    int[] rail = new int[(int)(channel.size() / 4)];
    channelMap.asIntBuffer().get(rail);
    AtomicIntegerArray freqs = new AtomicIntegerArray(hashDic.size());
    boolean hasFilter = (filter != null);
    int maxDoc = this.maxDoc;
    int[] posInt = this.posInt;
    int[] limInt = this.limInt;
    
    IntStream loop = IntStream.range(0, maxDoc).filter(docId -> {
      if (limInt[docId] == 0) return false;
      if (hasFilter && !filter.get(docId)) return false;
      return true;
    }).parallel().map(docId -> {
      // to use a channelMap in parallel, we need a new IntBuffer for each doc, too expensive
      for (int i = posInt[docId], max = posInt[docId] + limInt[docId] ; i < max; i++) {
        int formId = rail[i];
        freqs.getAndIncrement(formId);
      }
      return docId;
    });
    loop.count(); // go
    return freqs;
  }

}
