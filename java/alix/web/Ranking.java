package alix.web;

import alix.lucene.search.*;

public enum Ranking implements Option {
  
  occs("Occurrences") {
    @Override
    public Specif specif() {
      return new SpecifOccs();
    }
  },
  
  g("G-Test (Frantext 2018)") {
    @Override
    public Specif specif() {
      return new SpecifG();
    }
  },

  chi2("Chi2 (Muller, Brunet)") {
    @Override
    public Specif specif() {
      return new SpecifChi2();
    }
  },


  hypergeo("Loi hypergeometrique (Lafon)") {
    @Override
    public Specif specif() {
      return new SpecifHypergeo();
    }
  },


  /* pas bon 
  binomial("Loi binomiale") {
    @Override
    public Specif specif() {
      return new SpecifBinomial();
    }
  },
  */
  
  bm25("BM25") {
    @Override
    public Specif specif() {
      return new SpecifBM25();
    }
    
  },

  tfidf("tf-idf") {
    @Override
    public Specif specif() {
      return new SpecifTfidf();
    }
    
  },
  /* Bof
  jaccard("Jaccard") {
    @Override
    public Specif specif() {
      return new SpecifJaccard();
    }
  },

  jaccardtf("Jaccard (par document)") {
    @Override
    public Specif specif() {
      return new SpecifJaccardTf();
    }
  },
  
  dice("Dice") {
    @Override
    public Specif specif() {
      return new SpecifDice();
    }
  },
  
  dicetf("Dice (par document)") {
    @Override
    public Specif specif() {
      return new SpecifDiceTf();
    }
  },
  */

  alpha("Naturel") {
    @Override
    public Specif specif() {
      return null;
    }
  },



  
  ;

  abstract public Specif specif();

  
  private Ranking(final String label) {  
    this.label = label ;
  }

  // Repeating myself
  final public String label;
  public String label() { return label; }
  public String hint() { return null; }
}
