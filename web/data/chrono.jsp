<%@ page language="java" contentType="text/javascript; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="common.jsp" %>
<%!
/** A record used to sort docid by date */
/** 0.1ms no real need to cache */
public String ticks(PageContext pageContext, Alix alix) throws IOException  {
  
  ServletContext application = pageContext.getServletContext();
  
  int min = alix.min(YEAR);
  int max = alix.max(YEAR);
  StringBuilder sb = new StringBuilder();

  int span = max - min;
  int yearStep = 5;
  if (span > 400) yearStep = 10;
  else if (span > 200) yearStep = 5;
  else if (span > 75) yearStep = 2;
  else yearStep = 1;
  
  long total = alix.reader().getSumTotalTermFreq(TEXT);
  long labelWidth = (long)Math.ceil((float) total / 30); // width of a label in tokens
  
  long[] docLength = alix.docLength(TEXT);
  

  
  sb.append("[\n");
  // give the first year
  int label = min - min % yearStep;
  // int label = min;
  sb.append("    {\"v\": 0, \"label\": " + min + "}");
  // get Axis data, but resort it in cumulate order
  Tick[] axis = alix.axis(TEXT, YEAR);
  
  Arrays.sort(axis, new Comparator<Tick>() {
    @Override
    public int compare(Tick tick1, Tick tick2) {
      if (tick1.cumul < tick2.cumul) return -1;
      if (tick1.cumul > tick2.cumul) return +1;
      return 0;
    }
  });
  
  long cumulLast = 0;
  boolean first = true;
  int lastYear = Integer.MIN_VALUE;
  long cumul = 0;
  for (int i = 0, length = axis.length; i < length; i++) {
    if (axis[i].length == 0) continue; // A deleted doc
    int year = axis[i].rank;
    if (year < lastYear) {
      sb.append("\nBUG\n");
    }
    if (year == lastYear) continue;
    lastYear = year;
    if (year < label) continue;
    label = year - year % yearStep;
   
    sb.append(",\n");
    cumul = axis[i].cumul;
    sb.append("    {");
    sb.append("\"v\": "+cumul);
    // let space between labels
    if (cumul - cumulLast > labelWidth) {
      sb.append(", \"label\": "+label);
      cumulLast = cumul;
    }
    sb.append("}");
    lastYear = year;
    label = label + yearStep;
  }
  // give the last year ?
  // if (cumul != total) sb.append("\n    {\"v\": "+ total+", \"label\": " + max + "}");
  sb.append("\n  ]");
  return sb.toString();
}%>
<%
// needs the bits of th filter

// number of fots by curve, could be a parameter

int dots = getParameter(request, "dots", 200);

// build queries
time = System.nanoTime();
IndexReader reader = alix.reader();

long total = reader.getSumTotalTermFreq(TEXT);

int maxDoc = reader.maxDoc();
// OK if no deleted docs



out.println("{");
// display ticks
long partial = System.nanoTime();
String ticks = (String)alix.cache("ticks-year");
if (ticks == null) {
  ticks = ticks(pageContext, alix);
  alix.cache("ticks-year", ticks);
}
out.print( "  \"ticks\": "+ticks+",\n");
out.println("  \"time\" : \"" + (System.nanoTime() - partial) / 1000000.0 + "ms\",");



//parse the query by line
TermList terms = alix.qTerms(q, TEXT);
if (terms.size() > 0) {
  terms.sortByRowFreq();
  out.print("  \"labels\": [\"\"");
  boolean first = true;
  for(Term t: terms) {
    if (t == null) {
      out.print("\"");
      first = true;
      continue;
    }
    if (first) {
      out.print(", \"");
      first = false;
    }
    else out.print(", ");
    out.print(t.text());
  }
  out.println("],");
  
  int cols = terms.rows();
  // table of data to populate
  long[][] data = new long[cols][dots];
  // width of a step between two dots, 
  long step = (total) / dots;
  // axis index
  Tick[] axis = (Tick[])alix.cache("axis-year");
  if (axis == null) {
    axis = alix.axis(TEXT, YEAR);
    alix.cache("axis-year", axis);
  }
  // loop on contexts, because open a context is heavy, do not open too much
  for (LeafReaderContext ctx : reader.leaves()) {
    LeafReader leaf = ctx.reader();
    int docBase = ctx.docBase;
    // Do as a termQuery, loop on PostingsEnum.FREQS for each term
    // loop on terms
    int col = 0;
    for(Term term: terms) {
      if (term == null) {
        col++;
        continue;
      }
      PostingsEnum postings = leaf.postings(term);
      if (postings == null) continue;
      int docLeaf;
      long freq;
      long[] column = data[col];
      while((docLeaf = postings.nextDoc()) !=  DocIdSetIterator.NO_MORE_DOCS) {
        int docId = docBase + docLeaf;
        if (filter!= null && !filter.get(docId)) continue;
        if ((freq = postings.freq()) == 0) continue;
        int row = (int)(axis[docId].cumul / step);
        if (row >= dots) row = dots - 1; // because of rounding on big numbers last row could be missed
        column[row] += freq;
      }
    }
  }
  

  out.println("  \"data\": [");
  first = true;
  for (int row = 0; row < dots; row++) {
    // empty row, go throw
    long sum = 0;
    for (int col = 0; col < cols; col++) sum += data[col][row];
    if (sum == 0) continue;
    if (first) first = false;
    else out.print(",\n");
    out.print("    [");
    out.print((step * row));
    for (int col = 0; col < cols; col++){
      out.print(", ");
      long count = data[col][row];
      if (count < 2) {
        out.print("null");
        continue;
      }
      double ppm = Math.round(10 * 100000.0 * count / step) / 10.0;
      out.print(ppm);
    }
    out.print("]");
  }
  out.println("\n  ],");
}
out.println("  \"time\" : \"" + (System.nanoTime() - time) / 1000000.0 + "ms\"");
out.println("\n}");
%>