package alix.lucene.util;
/**
 * A co-occurrences scanner in a  {@link org.apache.lucene.document.TextField} of a lucene index.
 * This field should store term vectors with positions
 * {@link org.apache.lucene.document.FieldType#setStoreTermVectorPositions(boolean)}.
 * Efficiency is based on a pre-indexation of each document
 * as an int vector where each int is a term at its position
 * (a “rail”).
 * This object should be created on a “dead index”, 
 * with all writing operations commited.
 * 
 * In the method 
 */

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;

import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;

import alix.lucene.Alix;
import alix.lucene.search.Freqs;
import alix.lucene.search.Scorer;
import alix.lucene.search.ScorerBM25;
import alix.lucene.search.TermList;
import alix.lucene.search.TopTerms;
import alix.util.Calcul;

public class Cooc
{
  /** Suffix for a binary field containing tokens by position */
  private static final String _RAIL = "_rail";
  /** Name of the reference text field */
  private final String field;
  /** Name of the binary field storing the int vector of documents */
  private final String fieldBin;
  /** Keep the freqs for the fiels */
  private final Freqs freqs;
  /** Dictionary of terms for this field */
  private final BytesRefHash hashDic;
  /** State of the index */
  private final Alix alix;
  /**
   * Build a co-occurrences scanner.
   * @param alix A link to a lucene Index, with tools to get terms.
   * @param field A text field name with term vectors.
   * @throws IOException
   */
  public Cooc(Alix alix, String field) throws IOException
  {
    this.alix = alix;
    this.field = field;
    this.fieldBin = field + _RAIL;
    this.freqs = alix.freqs(field); // build and cache the dictionary of cache for the field
    this.hashDic = freqs.hashDic();
  }
  
  
  /**
   * Flaten terms of a document in a position order, according to the dictionary of terms.
   * Write it in a binary buffer, ready to to be stored in a BinaryField.
   * {@link org.apache.lucene.document.BinaryDocValuesField}
   * The buffer could be modified if resizing was needed.
   * @param termVector A term vector of a document with positions.
   * @param buf A reusable binary buffer to index.
   * @return
   * @throws IOException
   */
  public ByteBuffer rail(Terms termVector, ByteBuffer buf) throws IOException
  {
    int capacity = buf.capacity();
    buf.clear(); // reset markers but do not erase
    // tested, 2x faster than System.arraycopy after 5 iterations
    Arrays.fill(buf.array(), (byte)0);
    BytesRefHash hashDic = this.hashDic;
    TermsEnum tenum = termVector.iterator();
    PostingsEnum postings = null;
    BytesRef bytes = null;
    int length = -1; // length of the array
    while ((bytes = tenum.next()) != null) {
      int termId = hashDic.find(bytes);
      if (termId < 0) System.out.println("unknown term? "+bytes.utf8ToString());
      postings = tenum.postings(postings, PostingsEnum.POSITIONS);
      postings.nextDoc(); // always one doc
      int freq = postings.freq();
      for (int i = 0; i < freq; i++) {
        int pos = postings.nextPosition();
        int index = pos * 4;
        if (capacity < (index+4)) {
          capacity = Calcul.nextSquare(index+4);
          ByteBuffer expanded = ByteBuffer.allocate(capacity);
          expanded.order(buf.order());
          expanded.put(buf);
          buf = expanded;
        }
        if (length < (index+4)) length = index+4;
        buf.putInt(index, termId);
      }
    }
    buf.limit(length);
    return buf;
  }
  
