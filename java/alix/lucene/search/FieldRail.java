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

import alix.fr.Tag.TagFilter;
import alix.lucene.Alix;
import alix.util.IntList;
import alix.util.IntPair;
import alix.util.Top;
import alix.util.TopArray;

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
    
    
    int[] docLength = fieldText.docOccs;

    long capInt = headerInt + maxDoc;
    for (int i = 0; i < maxDoc; i++) {
      capInt += docLength[i] + 1 ;
    }
    
    MappedByteBuffer buf = channel.map(FileChannel.MapMode.READ_WRITE, 0, capInt * 4);
    // store the version 
    buf.putLong(reader.getVersion());
    // the intBuffer 
    IntBuffer bufint = buf.asIntBuffer();
    bufint.put(maxDoc);
    bufint.put(docLength);
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
  public long coocs(FormEnum dic) throws IOException
  {
    if (dic.search == null || dic.search.length == 0) throw new IllegalArgumentException("Search term(s) missing, FormEnum.search should be not null");
    final int left = dic.left;
    final int right = dic.right;
    if (left < 0 || right < 0 || (left + right) < 1) throw new IllegalArgumentException("FormEnum.left=" + left + " FormEnum.right=" + right + " not enough context to extract cooccurrences.");
    final BitSet filter = dic.filter;
    final boolean hasFilter = (filter != null);
    // create or reuse freqs
    if (dic.freqs == null || dic.freqs.length != maxForm) dic.freqs = new long[maxForm]; // by term, occurrences counts
    else Arrays.fill(dic.freqs, 0); // Renew, or not ?
    final long[] freqs = dic.freqs;
    // create or reuse hits
    if (dic.hits == null || dic.hits.length != maxForm) dic.hits = new int[maxForm]; // by term, document counts
    else Arrays.fill(dic.hits, 0); // Renew, or not ?
    final int[] hits = dic.hits;
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
      for (String word : dic.search) {
        if (word == null) continue;
        // Do not try to reuse search, expensive
        Term term = new Term(fieldName, word);
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
        partOccs += contexts.cardinality();
        // TODISCUSS substract search from contexts
        // contexts.andNot(search);
        // load the document rail and loop on the contexts to count co-occurrents
        final int posDoc = posInt[docId];
        int pos = contexts.nextSetBit(0);
        Arrays.fill(docSeen, false);
        while (pos >= 0) {
          int formId = bufInt.get(posDoc + pos);
          freqs[formId]++;
          if (!docSeen[formId]) {
            hits[formId]++;
            docSeen[formId] = true;
          }
          pos = contexts.nextSetBit(pos+1);
        }
      }
    }
    dic.partOccs = partOccs; // add or renew ?
    return found;
  }

  /**
   * Scores a {@link FormEnum} with freqs extracted by {@link #coocs(FormEnum)}.
   * For the {@link Specif} algorithm, the search and co-occurrences contexts are considered as a part in the corpus.
   * For tf-idf like scoring, this part is considered like a single document.
   */
  // In case of a corpus filter, shall we score compared to full corpus or only the part filtered?
  public void score(FormEnum dic)
  {
    if (dic.limit == 0) throw new IllegalArgumentException("How many sorted forms do you want? set FormEnum.limit");
    if (dic.partOccs < 1) throw new IllegalArgumentException("Scoring this FormEnum need the count of occurrences in the part, set FormEnum.partOccs");
    long partOccs = dic.partOccs;
    if (dic.freqs == null || dic.freqs.length != maxForm) throw new IllegalArgumentException("Scoring this FormEnum required a freqList, set FormEnum.freqs");
    long[] freqs = dic.freqs;
    if (dic.hits == null || dic.hits.length != maxForm) throw new IllegalArgumentException("Scoring this FormEnum required a doc count by formId in FormEnum.hits");
    int[] hits = dic.hits;
    
    // final long[] freqs, final int limit, Specif specif, final TagFilter tags, final boolean reverse
    Specif specif = dic.specif;
    if (specif == null) specif = new SpecifOccs();
    boolean isTfidf = (specif!= null && specif.type() == Specif.TYPE_TFIDF);
    boolean isProb = (specif!= null && specif.type() == Specif.TYPE_PROB);
    TagFilter tags = dic.tags;
    boolean hasTags = (tags != null);
    boolean noStop = (tags != null && tags.noStop());
    // localize
    long[] formAllOccs = fieldText.formAllOccs;
    int[] formAllDocs = fieldText.formAllDocs;
    
    
    // loop on all freqs to calculate scores
    specif.all(fieldText.occsAll, fieldText.docsAll);
    specif.part(partOccs, 1);
    int length = freqs.length;
    double[] scores = new double[length];
    for (int formId = 0; formId < length; formId++) {
      if (noStop && fieldText.isStop(formId)) continue;
      if (hasTags && !tags.accept(fieldText.formTag[formId])) continue;
      if (freqs[formId] == 0) continue;
      if (isTfidf) {
        specif.idf(formAllOccs[formId], formAllDocs[formId]);
        scores[formId] = specif.tf(freqs[formId], partOccs);
      }
      else if (isProb) {
        scores[formId] = specif.prob(freqs[formId], formAllOccs[formId]);
      }
    }

    dic.scores = scores;
    TopArray top;
    int flags = TopArray.NO_ZERO;
    if (dic.reverse) flags |= TopArray.REVERSE;
    if (dic.limit < 1) top = new TopArray(scores, flags); // all search
    else top = new TopArray(dic.limit, scores, flags);
    
    dic.sorter(top.toArray());
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
   * Loop on the rail to find expression (2 plain word with 
   * @return
   * @throws IOException
   */
  public Top<IntPair> expressions(int limit) throws IOException
  {
    Map<IntPair, Counter> expressions = new HashMap<IntPair, Counter>();

    IntPair key = new IntPair();
    
    int maxDoc = this.maxDoc;
    int[] posInt = this.posInt;
    int[] limInt = this.limInt;
    BitSet stops = fieldText.stops;
    // no cost in time and memory to take one int view, seems faster to loop
    IntBuffer bufInt = channelMap.rewind().asIntBuffer();
    int lastId = 0;
    for (int docId = 0; docId < maxDoc; docId++) {
      if (limInt[docId] == 0) continue; // deleted or with no value for this field
      // if (hasFilter && !filter.get(docId)) continue; // document not in the filter
      bufInt.position(posInt[docId]);
      for (int i = 0, max = limInt[docId] ; i < max; i++) {
        int formId = bufInt.get();
        if (stops.get(formId)) continue;
        key.set(lastId, formId);
        Counter count = expressions.get(key);
        if (count != null) count.inc();
        else expressions.put(new IntPair(key), new Counter());
        lastId = formId;
      }
    }
    StringBuilder sb = new StringBuilder();
    Top<IntPair> top= new Top<IntPair>(limit);
    for (Map.Entry<IntPair, Counter> entry: expressions.entrySet()) {
      top.push(entry.getValue().count, entry.getKey());
    }

    return top;
  }

  class Counter
  {
    int count = 1;
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