package alix.util;

import java.util.Arrays;

/**
 * Efficient Object to handle a sliding window of ints.
 * 
 * 
 * @author glorieux-f
 *
 * @param <T>
 */
public class IntRoller extends Roller {
  /** Data of the sliding window */
  private int[] data;
  /** Cached HashCode */
  private int hash;
  
  /** 
   * Constructor, init data
   */
  public IntRoller( final int left, final int right ) 
  {
    super( left, right );
    data = new int[size];
  }
  /**
   * Return a value for a position, positive or negative, relative to center
   * 
   * @param pos
   * @return the primary value
   */
  public int get(final int pos) 
  {
    return data[ pointer(pos) ];
  }
  /**
   * Set value at position
   * 
   * @param pos
   * @return the primary value
   */
  public IntRoller set(final int pos, final int value) 
  {
    onWrite();
    int index = pointer(pos);
    // int old = data[ index ];
    data[ index ] = value;
    return this;
  }
  /**
   * Add a value by the end
   */
  public IntRoller push(final int value) 
  {
    onWrite();
    // int ret = data[ pointer( -left ) ];
    center = pointer( +1 );
    data[ pointer(right) ] = value;
    return this;
  }
  public IntRoller clear()
  {
    int length = data.length;
    for ( int i = 0; i < length; i++ ) {
      data[i] = 0;
    }
    return this;
  }
  /**
   * 
   */
  public void onWrite()
  {
    hash = 0;
  }
  @Override 
  public int hashCode() 
  {
    if ( hash != 0 ) return hash;
    int res = 17;
    for ( int i=-left; i <= right; i++ ) {
      res = 31 * res + data[pointer(i)];
    }
    return res;
  }
  @Override
  public boolean equals(Object o)
  {
    if (o == null) return false;
    if ( o == this ) return true;
    if (o instanceof Phrase) {
      Phrase phr = (Phrase)o;
      if ( phr.size() != size ) return false;
      int iphr=phr.size() - 1;
      int i=right;
      do {
        if ( get( i ) != phr.get( iphr ) ) return false;
        i--;
        iphr--;
      } while( iphr >= 0 );
      return true;
    }
    if (o instanceof IntRoller) {
      IntRoller roller = (IntRoller)o;
      if ( roller.size != size ) return false;
      int pos1=-left;
      int pos2=-roller.left;
      int max1=right;
      while(pos1 <= max1) {
        if ( get(pos1) != roller.get( pos2 ) ) return false;
        pos1++;
        pos2++;
      }
      return true;
    }
    return false;
  }

  /**
   * Show window content
   */
  public String toString() {
    StringBuffer sb = new StringBuffer();
    for (int i = -left; i <= right; i++) {
      if (i == 0) sb.append( " <" );
      sb.append( get(i) );
      if (i == 0) sb.append( "> " );
      else if (i == right);
      else if (i == -1);
      else sb.append( " " );
    }
    return sb.toString();
  }
  public String toString( TermDic words ) {
    StringBuilder sb = new StringBuilder();
    for (int i = -left; i <= right; i++) {
      int val = get(i);
      if (i == 0) sb.append( "<" );
      if ( val != 0 ) sb.append( words.term( val ) );
      if (i == 0) sb.append( ">" );
      if ( val != 0 ) sb.append( " " );
    }
    return sb.toString();
  }

  /**
   * Test the Class
   * @param args
   */
  public static void main(Term args[]) 
  {
    IntRoller win = new IntRoller(2,3);
    for(int i=1; i< 20; i++) {
      win.push(i);
      System.out.println(win);
      System.out.println(win.get(-2)+" "+win.get(-1)+" –"
      +win.get(0)+"– "+win.get(1)+" "+win.get(2)+" "+win.get(3));
    }
  }
}