  /**
   * Reindex all documents of the text field as an int vector
   * storing terms and their positions
   * {@link org.apache.lucene.document.BinaryDocValuesField}
   * @throws IOException 
   */
  public void write() throws IOException
  {
    // known issue, writer should have been closed before reindex
    IndexWriter writer = alix.writer();
    IndexReader reader = alix.reader(writer);
    int maxDoc = reader.maxDoc();
    // create a byte buffer, big enough to store all docs
    ByteBuffer buf =  ByteBuffer.allocate(2);
    // buf.order(ByteOrder.LITTLE_ENDIAN);
    
    for (int docId = 0; docId < maxDoc; docId++) {
      Terms termVector = reader.getTermVector(docId, field);
      if (termVector == null) continue;
      buf = rail(termVector, buf); // reusable buffer may be 
      BytesRef ref =  new BytesRef(buf.array(), buf.arrayOffset(), buf.limit());
      Field field = new BinaryDocValuesField(fieldBin, ref);
      long code =  writer.tryUpdateDocValue(reader, docId, field);
      if( code < 0) System.out.println("Field \""+fieldBin+"\", update error for doc="+docId+" ["+code+"]");
    }
    reader.close();
    writer.commit();
    writer.forceMerge(1);
    writer.close();
    alix.reader(true); // renew reader, to view the new field
  }

  
  /**
   * Get cooccurrences fron a multi term query.
   * Each document should be available as an int vector
   * see {@link rail()}.
   * A loop will cross all docs, and 
   * @param terms List of terms to search accross docs to get positions.
   * @param left Number of tokens to catch at the left of the pivot.
   * @param right Number of tokens to catch at the right of the pivot.
   * @param filter Optional filter to limit the corpus of documents.
   * @throws IOException
   */
  public TopTerms topTerms(final TermList terms, final int left, final int right, final BitSet filter) throws IOException
  {
    TopTerms dic = new TopTerms(hashDic);
    // BM25 seems the best scorer
    Scorer scorer = new ScorerBM25(); 
    /*
    scorer.setAll(occsAll, docsAll);
    dic.setLengths(termLength);
    dic.setDocs(termDocs);
    */
    IndexReader reader = alix.reader();
    final int END = DocIdSetIterator.NO_MORE_DOCS;
    // collector of scores
    int size = this.hashDic.size();
    int[] freqs = new int[size];
    int[] hits = new int[size];
    // to count documents, a set to count only first occ in a doc
    java.util.BitSet dicSet = new java.util.BitSet(size);

    // for each doc, a bit set is used to record the relevant positions
    // this will avoid counting interferences when terms are close
    java.util.BitSet contexts = new java.util.BitSet();
    java.util.BitSet pivots = new java.util.BitSet();
    // loop on leafs
    for (LeafReaderContext context : reader.leaves()) {
      int docBase = context.docBase;
      int docLeaf;
      LeafReader leaf = context.reader();
      // loop carefully on docs with a rail
      BinaryDocValues binDocs = leaf.getBinaryDocValues(fieldBin);
      // probably nothing indexed
      if (binDocs == null) return null; 
      // start iterators for each term
      ArrayList<PostingsEnum> list = new ArrayList<PostingsEnum>();
      for (Term term : terms) {
        if (term == null) continue;
        PostingsEnum postings = leaf.postings(term, PostingsEnum.FREQS|PostingsEnum.POSITIONS);
        if (postings == null) continue;
        postings.nextDoc(); // advance cursor to the first doc
        list.add(postings);
      }
      PostingsEnum[] termDocs = list.toArray(new PostingsEnum[0]);
      final Bits liveDocs = leaf.getLiveDocs();
      while ( (docLeaf = binDocs.nextDoc()) != END) {
        if (liveDocs != null && !liveDocs.get(docLeaf)) continue; // deleted doc
        int docId = docBase + docLeaf;
        if (filter != null && !filter.get(docId)) continue; // document not in the metadata fillter
        
        boolean found = false;
        contexts.clear();
        pivots.clear();
        // loop on term iterator to get positions for this doc
        for (PostingsEnum postings: termDocs) {
          int doc = postings.docID();
          if (doc == END || doc > docLeaf) continue;
          // 
          if (doc < docLeaf) doc = postings.advance(docLeaf - 1);
          if (doc > docLeaf) continue;
          int freq = postings.freq();
          if (freq == 0) continue;
          found = true;
          for (; freq > 0; freq --) {
            final int position = postings.nextPosition();
            final int fromIndex = Math.max(0, position - left);
            final int toIndex = position + right + 1; // toIndex (exclusive)
            contexts.set(fromIndex, toIndex);
            pivots.set(position);
          }
        }
        if (!found) continue;
        // substract pivots from context, this way should avoid counting pivot
        contexts.andNot(pivots);
        BytesRef ref = binDocs.binaryValue();
        ByteBuffer buf = ByteBuffer.wrap(ref.bytes, ref.offset, ref.length);
        // loop on the positions 
        int pos = contexts.nextSetBit(0);
        if (pos < 0) continue; // word found but without context, ex: first word without left
        int max = ref.length - 3;
        dicSet.clear(); // clear the tern set, to count only first occ as doc
        while (true) {
          int index = pos*4;
          if (index >= max) break; // position further than available tokens
          int termId = buf.getInt(pos*4);
          freqs[termId]++;
          if (!dicSet.get(termId)) {
            hits[termId]++;
            dicSet.set(termId);
          }
          pos = contexts.nextSetBit(pos+1);
          if (pos < 0) break; // no more positions
        }
      }
    }
    // try to calculate a score
    double[] scores = new double[size];
    // TODO
    for (int i = 0; i < size; i++) {
      
    }
    dic.setHits(hits);
    dic.setOccs(freqs);
    return dic;
  }
  
