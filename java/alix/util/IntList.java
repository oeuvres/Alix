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
package alix.util;

import java.util.Arrays;

import alix.maths.Calcul;

/**
 * A mutable list of ints.
 */
public class IntList implements Comparable<IntList>
{
  /** Internal data */
  protected int[] data;
  /** Current size */
  protected int length;
  /** Cache an hash code */
  protected int hash;

  /**
   * Simple constructor.
   */
  public IntList()
  {
    data = new int[4];
  }
  /**
   * Constructor with an estimated size.
   * @param capacity
   */
  public IntList(int capacity)
  {
    data = new int[capacity];
  }
  /**
   * Wrap an existing int array.
   * 
   * @param data
   */
  public IntList(int[] data)
  {
    this.data = data;
  }


  /**
   * Light reset data, with no erase.
   */
  public IntList reset()
  {
    length = 0;
    return this;
  }
  
  /**
   * Test if vector is empty
   * @return
   */
  public boolean isEmpty()
  {
    return (length < 1);
  }

  /**
   * Size of data.
   * 
   * @return
   */
  public int length()
  {
    return length;
  }


  /**
   * Push on more value at the end
   * 
   * @param value
   */
  public IntList push(int value)
  {
    final int pos = length;
    length++;
    grow(pos);
    data[pos] = value;
    return this;
  }

  /**
   * Push a copy of an int array.
   * 
   * @param data
   */
  protected IntList push(int[] data)
  {
    int newSize = this.length + data.length;
    grow(newSize);
    System.arraycopy(data, 0, this.data, length, data.length);
    length = newSize;
    return this;
  }
  
  /**
   * Get int at a position.
   * 
   * @param pos
   * @return
   */
  public int get(int pos)
  {
    return data[pos];
  }

  /**
   * Get first value or cry if list is empty
   * 
   * @param pos
   * @return
   */
  public int first()
  {
    if (length < 1) throw new ArrayIndexOutOfBoundsException("The list is empty, no first element");
    return data[0];
  }

  /**
   * Get last value or cry if list is empty
   * 
   * @param pos
   * @return
   */
  public int last()
  {
    if (length < 1) throw new ArrayIndexOutOfBoundsException("The list is empty, no first element");
    return data[length - 1];
  }


  /**
   * Change value at a position
   * 
   * @param pos
   * @param value
   */
  public IntList put(int pos, int value)
  {
    grow(pos);
    data[pos] = value;
    return this;
  }

  /**
   * Add value at a position
   * 
   * @param pos
   * @param value
   */
  public IntList add(int pos, int value)
  {
    grow(pos) ;
    data[pos] += value;
    return this;
  }

  /**
   * Increment value at a position
   * 
   * @param pos
   */
  public IntList inc(int pos)
  {
    grow(pos);
    data[pos]++;
    return this;
  }
  
  /**
   * Return an int array of unique values from this list 
   */
  public int[] uniq()
  {
    int[] work = new int[length];
    System.arraycopy(data, 0, work, 0, length);
    Arrays.sort(work);
    int destSize = 1;
    int last = work[0];
    for (int i = destSize; i < length; i++) {
      if (work[i] == last) continue;
      work[destSize] = last = work[i];
      destSize++;
    }
    int[] dest = new int[destSize];
    System.arraycopy(work, 0, dest, 0, destSize);
    return dest;
  }

  /**
   * Call it before write
   * 
   * @param position
   * @return true if resized (? good ?)
   */

  protected boolean grow(final int position)
  {
    if (position >= length) length = (position + 1);
    hash = 0;
    if (position < data.length) return false;
    final int oldLength = data.length;
    final int[] oldData = data;
    int capacity = Calcul.nextSquare(position + 1);
    data = new int[capacity];
    System.arraycopy(oldData, 0, data, 0, oldLength);
    return true;
  }

  /**
   * Get data as an int array.
   * @return
   */
  public int[] toArray()
  {
    int[] dest = new int[length];
    System.arraycopy(data, 0, dest, 0, length);
    return dest;
  }

  /**
   * Get a pointer on underlaying array (unsafe).
   * @return
   */
  public int[] data()
  {
    return data;
  }

  @Override
  public String toString()
  {
    StringBuffer sb = new StringBuffer();
    sb.append('(');
    for (int i = 0; i < length; i++) {
      if (i > 0) sb.append(", ");
      sb.append(data[i]);
    }
    sb.append(')');
    return sb.toString();
  }

  @Override
  public boolean equals(final Object o)
  {
    if (o == null) return false;
    if (o == this) return true;
    if (o instanceof IntList) {
      IntList list = (IntList) o;
      if (list.length != length) return false;
      for (short i = 0; i < length; i++) {
        if (list.data[i] != data[i]) return false;
      }
      return true;
    }
    return false;
  }
  
  @Override
  public int compareTo(final IntList list)
  {
    if (length != list.length) return Integer.compare(length, list.length);
    int lim = length; // avoid a content lookup
    for (int i = 0; i < lim; i++) {
      if (data[i] != list.data[i]) return Integer.compare(data[i], list.data[i]);
    }
    return 0;
  }


  @Override
  public int hashCode()
  {
    if (hash != 0) return hash;
    int res = 17;
    for (int i = 0; i < length; i++) {
      res = 31 * res + data[i];
    }
    return res;
  }

}
