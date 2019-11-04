package alix.lucene.analysis;

import java.io.IOException;
import java.io.StringReader;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute;

import alix.fr.Tag;
import alix.lucene.analysis.tokenattributes.CharsAtt;
import alix.lucene.analysis.tokenattributes.CharsLemAtt;
import alix.lucene.analysis.tokenattributes.CharsOrthAtt;

public class TestFrAnalyzer
{
  static class MetaAnalyzer extends Analyzer
  {

    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new MetaTokenizer();
      return new TokenStreamComponents(source);
    }

  }
  static class AnalyzerTokfr extends Analyzer
  {

    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new FrTokenizer();
      return new TokenStreamComponents(source);
    }

  }

  static class AnalyzerFull extends Analyzer
  {

    @Override
    protected TokenStreamComponents createComponents(String fieldName)
    {
      final Tokenizer source = new FrTokenizer();
      TokenStream result = new FrTokenLem(source);
      result = new TokenLemFull(result);
      return new TokenStreamComponents(source, result);
    }

  }

  public static void main(String[] args) throws IOException
  {
    // text to tokenize
    
    String text = "Emploie-t-il beaucoup de navires ? Réveille-le. "
        + "<a href=\"note\">XVII.</a> et XLV, GRYMALKIN. Mais lorsque la Grâce t’illumine de nouveau. "
        + "Le Siècle, La Plume, La Nouvelle Revue. Mot<a>note</a>. "
        + " -- Quadratin. U.K.N.O.W.N. La Fontaine... Quoi ???\" + \" Problème</section>. "
        + "L'errance de la Personne. François de Saint-Ouen Est-ce bien ?"
        + "M. P. de Noailles. Jackques Le Cornu. Comité d'État, let. A. dit-il. Souviens-toi !"
        + "6. Des experts suisses sont associés aux Parties contractantes peuvent échanger. "
        + "II La Suisse jouit des mêmes droits que les Etats membres. "
        + "L'Etat, c'est moi. C'est-à-dire l'Etre.<script>NAZE</script> "
        + "Souviens-toi. Que faut-il en faire ? Faut-il en dire plus ?"
        + "<p rend=\"block\"> (1) La confession des États-Unis. 1. Le petit chat est mort. "
        + "relativité souvenez-vous des décrets humains.<lb/>\n" + 
        "Le prix de mes <p xml:id='pp'>Qu'en penses-tu ? "
        + "C’est m&eacute;connaître 1,5 &lt; -1.5 cts &amp; M<b>o</b>t. Avec de <i>l'italique</i>"
        + "FIN.";
   
    text = "\n" + 
        "         <span class=\"byline\">\n" + 
        "            <span class=\"persName\">\n" + 
        "               <span class=\"surname\">Âubignac</span>, François H&eacute;delin &gt; &eacute;  Black & Mortimer </span>.</span> \n" + 
        "         <span class=\"title\">Dissertation sur la condemnation des théâtres</span> \n" + 
        "         <span class=\"year\">(1666)</span>. <span class=\"pages\">pp. 188-216</span>.  « <span class=\"analytic\">Disseration sur la Condemnation.   des Théâtres. — Chapitre IX.   Que les Acteurs des Poèmes Dramatiques n'étaient point infâmes parmi les Romains, mais seulement les Histrions ou Bateleurs.</span> »";

    Analyzer[] analyzers = {  
        new MetaAnalyzer(),
        // new StandardAnalyzer(),
        // new FrenchAnalyzer()
    };
    for (Analyzer analyzer : analyzers) {
      System.out.println(analyzer.getClass());
      System.out.println();
      TokenStream stream = analyzer.tokenStream("stats", new StringReader(text));

      // get the CharTermAttribute from the TokenStream
      CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
      CharsLemAtt lem = stream.addAttribute(CharsLemAtt.class);
      CharsOrthAtt orth = stream.addAttribute(CharsOrthAtt.class);
      OffsetAttribute offset = stream.addAttribute(OffsetAttribute.class);
      FlagsAttribute flags = stream.addAttribute(FlagsAttribute.class);
      PositionIncrementAttribute posInc = stream.addAttribute(PositionIncrementAttribute.class);
      PositionLengthAttribute posLen = stream.addAttribute(PositionLengthAttribute.class);
      try {
        stream.reset();
        // print all tokens until stream is exhausted
        while (stream.incrementToken()) {
          System.out.print(
            term 
            + "\t" + Tag.label(flags.getFlags())
            +"\t" + orth  
            + " |" + text.substring(offset.startOffset(), offset.endOffset()) + "|"
            + " " + offset.startOffset() + "-" + offset.endOffset()
            + " (" + posInc.getPositionIncrement() + ", " + posLen.getPositionLength() + ")"
          );
          System.out.println();
        }
        
        stream.end();
      }
      finally {
        stream.close();
        analyzer.close();
      }
      System.out.println();
    }
  }

}