  /**
   * Get the token sequence of a document.
   * @throws IOException 
   * 
   */
  public  String[] sequence(int docId) throws IOException
  {
    IndexReader reader = alix.reader();
    for (LeafReaderContext context : reader.leaves()) {
      int docBase = context.docBase;
      if (docBase > docId) return null;
      int docLeaf = docId - docBase;
      LeafReader leaf = context.reader();
      BinaryDocValues binDocs = leaf.getBinaryDocValues(fieldBin);
      if (binDocs == null) return null;
      int docFound = binDocs.advance(docLeaf);
      // maybe found on next leaf
      if (docFound == DocIdSetIterator.NO_MORE_DOCS) continue;
      // docId not found
      if (docFound != docLeaf) return null;
      BytesRef ref = binDocs.binaryValue();
      return strings(ref);
    }
    return null;
  }
  
  /**
   * Get the tokens of a term vector as an array of strings.
   * @param termVector
   * @return
   * @throws IOException
   */
  public static String[] strings(Terms termVector) throws IOException
  {
    TermsEnum tenum = termVector.iterator();
    PostingsEnum postings = null;
    String[] words = new String[1000];
    BytesRef bytes = null;
    while ((bytes = tenum.next()) != null) {
      postings = tenum.postings(postings, PostingsEnum.POSITIONS);
      postings.nextDoc(); // always one doc
      int freq = postings.freq();
      for (int i = 0; i < freq; i++) {
        int pos = postings.nextPosition();
        words = ArrayUtil.grow(words, pos + 1);
        words[pos] = bytes.utf8ToString();
      }
    }
    return words;
  }

  /**
   * Tokens of a doc as strings from bytes.
   * @param ref
   * @return An indexed document as an array of strings.
   * @throws IOException
   */
  public String[] strings(BytesRef ref) throws IOException
  {
    return strings(ref.bytes, ref.offset, ref.length);
  }
  
  /**
   * Tokens of a doc as strings from a byte array
   * @param rail Binary version an int array
   * @param offset Start index in the array
   * @param length Length of bytes to consider from offset
   * @return
   * @throws IOException
   */
  public String[] strings(byte[] rail, int offset, int length) throws IOException
  {
    ByteBuffer buf = ByteBuffer.wrap(rail, offset, length);
    int size = length / 4;
    String[] words = new String[size];
    BytesRef ref = new BytesRef();
    for (int pos = 0; pos < size; pos++) {
      int termId = buf.getInt();
      this.hashDic.get(termId, ref);
      words[pos] = ref.utf8ToString();
    }
    return words;
  }

}
