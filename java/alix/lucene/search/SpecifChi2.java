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

/**
 * Implementation of a Chi2 scorer
 * 
 * <li>formPart  f : la fréquence de l’événement dans la partie ;
 * <li>formAll   F : la fréquence totale de l’événement dans le corpus ;
 * <li>wcPart    t : le nombre total d’événements ayant lieu dans la partie ;
 * <li>wcAll     T : le nombre total d’événements ayant lieu dans l’ensemble des parties.
 * 
 * 
 * @author fred
 *
 */
public class SpecifChi2 extends Specif
{
  @Override
  public int type() {
    return TYPE_PROB;
  }


  /**
   * Find a 2x2 chi-square value.
   * Note: could do this more neatly using simplified formula for 2x2 case.
   *
   * @param N The total number of balls
   * @param K The number of black balls
   * @param n The number of balls drawn
   * @param k The number of black balls drawn
   * @return The Fisher's exact p-value
   */
  public static double chi2(long N, int K, int n, int k) {
    if (k < 0 || K > N || n > N || N <= 0 || n < 0 || K < 0) {
      throw new IllegalArgumentException("Invalid chi2 params"+" N="+N+" K="+K+" n="+n+" k="+k);
    }
    long[][] cg = {{k, K - k}, {n - k, N - (k + (K - k) + (n - k))}};
    long[] cgr = {K, N - K};
    long[] cgc = {n, N - n};
    double total = 0.0;
    for (int i = 0; i < 2; i++) {
      for (int j = 0; j < 2; j++) {
        double exp = (double) cgr[i] * cgc[j] / N;
        total += (cg[i][j] - exp) * (cg[i][j] - exp) / exp;
      }
    }
    return total;
  }

  @Override
  public double prob(final long formPartOccs, final long formAllOccs)
  {
    if (formAllOccs < 4) return 0;

    // if (formPartOccs < FLOOR) return 0;
    double p = chi2((int)allOccs, (int)formAllOccs,  (int)partOccs, (int)formPartOccs);
    // System.out.println("N="+ (int)allOccs +" K="+ (int)formAllOccs +" n="+(int)partOccs + " k="+(int)formPartOccs+ " p="+p);
    double mean = (double)formAllOccs / allOccs;
    if ((formPartOccs / partOccs) > mean) return p;
    else return -p;
  }

}
