package alix.lucene.search;

public class ScorerTfidf extends Scorer
{
  /** Store idf */
  float idf;
  /** A traditional coefficient */
  final float k = 0.2F;

  public ScorerTfidf()
  {
    
  }
  
  ScorerTfidf(long occsAll, int docsAll)
  {
    setAll(occsAll, docsAll);
  }

  @Override
  public void weight(final long occsMatch, final int docsMatch)
  {
    double l = 0;
    this.idf = (float) (Math.log((docsAll +l ) / (double) (docsMatch + l)) );
  }

  @Override
  public float score(final int occsMatch, final long docLen)
  {
    return idf * (k + (1 - k) * (float) occsMatch / (float) docLen);
    // return idf * (1 +(float)Math.log(occsMatch));
  }

}